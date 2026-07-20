package io.github.vihrea1337.devlog

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционный тест сервера. testApplication поднимает Ktor в памяти (без реального
 * порта и без базы) и позволяет слать ему запросы. Проверяем /health.
 */
class ApplicationTest {

    @Test
    fun `health отвечает ok`() = testApplication {
        application { module() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
