-- V5 — персональный токен GitHub для импорта ПРИВАТНЫХ репозиториев (по одному на пользователя).
--
-- Отдельная таблица, а не колонка в users — как и предполагалось в комментарии V4:
-- изолирует секрет от общей таблицы аккаунтов, "привязать/отвязать" — самостоятельная
-- операция (DELETE строки, а не UPDATE колонки). Токен хранится ЗАШИФРОВАННЫМ
-- (AES-GCM, ключ — из JWT_SECRET, см. TokenCipher.kt), не в открытом виде: он даёт
-- доступ к приватному коду пользователя на GitHub.
CREATE TABLE IF NOT EXISTS github_integrations (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    encrypted_token TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
