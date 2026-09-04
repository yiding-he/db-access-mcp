package com.hyd.dbmcp.crypto

import com.hyd.dbmcp.util.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置文件的加解密：PBKDF2-HMAC-SHA256 派生密钥 + AES-256-GCM，只用 JDK 自带能力
 * （不为 Argon2 引 BouncyCastle：威胁模型是「本机文件被人拿到」，PBKDF2 已足够）。
 *
 * 密码是否正确由 GCM tag 校验天然判定，所以信封里不需要另存密码验证器。
 *
 * 三个入口：
 * - [seal] 首次设密：派生密钥 + 生成信封
 * - [open] 解锁：用口令解密，拿回明文与重写信封需要的东西
 * - [reseal] 配置变更后重写：复用已派生的密钥，只换 IV
 *
 * 重写信封时必须连 salt 与迭代次数一起沿用：密钥是从「口令 + 这个 salt」派生出来的，
 * 只换 salt 会让信封头与实际加密用的密钥对不上，下次解锁就解不开了。
 */
object ConfigCipher {

    /** 信封里记录的 KDF 标识（给人看的名字） */
    const val KDF_NAME = "PBKDF2-HMAC-SHA256"

    /** JCE 里的算法名，与上面的展示名不是同一个字符串 */
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    const val ITERATIONS = 600_000

    private const val SALT_BYTES = 16

    private const val IV_BYTES = 12

    private const val TAG_BITS = 128

    private const val KEY_BITS = 256

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    fun seal(plaintext: ByteArray, password: String): Sealed {
        val salt = randomBytes(SALT_BYTES)
        val handle = CipherHandle(deriveKey(password, salt, ITERATIONS), salt, ITERATIONS)
        return Sealed(envelope(plaintext, handle), handle)
    }

    fun open(envelopeJson: String, password: String): Opened {
        val fields = readEnvelope(envelopeJson)
        val handle = CipherHandle(deriveKey(password, fields.salt, fields.iterations), fields.salt, fields.iterations)
        val plaintext = try {
            decryptWith(handle.key, fields.iv, fields.ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongConfigPasswordException("配置密码不正确，或配置文件已被改动")
        } catch (e: IllegalBlockSizeException) {
            throw ConfigEnvelopeException("配置文件密文长度不合法")
        }
        return Opened(handle, plaintext)
    }

    fun reseal(plaintext: ByteArray, handle: CipherHandle): String = envelope(plaintext, handle)

    private fun envelope(plaintext: ByteArray, handle: CipherHandle): String {
        val iv = randomBytes(IV_BYTES)
        val node = Json.obj()
        node.put("v", VERSION)
        node.put("kdf", KDF_NAME)
        node.put("iter", handle.iterations)
        node.put("salt", encode(handle.salt))
        node.put("iv", encode(iv))
        node.put("ct", encode(encryptWith(handle.key, iv, plaintext)))
        return Json.write(node)
    }

    private fun encryptWith(key: SecretKey, iv: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)

    private fun decryptWith(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext)

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    private fun readEnvelope(envelopeJson: String): EnvelopeFields {
        val node = try {
            Json.parse(envelopeJson)
        } catch (e: RuntimeException) {
            throw ConfigEnvelopeException("配置文件不是合法的 JSON 信封：${e.message}")
        }
        if (node.path("v").asInt(0) != VERSION) {
            throw ConfigEnvelopeException("不支持的配置文件版本：${node.path("v").asString("?")}")
        }
        val kdf = node.path("kdf").asString("")
        if (kdf != KDF_NAME) {
            throw ConfigEnvelopeException("不支持的密钥派生算法：$kdf")
        }
        val fields = try {
            EnvelopeFields(
                salt = decode(node.path("salt").asString("")),
                iv = decode(node.path("iv").asString("")),
                ciphertext = decode(node.path("ct").asString("")),
                iterations = node.path("iter").asInt(ITERATIONS),
            )
        } catch (e: IllegalArgumentException) {
            throw ConfigEnvelopeException("配置文件字段不是合法的 Base64：${e.message}")
        }
        if (fields.salt.size != SALT_BYTES || fields.iv.size != IV_BYTES || fields.ciphertext.isEmpty()) {
            throw ConfigEnvelopeException("配置文件字段长度不合法")
        }
        return fields
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            val encoded = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray =
        if (text.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(text)

    private const val VERSION = 1

    private class EnvelopeFields(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray, val iterations: Int)
}

/** 加密结果：信封文本 + 重写信封时要用到的派生信息 */
class Sealed(val envelope: String, val handle: CipherHandle)

/** 解密结果：明文 + 重写信封时要用到的派生信息 */
class Opened(val handle: CipherHandle, val plaintext: ByteArray)

/**
 * 解锁后驻留内存的东西：派生出的密钥，以及派生它时用的 salt 与迭代次数。
 *
 * 三者必须一起保存：重写信封时只换 IV，salt 与迭代次数沿用原值，密钥才与信封对得上。
 * 内存里不需要留配置密码本身。
 */
class CipherHandle(val key: SecretKey, val salt: ByteArray, val iterations: Int)

class WrongConfigPasswordException(message: String) : RuntimeException(message)

class ConfigEnvelopeException(message: String) : RuntimeException(message)
