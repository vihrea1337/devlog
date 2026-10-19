-- V9 — токены для скриптов: долгий ключ, который умеет ТОЛЬКО импорт (файлы дня, текст).
--
-- Зачем: автоимпорт файлов дня со своего ПК (планировщик или хук) не должен входить паролем
-- и держать 30-дневный JWT, который открывает весь аккаунт. Токен скрипта ничего не читает и
-- не удаляет — только кладёт записи; его можно отозвать, не меняя пароль.
--
-- Сам токен НЕ храним — только его SHA-256 (как пароль, но без медленного хеша: токен — это
-- 32 случайных байта, перебирать его по хешу бессмысленно). Показывается один раз при выдаче.
CREATE TABLE IF NOT EXISTS import_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- "name" — служебное слово SQL, Exposed пишет его в кавычках (как в V1 у projects).
    "name" VARCHAR(60) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS import_tokens_token_hash_unique ON import_tokens (token_hash);
CREATE INDEX IF NOT EXISTS import_tokens_user_id ON import_tokens (user_id);
