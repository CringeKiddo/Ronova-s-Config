package com.ronova.config.adb

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Owns the RSA-2048 identity that adbd trusts after the one-time pairing.
 * The same key backs (a) the X.509 certificate used for TLS and (b) the 524-byte
 * Android RSAPublicKey blob that is registered with adbd during pairing.
 */
class AdbKeyManager(context: Context) {

    companion object {
        private const val ALIAS = "ronova-adb"
        private const val KEY_COMMENT = " ronova@config\u0000"
        private val conscryptProvider: Provider by lazy { Conscrypt.newProvider() }
    }

    private class KeyMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val publicKey: RSAPublicKey
    )

    private val dir = File(context.applicationContext.filesDir, "adb").apply { mkdirs() }
    private val keyFile = File(dir, "adbkey.pk8")
    private val certFile = File(dir, "adbkey.crt")
    private val lock = Any()

    @Volatile
    private var material: KeyMaterial? = null

    val privateKey: PrivateKey get() = ensure().privateKey
    val certificate: X509Certificate get() = ensure().certificate
    val publicKey: RSAPublicKey get() = ensure().publicKey

    private fun ensure(): KeyMaterial =
        material ?: synchronized(lock) {
            material ?: loadOrCreate().also { material = it }
        }

    private fun loadOrCreate(): KeyMaterial {
        if (keyFile.exists() && certFile.exists()) {
            try {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return KeyMaterial(priv, cert, cert.publicKey as RSAPublicKey)
            } catch (_: Exception) {
                keyFile.delete()
                certFile.delete()
            }
        }
        return generate()
    }

    private fun generate(): KeyMaterial {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val name = X500Name("CN=ronova@config")
        val now = System.currentTimeMillis()
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            Date(now - 86_400_000L),
            Date(now + 3_650L * 86_400_000L),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
        return KeyMaterial(kp.private, cert, kp.public as RSAPublicKey)
    }

    /** Wipes the identity; the next access generates a new key (device must be re-paired). */
    fun deleteKeys() {
        synchronized(lock) {
            material = null
            keyFile.delete()
            certFile.delete()
        }
    }

    /** Android's 524-byte RSAPublicKey struct (see libcrypto_utils/android_pubkey.c). */
    fun androidPublicKeyStruct(): ByteArray {
        val n = publicKey.modulus
        val e = publicKey.publicExponent
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv.toInt())
        buf.put(toLittleEndian(n, 256))
        buf.put(toLittleEndian(rr, 256))
        buf.putInt(e.toInt())
        return buf.array()
    }

    /** Base64(524-byte struct) + " ronova@config\0", exactly what adbd stores in adb_keys. */
    fun adbPublicKeyBytes(): ByteArray {
        val b64 = Base64.encode(androidPublicKeyStruct(), Base64.NO_WRAP)
        return b64 + KEY_COMMENT.toByteArray(Charsets.US_ASCII)
    }

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        val count = minOf(be.size, size)
        for (i in 0 until count) {
            out[i] = be[be.size - 1 - i]
        }
        return out
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            arrayOf(ALIAS)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String? = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            arrayOf(certificate)

        override fun getPrivateKey(alias: String?): PrivateKey? = privateKey
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * TLS 1.3 context backed by the bundled Conscrypt (needed for exportKeyingMaterial).
     * Server certificates are not validated: adbd uses self-signed certs and authenticates
     * the peer through the pairing secret / stored public key instead.
     */
    fun createSslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3", conscryptProvider)
        ctx.init(
            arrayOf<KeyManager>(keyManager),
            arrayOf<TrustManager>(trustAll),
            SecureRandom()
        )
        return ctx
    }
}
