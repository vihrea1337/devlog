-- V1 — начальная схема DevLog.
--
-- Это «слепок» той схемы, которую до перехода на миграции создавал Exposed
-- (SchemaUtils.create) прямо из описания таблиц в Tables.kt.
--
-- ВАЖНО про боевую базу: там таблицы уже существуют, поэтому Flyway настроен с
-- baselineOnMigrate — он увидит непустую схему, отметит V1 как «уже применённую»
-- (не выполняя её) и накатит только V2 и дальше. На пустой базе (локальная разработка,
-- тесты, новый сервер) V1 выполнится и создаст всё с нуля.
--
-- Типы соответствуют тому, что Exposed генерирует для PostgreSQL:
-- timestamp() → TIMESTAMP (без зоны), date() → DATE, uuid() → UUID.

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS users_email_unique ON users (email);

CREATE TABLE IF NOT EXISTS projects (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- "name" и "source" (ниже) — служебные слова SQL, Exposed пишет их в кавычках.
    -- Пишем так же: иначе H2 создаст колонку в верхнем регистре и схемы разойдутся.
    "name"     VARCHAR(120) NOT NULL,
    color      VARCHAR(9)   NULL,
    ai_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS entries (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id     UUID        NULL REFERENCES projects (id) ON DELETE SET NULL,
    occurred_on    DATE        NOT NULL,
    raw_text       TEXT        NOT NULL,
    "source"       VARCHAR(20) NOT NULL DEFAULT 'manual',
    source_ref     VARCHAR(500) NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'queued',
    time_spent_min INTEGER     NULL,
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL
);
-- Индекс под ленту и сборку отчёта: записи пользователя за период.
CREATE INDEX IF NOT EXISTS entries_user_id_occurred_on ON entries (user_id, occurred_on);

CREATE TABLE IF NOT EXISTS entry_structured (
    entry_id     UUID PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    summary      TEXT        NOT NULL,
    steps        TEXT        NOT NULL,
    decisions    TEXT        NOT NULL,
    problems     TEXT        NOT NULL,
    outcome      TEXT        NOT NULL,
    tags         TEXT        NOT NULL,
    ai_model     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS reports (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id   UUID         NULL REFERENCES projects (id) ON DELETE SET NULL,
    title        VARCHAR(200) NOT NULL,
    period_start DATE         NOT NULL,
    period_end   DATE         NOT NULL,
    format       VARCHAR(20)  NOT NULL DEFAULT 'markdown',
    content_md   TEXT         NOT NULL,
    share_token  VARCHAR(64)  NULL,
    created_at   TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS reports_share_token_unique ON reports (share_token);
