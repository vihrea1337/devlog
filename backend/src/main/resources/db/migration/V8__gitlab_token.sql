-- V8 — персональный токен GitLab, аналог V5 (github_integrations) для второго провайдера
-- импорта коммитов. В отличие от GitHub, у GitLab Events API нет анонимного/публичного
-- режима — токен здесь не опционален, но структура таблицы та же: изолированный секрет,
-- зашифрованный тем же TokenCipher (ключ из JWT_SECRET), "привязать/отвязать" — DELETE/UPSERT
-- строки, а не флаг в users.
CREATE TABLE IF NOT EXISTS gitlab_integrations (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    encrypted_token TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
