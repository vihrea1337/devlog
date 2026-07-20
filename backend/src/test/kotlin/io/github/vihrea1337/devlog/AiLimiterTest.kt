package io.github.vihrea1337.devlog

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Юнит-тесты суточного лимита ИИ (без сети и БД). */
class AiLimiterTest {

    @BeforeTest
    fun clean() = AiLimiter.reset()

    @Test
    fun `лимит исчерпывается после N операций`() {
        val user = UUID.randomUUID()
        assertTrue(AiLimiter.tryConsume(user, limit = 2))
        assertTrue(AiLimiter.tryConsume(user, limit = 2))
        assertFalse(AiLimiter.tryConsume(user, limit = 2))
    }

    @Test
    fun `у разных пользователей счётчики независимы`() {
        assertTrue(AiLimiter.tryConsume(UUID.randomUUID(), limit = 1))
        assertTrue(AiLimiter.tryConsume(UUID.randomUUID(), limit = 1))
    }
}
