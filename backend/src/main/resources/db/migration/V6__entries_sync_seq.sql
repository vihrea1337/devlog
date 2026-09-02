-- V6 — монотонный счётчик записи (sync_seq) вместо updated_at как ключа пагинации
-- в /api/entries/changes.
--
-- updated_at (timestamp) оказался ненадёжным ключом курсора: несколько записей могут
-- получить ОДИНАКОВОЕ значение (быстрые вставки подряд, ограниченное разрешение часов/
-- колонки), и при страничной выборке "since X" часть таких записей либо терялась
-- навсегда (простое "> since"), либо — с id как тай-брейком — могла выпасть из ответа,
-- если её случайный UUID оказался "меньше", чем у записи-курсора, хотя по факту она
-- новее. sync_seq решает это раз и навсегда: значение из последовательности БД строго
-- монотонно растёт на каждой вставке и изменении записи, совпадений не бывает в принципе,
-- в отличие от timestamp'а.
CREATE SEQUENCE IF NOT EXISTS entries_sync_seq;
ALTER TABLE entries ADD COLUMN IF NOT EXISTS sync_seq BIGINT;
-- Существующим строкам (если есть, при повторном прогоне на непустой базе) — тоже
-- монотонный порядок, чтобы ни одна не осталась с NULL перед NOT NULL ниже.
UPDATE entries SET sync_seq = nextval('entries_sync_seq') WHERE sync_seq IS NULL;
ALTER TABLE entries ALTER COLUMN sync_seq SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_entries_user_sync_seq ON entries(user_id, sync_seq);
