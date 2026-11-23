package com.ronova.config.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Minimal edwards25519 arithmetic, just enough for BoringSSL-compatible SPAKE2. */
internal object Ed25519 {
    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)

    val P: BigInteger = ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(ONE).shiftRight(2), P)

    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(ZERO, ONE, ONE, ZERO)

    fun leToBig(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size)
        for (i in bytes.indices) be[i] = bytes[bytes.size - 1 - i]
        return BigInteger(1, be)
    }

    fun bigToLe(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        val count = minOf(be.size, size)
        for (i in 0 until count) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun decodeY(y: BigInteger, sign: Int): Point? {
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(ONE).mod(P)
        val v = D.multiply(y2).add(ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).mod(P) != x2) return null
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
        return Point(x, y, ONE, x.multiply(y).mod(P))
    }

    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val copy = bytes.copyOf()
        val sign = (copy[31].toInt() ushr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        return decodeY(leToBig(copy), sign)
    }

    fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = bigToLe(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun add(a: Point, b: Point): Point {
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val cc = a.t.multiply(D2).multiply(b.t).mod(P)
        val dd = a.z.multiply(b.z).multiply(TWO).mod(P)
        val e = bb.subtract(aa).mod(P)
        val f = dd.subtract(cc).mod(P)
        val g = dd.add(cc).mod(P)
        val h = bb.add(aa).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P)
        )
    }

    fun negate(p: Point): Point = Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

    fun scalarMul(k: BigInteger, p: Point): Point {
        var r = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    val BASE: Point = decodeY(
        BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P), 0
    )!!

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** BoringSSL SPAKE2 blinding points. */
    val M: Point =
        decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
    val N: Point =
        decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!
}

/** BoringSSL-compatible SPAKE2 over edwards25519 (the variant used by adb pairing). */
internal class Spake2(
    private val isAlice: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray
) {
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar: BigInteger = Ed25519.leToBig(passwordHash).mod(Ed25519.L)
    private val priv: BigInteger
    private val myMsg: ByteArray

    init {
        val rnd = ByteArray(64)
        SecureRandom().nextBytes(rnd)
        priv = Ed25519.leToBig(rnd).mod(Ed25519.L).shiftLeft(3)
        val mask = Ed25519.scalarMul(passwordScalar, if (isAlice) Ed25519.M else Ed25519.N)
        val p = Ed25519.add(Ed25519.scalarMul(priv, Ed25519.BASE), mask)
        myMsg = Ed25519.encode(p)
    }

    fun message(): ByteArray = myMsg.copyOf()

    /** Returns the 64-byte shared key, or null when the peer message is malformed. */
    fun processMessage(theirMsg: ByteArray): ByteArray? {
        val qStar = Ed25519.decode(theirMsg) ?: return null
        val peerMask = Ed25519.scalarMul(passwordScalar, if (isAlice) Ed25519.N else Ed25519.M)
        val q = Ed25519.add(qStar, Ed25519.negate(peerMask))
        val dh = Ed25519.encode(Ed25519.scalarMul(priv, q))

        val sha = MessageDigest.getInstance("SHA-512")
        if (isAlice) {
            lengthPrefixed(sha, myName)
            lengthPrefixed(sha, theirName)
            lengthPrefixed(sha, myMsg)
            lengthPrefixed(sha, theirMsg)
        } else {
            lengthPrefixed(sha, theirName)
            lengthPrefixed(sha, myName)
            lengthPrefixed(sha, theirMsg)
            lengthPrefixed(sha, myMsg)
        }
        lengthPrefixed(sha, dh)
        lengthPrefixed(sha, passwordHash)
        return sha.digest()
    }

    private fun lengthPrefixed(sha: MessageDigest, data: ByteArray) {
        val len = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) {
            len[i] = (l and 0xff).toByte()
            l = l ushr 8
        }
        sha.update(len)
        sha.update(data)
    }
}

/** AES-128-GCM channel keyed from the SPAKE2 secret (HKDF-SHA256), 64-bit LE counter IVs. */
internal class PairingCipher(secret: ByteArray) {
    private val key = SecretKeySpec(hkdfSha256(secret, INFO, 16), "AES")
    private var encSeq = 0L
    private var decSeq = 0L

    companion object {
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1.toByte())
            return mac.doFinal().copyOf(length)
        }
    }

    private fun iv(seq: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val seq = encSeq++
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(seq)))
        return c.doFinal(plain)
    }

    fun decrypt(cipherText: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val seq = decSeq++
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(seq)))
        return c.doFinal(cipherText)
    }
}

/** Client side of the Android 11+ "Pair device with pairing code" protocol. */
class AdbPairingClient(private val keys: AdbKeyManager) {

    private companion object {
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val EXPORT_LABEL = "adb-label\u0000"
        val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                doPair(host, port, pairingCode.trim())
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(
                    if (e is AdbException) e
                    else AdbException(e.message ?: e.javaClass.simpleName, e)
                )
            }
        }

    private fun doPair(host: String, port: Int, code: String) {
        require(code.isNotEmpty()) { "Pairing code is empty" }
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), 10_000)
        raw.soTimeout = 15_000

        val ssl = keys.createSslContext().socketFactory
            .createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.useClientMode = true
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.startHandshake()

            val keyMaterial = Conscrypt.exportKeyingMaterial(ssl, EXPORT_LABEL, null, 64)
            val password = code.toByteArray(Charsets.US_ASCII) + keyMaterial

            val input = DataInputStream(ssl.inputStream)
            val output = ssl.outputStream

            val spake = Spake2(true, CLIENT_NAME, SERVER_NAME, password)
            writePacket(output, TYPE_SPAKE2_MSG, spake.message())

            val (type1, theirMsg) = readPacket(input)
            if (type1 != TYPE_SPAKE2_MSG || theirMsg.size != 32) {
                throw AdbException("Unexpected SPAKE2 reply from device")
            }
            val secret = spake.processMessage(theirMsg)
                ?: throw AdbException("Invalid SPAKE2 message from device")
            val cipher = PairingCipher(secret)

            val peerInfo = ByteArray(PEER_INFO_SIZE)
            peerInfo[0] = 0 // ADB_RSA_PUB_KEY
            val pub = keys.adbPublicKeyBytes()
            System.arraycopy(pub, 0, peerInfo, 1, minOf(pub.size, PEER_INFO_SIZE - 1))
            writePacket(output, TYPE_PEER_INFO, cipher.encrypt(peerInfo))

            val (type2, encrypted) = readPacket(input)
            if (type2 != TYPE_PEER_INFO) throw AdbException("Unexpected pairing reply from device")
            try {
                cipher.decrypt(encrypted)
            } catch (e: AEADBadTagException) {
                throw AdbException("Pairing failed: wrong pairing code", e)
            }
        } catch (e: IOException) {
            throw AdbException(
                "Pairing connection closed by the device (wrong or expired pairing code, or the pairing dialog was closed)",
                e
            )
        } finally {
            try { ssl.close() } catch (_: Exception) {}
            try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun writePacket(out: java.io.OutputStream, type: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN)
        bb.put(1.toByte())
        bb.put(type.toByte())
        bb.putInt(payload.size)
        bb.put(payload)
        out.write(bb.array())
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (version != 1) throw AdbException("Unsupported pairing protocol version $version")
        if (length < 0 || length > MAX_PAYLOAD) throw AdbException("Invalid pairing payload size $length")
        val data = ByteArray(length)
        input.readFully(data)
        return type to data
    }
}
