package io.github.vihrea1337.devlog

import java.security.MessageDigest
import java.util.UUID

/**
 * Общая логика для импорта, который можно ПОВТОРЯТЬ: файл дня дописывается в течение дня,
 * за день появляются новые коммиты. Импортировать второй раз — нормальный сценарий, и он
 * не должен ни плодить дубли, ни молча затирать то, что пользователь поправил руками.
 *
 * Как отличить «запись не трогали» от «запись правили в DevLog»: при импорте в `source_ref`
 * кладём отпечаток (короткий SHA-256) текста, который импорт САМ записал. При следующем
 * импорте сравниваем отпечаток с текущим текстом записи:
 * - совпал → запись никто не правил, её можно смело заменить новой версией источника;
 * - не совпал → пользователь редактировал запись — НЕ перезаписываем, сообщаем об этом.
 *
 * Отдельная колонка под отпечаток не нужна: `source_ref` («откуда запись») для этого и есть.
 */
object ImportedEntries {

    /** Что сделал импорт с одной записью. */
    enum class Outcome { CREATED, UPDATED, UNCHANGED, EDITED, DELETED }

    /**
     * Как поступить со статусом после создания/замены: [structured] не null — запись уже
     * разобрана кодом (ИИ не нужен); иначе — в очередь ИИ или черновик по настройке проекта.
     */
    data class Prepared(
        val id: UUID,
        val input: ValidEntryInput,
        /** Короткая метка источника: «day-file:2026-10-19.md», «github:owner/repo». */
        val source: String,
        val structured: StructuredDto? = null,
        val structuredBy: String = PARSER_MODEL,
    )

    /** Подпись «кто разобрал запись» для разбора кодом — видна там же, где имя модели ИИ. */
    const val PARSER_MODEL = "код: разделы записи"

    /** Короткий отпечаток текста: не секрет, а метка версии — 16 hex-символов хватает. */
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Значение для `source_ref`: «источник#отпечаток». Колонка — 500 символов, источник режем. */
    fun sourceRef(source: String, text: String): String = source.take(480) + "#" + fingerprint(text)

    /** Совпадает ли отпечаток в [sourceRef] с текстом — т.е. запись не правили руками. */
    fun isUntouched(sourceRef: String?, currentText: String): Boolean =
        sourceRef?.substringAfterLast('#', missingDelimiterValue = "") == fingerprint(currentText)

    /**
     * Создать запись, обновить её или оставить как есть — см. описание объекта.
     * [overwrite] = true — пользователь сам попросил заменить источником и правленую руками,
     * и удалённую запись (удалённая при этом восстанавливается).
     */
    fun upsert(userId: UUID, p: Prepared, overwrite: Boolean = false): Outcome {
        val text = p.input.rawText
        val ref = sourceRef(p.source, text)
        val existing = EntryRepository.importState(userId, p.id)
        val status = initialStatus(userId, p)

        val outcome = when {
            existing == null -> {
                EntryRepository.create(userId, p.input.copy(id = p.id, sourceRef = ref, initialStatus = status))
                Outcome.CREATED
            }
            // Пользователь удалил запись — значит, она ему не нужна; импорт её не воскрешает.
            existing.deleted && !overwrite -> return Outcome.DELETED
            !existing.deleted && existing.rawText == text && existing.projectId == p.input.projectId ->
                return Outcome.UNCHANGED
            !existing.deleted && !overwrite && !isUntouched(existing.sourceRef, existing.rawText) ->
                return Outcome.EDITED
            else -> {
                EntryRepository.replaceImported(userId, p.id, text, ref, p.input.projectId, status)
                Outcome.UPDATED
            }
        }
        // Разбор кодом сохраняем сразу — ИИ этой записи не понадобится вовсе.
        if (p.structured != null) {
            EntryStructuredRepository.save(p.id, p.structured, p.structuredBy)
            EntryRepository.markAiDone(p.id)
        }
        return outcome
    }

    /**
     * Статус до сохранения разбора: для разобранной кодом — "draft" (а не "queued"), чтобы
     * воркер ИИ не успел схватить запись в промежутке между вставкой и сохранением разбора.
     */
    private fun initialStatus(userId: UUID, p: Prepared): String = when {
        p.structured != null -> "draft"
        ProjectRepository.isAiEnabled(userId, p.input.projectId) -> "queued"
        else -> "draft"
    }
}
