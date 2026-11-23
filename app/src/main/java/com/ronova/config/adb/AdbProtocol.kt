package com.ronova.config.adb

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class AdbShellResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean,
    val durationMs: Long,
    val errorMessage: String? = null
)

sealed interface AdbConnectionState {
    data object Disconnected : AdbConnectionState
    data object Connecting : AdbConnectionState
    data class Connected(val host: String, val port: Int) : AdbConnectionState
    data class Failed(val reason: String) : AdbConnectionState
}

/**
 * Persistent TLS ADB session to this phone's own wireless-debugging daemon.
 * All stream I/O is serialised by one mutex; dropped sessions are re-established
 * automatically (once per command) before the command is sent.
 */
class AdbProtocol private constructor(context: Context) {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_AUTH = 0x48545541
        private const val A_STLS = 0x534c5453

        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val COMMAND_TIMEOUT_MS = 90_000L

        @Volatile
        private var instance: AdbProtocol? = null

        fun get(context: Context): AdbProtocol =
            instance ?: synchronized(this) {
                instance ?: AdbProtocol(context.applicationContext).also { instance = it }
            }
    }

    private class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private class StaleConnection(message: String?) : Exception(message ?: "ADB session dropped")

    private class ShellV2Parser {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode = -1
        private var pending = ByteArray(0)

        fun feed(data: ByteArray) {
            pending += data
            var off = 0
            while (pending.size - off >= 5) {
                val id = pending[off].toInt() and 0xff
                val len = ByteBuffer.wrap(pending, off + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (len < 0) {
                    pending = ByteArray(0)
                    return
                }
                if (pending.size - off - 5 < len) break
                val start = off + 5
                when (id) {
                    1 -> stdout.write(pending, start, len)
                    2 -> stderr.write(pending, start, len)
                    3 -> if (len > 0) exitCode = pending[start].toInt() and 0xff
                }
                off = start + len
            }
            pending = pending.copyOfRange(off, pending.size)
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ronova_adb", Context.MODE_PRIVATE)
    private val keyManager = AdbKeyManager(appContext)
    private val discovery = AdbMdnsDiscovery(appContext)
    private val pairingClient = AdbPairingClient(keyManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()
    val discoveryState: StateFlow<AdbDiscoveryState> get() = discovery.state

    val isPaired: Boolean get() = prefs.getBoolean("paired", false)

    // Guarded by ioMutex
    private var rawSocket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var features: Set<String> = emptySet()
    private var maxData = 4096
    private var nextLocalId = 1
    private var lastEndpoint: AdbEndpoint? = null

    // ───────────────────────── Discovery & pairing ─────────────────────────

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> {
        val result = pairingClient.pair(host, port, pairingCode)
        if (result.isSuccess) prefs.edit().putBoolean("paired", true).apply()
        return result
    }

    suspend fun pairWithDiscovered(pairingCode: String): Result<Unit> {
        discovery.start()
        val endpoint = discovery.state.value.pairingEndpoint
            ?: withTimeoutOrNull(6_000) { discovery.state.first { it.pairingEndpoint != null } }?.pairingEndpoint
            ?: return Result.failure(
                AdbException("No pairing service found. Open Developer options → Wireless debugging → Pair device with pairing code first.")
            )
        return pair(endpoint.host, endpoint.port, pairingCode)
    }

    // ───────────────────────── Connection ─────────────────────────

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                connectLocked(host, port)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLocked(failure = e.message)
                Result.failure(if (e is AdbException) e else AdbException(e.message ?: "Connect failed", e))
            }
        }
    }

    suspend fun connectAuto(): Result<Unit> {
        discovery.start()
        val endpoint = discovery.state.value.connectEndpoint
            ?: withTimeoutOrNull(6_000) { discovery.state.first { it.connectEndpoint != null } }?.connectEndpoint
            ?: lastEndpoint
            ?: return Result.failure(
                AdbException("Wireless debugging is not running. Turn it on in Developer options.")
            )
        return connect(endpoint.host, endpoint.port)
    }

    fun disconnect() {
        scope.launch { ioMutex.withLock { closeLocked(failure = null) } }
    }

    fun close() {
        disconnect()
        discovery.stop()
    }

    private fun connectLocked(host: String, port: Int) {
        closeLocked(failure = null)
        _connectionState.value = AdbConnectionState.Connecting

        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = READ_TIMEOUT_MS
        rawSocket = raw

        var inp: InputStream = raw.getInputStream()
        var out: OutputStream = raw.getOutputStream()

        writeMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray())
        var msg = readMessage(inp)

        if (msg.command == A_STLS) {
            writeMessage(out, A_STLS, A_STLS_VERSION, 0)
            try {
                val ssl = keyManager.createSslContext().socketFactory
                    .createSocket(raw, host, port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.enabledProtocols = arrayOf("TLSv1.3")
                ssl.startHandshake()
                tlsSocket = ssl
                inp = ssl.inputStream
                out = ssl.outputStream
            } catch (e: SSLException) {
                throw AdbException(
                    "TLS handshake rejected by adbd. Pair again: Wireless debugging → Pair device with pairing code.",
                    e
                )
            }
            msg = readMessage(inp)
        }

        if (msg.command == A_AUTH) {
            throw AdbException("Device is asking for legacy key authorisation. Pair via Wireless debugging first.")
        }
        if (msg.command != A_CNXN) {
            throw AdbException("Unexpected reply from adbd (0x${Integer.toHexString(msg.command)})")
        }

        val banner = String(msg.data, Charsets.UTF_8)
        features = banner.substringAfter("features=", "")
            .substringBefore(';')
            .split(',')
            .map { it.trim().trim('\u0000') }
            .filter { it.isNotEmpty() }
            .toSet()
        maxData = msg.arg1.coerceIn(1024, MAX_PAYLOAD)

        input = inp
        output = out
        lastEndpoint = AdbEndpoint(host, port, "")
        prefs.edit().putBoolean("paired", true).apply()
        _connectionState.value = AdbConnectionState.Connected(host, port)
    }

    private fun closeLocked(failure: String?) {
        try { tlsSocket?.close() } catch (_: Exception) {}
        try { rawSocket?.close() } catch (_: Exception) {}
        tlsSocket = null
        rawSocket = null
        input = null
        output = null
        _connectionState.value =
            if (failure != null) AdbConnectionState.Failed(failure) else AdbConnectionState.Disconnected
    }

    private fun openLocked() {
        val endpoint = discovery.state.value.connectEndpoint ?: lastEndpoint
            ?: throw AdbException("No wireless-debugging endpoint known. Turn on Wireless debugging.")
        connectLocked(endpoint.host, endpoint.port)
    }

    // ───────────────────────── Shell execution ─────────────────────────

    suspend fun executeShell(cmd: String): AdbShellResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        ioMutex.withLock {
            var failure: String? = null
            for (attempt in 0..1) {
                try {
                    if (input == null) openLocked()
                    return@withLock runShellLocked(cmd, started)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StaleConnection) {
                    failure = e.message
                    closeLocked(failure = null)
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                    closeLocked(failure = failure)
                    break
                }
            }
            AdbShellResult(
                command = cmd,
                stdout = "",
                stderr = "",
                exitCode = -1,
                success = false,
                durationMs = SystemClock.elapsedRealtime() - started,
                errorMessage = failure ?: "Unknown ADB error"
            )
        }
    }

    private fun runShellLocked(cmd: String, started: Long): AdbShellResult {
        val inp = input ?: throw StaleConnection("Not connected")
        val out = output ?: throw StaleConnection("Not connected")
        val v2 = "shell_v2" in features
        val service = if (v2) "shell,v2,raw:$cmd" else "shell:$cmd; echo \"@@RC=\$?\""

        val localId = nextLocalId++
        if (nextLocalId == Int.MAX_VALUE) nextLocalId = 1

        try {
            writeMessage(out, A_OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw StaleConnection(e.message)
        }

        var remoteId = 0
        var opened = false
        var finished = false
        var error: String? = null
        val parser = if (v2) ShellV2Parser() else null
        val plain = ByteArrayOutputStream()
        val deadline = started + COMMAND_TIMEOUT_MS

        while (!finished) {
            if (SystemClock.elapsedRealtime() > deadline) {
                error = "Command timed out"
                try { writeMessage(out, A_CLSE, localId, remoteId) } catch (_: IOException) {}
                break
            }
            val msg = try {
                readMessage(inp)
            } catch (e: IOException) {
                if (!opened) throw StaleConnection(e.message)
                throw e
            }
            when (msg.command) {
                A_OKAY -> if (msg.arg1 == localId) {
                    remoteId = msg.arg0
                    opened = true
                }
                A_WRTE -> if (msg.arg1 == localId) {
                    remoteId = msg.arg0
                    opened = true
                    if (parser != null) parser.feed(msg.data) else plain.write(msg.data)
                    writeMessage(out, A_OKAY, localId, remoteId)
                }
                A_CLSE -> if (msg.arg1 == localId) {
                    if (!opened) error = "adbd rejected the shell service"
                    else try { writeMessage(out, A_CLSE, localId, remoteId) } catch (_: IOException) {}
                    finished = true
                }
                else -> Unit
            }
        }

        val stdoutText: String
        val stderrText: String
        val exit: Int
        if (parser != null) {
            stdoutText = String(parser.stdout.toByteArray(), Charsets.UTF_8)
            stderrText = String(parser.stderr.toByteArray(), Charsets.UTF_8)
            exit = if (parser.exitCode >= 0) parser.exitCode else if (error == null) 0 else -1
        } else {
            val raw = String(plain.toByteArray(), Charsets.UTF_8)
            val match = Regex("@@RC=(\\d+)\\s*$").find(raw)
            exit = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
            stdoutText = if (match != null) raw.removeRange(match.range) else raw
            stderrText = ""
        }

        return AdbShellResult(
            command = cmd,
            stdout = stdoutText.trimEnd(),
            stderr = stderrText.trimEnd(),
            exitCode = exit,
            success = exit == 0 && error == null,
            durationMs = SystemClock.elapsedRealtime() - started,
            errorMessage = error
        )
    }

    // ───────────────────────── Wire format ─────────────────────────

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("ADB connection closed")
            off += n
        }
    }

    private fun readMessage(stream: InputStream): AdbMessage {
        val header = ByteArray(24)
        readFully(stream, header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.getInt()
        val arg0 = bb.getInt()
        val arg1 = bb.getInt()
        val length = bb.getInt()
        bb.getInt() // data checksum (not verified; skipped by protocol >= 0x01000001)
        val magic = bb.getInt()
        if (magic != command.inv()) throw IOException("Corrupt ADB packet header")
        if (length < 0 || length > MAX_PAYLOAD * 4) throw IOException("Invalid ADB payload size $length")
        val data = ByteArray(length)
        if (length > 0) readFully(stream, data)
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun writeMessage(
        stream: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray = ByteArray(0)
    ) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        val bb = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        bb.put(data)
        stream.write(bb.array())
        stream.flush()
    }
}
