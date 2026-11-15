package com.example.usbdisplay.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * RSA-ключ ТВ для авторизации в ADB. Ключ создаётся один раз и хранится в памяти приложения,
 * поэтому галочка «Всегда разрешать» на телефоне срабатывает и при следующих подключениях.
 */
class AdbKey(private val keyPair: KeyPair) {

    /** ADB подписывает 20-байтный токен как готовый SHA-1 хэш: PKCS#1 v1.5 + DigestInfo(SHA-1). */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(keyPair.private)
        sig.update(SHA1_DIGEST_INFO_PREFIX)
        sig.update(token)
        return sig.sign()
    }

    /** Публичный ключ в формате ADB (структура RSAPublicKey из mincrypt), base64 + " имя" + нулевой байт. */
    fun publicKeyPayload(name: String): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + KEY_BITS / 8 + KEY_BITS / 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv)
        buf.put(leBytes(n, KEY_BITS / 8))
        buf.put(leBytes(rr, KEY_BITS / 8))
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encode(buf.array(), Base64.NO_WRAP)
        val tail = " $name".toByteArray(Charsets.UTF_8)
        val out = ByteArray(b64.size + tail.size + 1)
        System.arraycopy(b64, 0, out, 0, b64.size)
        System.arraycopy(tail, 0, out, b64.size, tail.size)
        out[out.size - 1] = 0
        return out
    }

    companion object {
        private const val KEY_BITS = 2048

        // DER-префикс DigestInfo для SHA-1
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        private fun leBytes(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(size)
            for (i in 0 until minOf(be.size, size)) out[i] = be[be.size - 1 - i]
            return out
        }

        fun loadOrCreate(context: Context): AdbKey {
            val priv = File(context.filesDir, "adbkey.pk8")
            val pub = File(context.filesDir, "adbkey.x509")
            val kf = KeyFactory.getInstance("RSA")
            if (priv.exists() && pub.exists()) {
                try {
                    val pair = KeyPair(
                        kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                        kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
                    )
                    return AdbKey(pair)
                } catch (e: Exception) {
                    // повреждённый файл — создаём ключ заново
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(KEY_BITS)
            val pair = gen.generateKeyPair()
            priv.writeBytes(pair.private.encoded)
            pub.writeBytes(pair.public.encoded)
            return AdbKey(pair)
        }
    }
}
