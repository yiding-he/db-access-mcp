package com.hyd.dbmcp.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigCipherTest {

    private val password = "correct horse battery staple"

    @Test
    fun `加解密往返一致`() {
        val plaintext = """{"connections":[{"name":"a"}]}""".toByteArray()
        val sealed = ConfigCipher.seal(plaintext, password)

        val opened = ConfigCipher.open(sealed.envelope, password)

        assertArrayEquals(plaintext, opened.plaintext)
    }

    @Test
    fun `口令错误被 GCM 校验发现`() {
        val sealed = ConfigCipher.seal("payload".toByteArray(), password)

        assertThrows<WrongConfigPasswordException> { ConfigCipher.open(sealed.envelope, "$password!") }
    }

    @Test
    fun `密文被篡改时认证失败`() {
        // GCM 无法区分「口令错」与「数据被改」，两者都表现为 tag 不匹配
        val sealed = ConfigCipher.seal("payload".toByteArray(), password)
        val tampered = sealed.envelope.replaceFirst("\"ct\":\"", "\"ct\":\"AAAA")

        assertThrows<WrongConfigPasswordException> { ConfigCipher.open(tampered, password) }
    }

    @Test
    fun `字段不是合法 Base64 报配置文件损坏`() {
        val sealed = ConfigCipher.seal("payload".toByteArray(), password)
        val broken = sealed.envelope.replaceFirst("\"ct\":\"", "\"ct\":\"@@@")

        assertThrows<ConfigEnvelopeException> { ConfigCipher.open(broken, password) }
    }

    @Test
    fun `信封不是合法 JSON 报配置文件损坏`() {
        assertThrows<ConfigEnvelopeException> { ConfigCipher.open("not json at all", password) }
    }

    @Test
    fun `每次加密都换 salt 与 iv`() {
        val plaintext = "same input".toByteArray()

        val first = ConfigCipher.seal(plaintext, password)
        val second = ConfigCipher.seal(plaintext, password)

        assertNotEquals(first.envelope, second.envelope)
        assertArrayEquals(plaintext, ConfigCipher.open(second.envelope, password).plaintext)
    }

    @Test
    fun `用缓存密钥重写后仍可用同一口令解开`() {
        // 这里盯住一个真实坑：reseal 只换 iv，salt 与迭代次数必须沿用，否则下次解锁对不上密钥
        val sealed = ConfigCipher.seal("v1".toByteArray(), password)

        val resealed = ConfigCipher.reseal("v2".toByteArray(), sealed.handle)

        assertArrayEquals("v2".toByteArray(), ConfigCipher.open(resealed, password).plaintext)
        assertNotEquals(sealed.envelope, resealed)
    }

    @Test
    fun `信封头部记录算法与迭代次数`() {
        val envelope = ConfigCipher.seal("x".toByteArray(), password).envelope

        assertTrue(envelope.contains(ConfigCipher.KDF_NAME), envelope)
        assertTrue(envelope.contains("\"iter\":${ConfigCipher.ITERATIONS}"), envelope)
        assertTrue(envelope.contains("\"v\":1"), envelope)
    }
}
