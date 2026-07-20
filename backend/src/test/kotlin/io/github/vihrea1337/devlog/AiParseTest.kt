package io.github.vihrea1337.devlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Юнит-тесты разбора ответа модели (без сети и без ключа). Проверяем, что вытаскиваем
 * JSON даже если модель обернула его в ```-блок или добавила текст вокруг.
 */
class AiParseTest {

    @Test
    fun `парсит JSON даже в fenced-блоке с текстом вокруг`() {
        val content = """
            Вот результат:
            ```json
            {"summary":"чинил виджет","steps":["нашёл баг","поправил updateAll"],
             "decisions":["звать updateAll при смене города"],"problems":["виджет не обновлялся"],
             "outcome":"починено","tags":["android","glance"]}
            ```
        """.trimIndent()

        val dto = AiProcessor.parseStructured(content)

        assertNotNull(dto)
        assertEquals("чинил виджет", dto!!.summary)
        assertEquals(2, dto.steps.size)
        assertEquals(listOf("android", "glance"), dto.tags)
    }

    @Test
    fun `неполный JSON заполняется значениями по умолчанию`() {
        val dto = AiProcessor.parseStructured("""{"summary":"только суть"}""")
        assertNotNull(dto)
        assertEquals("только суть", dto!!.summary)
        assertEquals(emptyList(), dto.steps)
        assertEquals("", dto.outcome)
    }

    @Test
    fun `текст без JSON даёт null`() {
        assertNull(AiProcessor.parseStructured("извините, не смог разобрать"))
    }
}
