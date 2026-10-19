-- V10 — автоматика отчётов: что сервер делает сам, без кнопок (см. Automation.kt).
--
-- Одна строка на пользователя:
-- - project_id — проект, куда класть коммиты и по которому собирать отчёты;
-- - github_repo — «владелец/имя»: раз в сутки забирать оттуда коммиты пользователя
--   (нужен привязанный токен GitHub; для чужого приватного репозитория — classic с правом repo);
-- - daily_report / weekly_report / monthly_report — какие «Отчёты работодателю» собирать
--   самому: за вчера (утром), за прошлую неделю (в понедельник), черновик месяца (в его
--   последний день);
-- - time_zone — пояс пользователя: по нему «сегодня», «последний день месяца» и к какому
--   дню относится коммит (иначе ночной коммит по Москве уехал бы во вчера по UTC);
-- - last_* — что и когда уже сделано: не повторяем дважды и показываем итог в вебе.
CREATE TABLE IF NOT EXISTS report_automation (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    project_id UUID NULL REFERENCES projects(id) ON DELETE SET NULL,
    github_repo VARCHAR(140) NULL,
    daily_report BOOLEAN NOT NULL DEFAULT FALSE,
    weekly_report BOOLEAN NOT NULL DEFAULT FALSE,
    monthly_report BOOLEAN NOT NULL DEFAULT FALSE,
    time_zone VARCHAR(40) NOT NULL DEFAULT 'Europe/Moscow',
    last_commit_import_on DATE NULL,
    last_commit_import_result VARCHAR(300) NULL,
    last_daily_on DATE NULL,
    last_weekly_on DATE NULL,
    last_monthly VARCHAR(7) NULL,
    last_report_result VARCHAR(300) NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Какой автоматикой собран отчёт: day | week | month; NULL — собран руками. По этой метке
-- скрипт на ПК узнаёт о новых отчётах (уведомление), а веб подписывает их «собран сам».
ALTER TABLE reports ADD COLUMN IF NOT EXISTS auto_kind VARCHAR(10) NULL;
