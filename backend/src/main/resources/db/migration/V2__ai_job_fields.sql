-- V2 — поля для устойчивой обработки ИИ.
--
-- Раньше обработка жила только в оперативной памяти: сервер запускал корутину и забывал
-- про неё. Перезапуск/падение/редеплой — и запись навсегда зависала в статусе queued или
-- processing, подобрать её было некому. Теперь очередь хранится в самой базе: воркер
-- периодически спрашивает «что не обработано?» и берёт запись в работу.
--
-- ai_attempts    — сколько раз пытались обработать (защита от вечного цикла на битой записи);
-- ai_error       — текст последней ошибки (почему статус failed: лимит, сеть, плохой JSON);
-- ai_started_at  — когда запись взяли в работу. Если она висит в processing дольше таймаута,
--                  значит обработка оборвалась и запись можно забрать снова.

ALTER TABLE entries ADD COLUMN IF NOT EXISTS ai_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE entries ADD COLUMN IF NOT EXISTS ai_error TEXT NULL;
ALTER TABLE entries ADD COLUMN IF NOT EXISTS ai_started_at TIMESTAMP NULL;

-- Индекс под запрос воркера «дай записи, ждущие обработки».
CREATE INDEX IF NOT EXISTS entries_status ON entries (status);
