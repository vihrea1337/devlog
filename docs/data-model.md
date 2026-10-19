# DevLog — модель данных (схема БД)

PostgreSQL. Идентификаторы — UUID (глобально уникальны, удобны при синхронизации
нескольких клиентов). Время — `timestamp`, даты работы — `date`.

## Как меняется схема (миграции)

Схему ведёт **Flyway**: SQL-файлы в `backend/src/main/resources/db/migration/` вида
`V2__что_делаем.sql` применяются по возрастанию версии при старте сервера, а список
уже применённых версий Flyway хранит в самой базе (таблица `flyway_schema_history`).

**Правило: колонку добавляем в двух местах сразу** — в `Tables.kt` (чтобы её видел код)
и новой миграцией `V…__.sql` (чтобы она появилась в существующей базе). Раньше схему
создавал `SchemaUtils.create`, но он умеет только создавать отсутствующие таблицы целиком
и молча проходит мимо таблицы, где не хватает колонки — на боевой базе изменение
не применилось бы никогда.

За тем, что оба места совпадают, следит тест `SchemaMigrationTest`: он накатывает миграции
на пустую H2 и проверяет, что Exposed не находит недостающих таблиц и колонок.
Боевая база создавалась ещё старым способом, поэтому Flyway настроен с `baselineOnMigrate`:
непустую схему без истории он помечает как версию 1 и накатывает только V2 и дальше.
Массивы (шаги/решения/проблемы/теги) храним JSON-строкой в `text`-колонках — просто и переносимо (одинаково на Postgres и на H2 в тестах); при желании позже можно перевести на `jsonb`.

## users — пользователи
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| email | varchar(255) UNIQUE | логин |
| password_hash | varchar(255) | bcrypt/argon2, НЕ открытый пароль |
| display_name | varchar(100) | имя для интерфейса |
| created_at | timestamptz | |

_Позже (Фаза 4): `plan`, `ai_quota_used` для тарифов и лимитов ИИ._

## projects — проекты (группировка записей)
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users | владелец |
| name | varchar(120) | |
| color | varchar(9) | hex-цвет для UI |
| ai_enabled | boolean, default true | выключатель ИИ (приватность) |
| archived | boolean, default false | |
| created_at | timestamptz | |
| github_export_repo | varchar(200), NULL | "owner/repo" для экспорта отчётов; NULL — экспорт выключен (V7) |
| github_export_path | varchar(300), NULL | путь до changelog-файла; NULL при непустом repo = docs/devlog.md по умолчанию (V7) |

## entries — записи (сырьё за день)
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users | дублируется для быстрых per-user выборок и безопасности |
| project_id | uuid FK→projects, NULL | запись может быть вне проекта |
| occurred_on | date | за какой день работа (не дата создания!) |
| raw_text | text | сырой текст как ввели/импортировали |
| source | varchar(20) | `manual` \| `github` \| `gitlab` \| `text-import` \| `day-file` |
| source_ref | varchar(500), NULL | у импорта — «источник#отпечаток текста» (`day-file:2026-10-19.md#…`): по нему повторный импорт отличает нетронутую запись от правленой руками (см. `ImportedEntries.kt`) |
| status | varchar(20) | `draft` \| `queued` \| `processing` \| `structured` \| `failed` |
| time_spent_min | int, NULL | потрачено минут (опционально) |
| created_at | timestamp | |
| updated_at | timestamp | для показа человеку (когда правили) |
| sync_seq | bigint, из последовательности `entries_sync_seq` | курсор `/api/entries/changes` — строго монотонный, в отличие от updated_at (см. architecture.md) |
| ai_attempts | int, default 0 | сколько раз пытались обработать (стоп после 3) |
| ai_error | text, NULL | почему не вышло — показывается пользователю |
| ai_started_at | timestamp, NULL | когда взяли в работу; ловит зависшие в `processing` |
| deleted_at | timestamp, NULL | мягкое удаление: строка остаётся «надгробием» |

### Про удаление
`DELETE /api/entries/{id}` не стирает строку: проставляется `deleted_at`, статус меняется
на `deleted`, а **содержимое стирается сразу** (`raw_text` очищается, строка в
`entry_structured` удаляется). Причина: без надгробия второе устройство, которое было
офлайн, никогда не узнает об удалении — запись «воскреснет» при следующей синхронизации.
Хранить при этом текст удалённой записи незачем: для синхронизации хватает id и времени.
_На будущее: надгробия старше нескольких месяцев можно вычищать по расписанию._

_Последние три колонки — очередь обработки ИИ (`AiWorker`), см. `architecture.md`._

## entry_structured — что ИИ извлёк (1:1 к entries)
| колонка | тип | заметки |
|---|---|---|
| entry_id | uuid PK, FK→entries | тот же id, связь один-к-одному |
| summary | text | суть одной фразой |
| steps | text | JSON-массив строк-шагов |
| decisions | text | JSON-массив принятых решений |
| problems | text | JSON-массив решённых проблем |
| outcome | text | итог/результат |
| tags | text | JSON-массив тегов |
| ai_model | varchar(60) | какая модель обработала |
| processed_at | timestamptz | |

## reports — отчёты за период
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users | |
| project_id | uuid FK→projects, NULL | NULL = по всем проектам |
| title | varchar(200) | |
| period_start | date | |
| period_end | date | |
| format | varchar(20) | `markdown` \| `pdf` \| `html` |
| content_md | text | сгенерированный ИИ Markdown |
| share_token | varchar(64) UNIQUE, NULL | для публичной ссылки работодателю |
| created_at | timestamptz | |
| auto_kind | varchar(10), NULL | V10: `day` \| `week` \| `month` — собран автоматикой; NULL — по кнопке |

## github_integrations — персональный токен GitHub (Фаза 4, реализовано)
| колонка | тип | заметки |
|---|---|---|
| user_id | uuid PK, FK→users | один токен на пользователя (по одному, не список) |
| encrypted_token | text | AES-256-GCM, ключ — SHA-256 от JWT_SECRET (см. TokenCipher.kt) |
| updated_at | timestamp | когда привязали/заменили |

Это реализация закладки из более раннего плана (тогда таблица называлась `integrations`
и была задумана как универсальная — `type` \| `credentials_ref` — со ссылкой на секрет,
хранящийся ГДЕ-ТО ЕЩЁ). На практике для GitHub-токена завели отдельную специализированную
таблицу и хранят сам секрет ЗАШИФРОВАННЫМ в этой же БД, а не ссылку на внешнее хранилище:
секретов-менеджера в проекте нет и заводить его ради одного личного токена непропорционально
для портфолио-проекта такого масштаба.

## gitlab_integrations — персональный токен GitLab (Фаза 4, реализовано, V8)
| колонка | тип | заметки |
|---|---|---|
| user_id | uuid PK, FK→users | один токен на пользователя |
| encrypted_token | text | тот же TokenCipher, что у github_integrations |
| updated_at | timestamp | когда привязали/заменили |

**Второй провайдер появился (GitLab) — и обобщённую схему по-прежнему НЕ завели, сознательно.**
Причина: `github_integrations`/`gitlab_integrations` — это только хранилище токена, а
реальная разница между провайдерами (у GitHub токен опционален и есть отдельный `githubLogin`
в `users`, у GitLab токен обязателен и логина нет — см. GitlabImport.kt) всё равно живёт
в отдельных `GithubImporter`/`GitlabImporter` и отдельных ручках `/api/import/github`,
`/api/import/gitlab`. Общая таблица `integrations(user_id, provider, encrypted_token)` убрала
бы дублирование двух ОДИНАКОВЫХ таблиц из четырёх строк каждая, но не убрала бы дублирование
там, где оно реально есть (в импортёрах) — а взамен потребовала бы миграции данных из уже
существующей `github_integrations` и добавила бы дискриминатор `provider` во все запросы.
Две специализированные таблицы дешевле для двух провайдеров; вернуться к обобщённой схеме
имеет смысл при третьем провайдере, если он тоже будет "просто токен, без своих полей".

## import_tokens — токены для скриптов (V9, 2026-10-19)
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users, ON DELETE CASCADE | владелец |
| name | varchar(60) | «Домашний ПК» — чтобы понять, какой отзывать |
| token_hash | varchar(64) UNIQUE | SHA-256 токена; сам токен не храним, показываем один раз |
| created_at | timestamp | |
| last_used_at | timestamp, NULL | обновляется не чаще раза в минуту |

Токен (`dli_…`, 32 случайных байта) пускает только в `/api/import/day-files`,
`/api/import/text`, `/api/import/commits` и `/api/import/notices` — для автоимпорта с ПК (`tools/push-day-files.mjs`, см. `docs/auto-import.md`).

## report_automation — автоматика отчётов (V10, 2026-10-19)
Одна строка на пользователя: что сервер делает сам (`Automation.kt`).

| колонка | тип | заметки |
|---|---|---|
| user_id | uuid PK, FK→users, ON DELETE CASCADE | |
| project_id | uuid FK→projects, NULL, ON DELETE SET NULL | куда коммиты и по чему отчёты; NULL — все записи |
| github_repo | varchar(140), NULL | «владелец/имя»: коммиты раз в сутки (нужен токен GitHub) |
| daily_report / weekly_report / monthly_report | boolean, default false | какие отчёты собирать самому |
| time_zone | varchar(40), default `Europe/Moscow` | «сегодня», последний день месяца, день коммита |
| last_commit_import_on | date, NULL | когда забирали коммиты |
| last_commit_import_result | varchar(300), NULL | итог для веба |
| last_daily_on | date, NULL | за какой день собран последний дневной |
| last_weekly_on | date, NULL | понедельник последней собранной недели |
| last_monthly | varchar(7), NULL | `2026-10` |
| last_report_result | varchar(300), NULL | итог последней сборки |
| updated_at | timestamp | |

## Связи
- `users 1—∞ projects`, `users 1—∞ entries`, `users 1—∞ reports`, `users 1—1 github_integrations`,
  `users 1—1 gitlab_integrations`, `users 1—∞ import_tokens`, `users 1—1 report_automation`
- `projects 1—∞ entries` (или запись без проекта)
- `entries 1—1 entry_structured`

## Заметки по реализации
- Все выборки фильтруются по `user_id` из JWT — изоляция пользователей.
- Индексы: `entries(user_id, occurred_on)` для ленты и сборки отчёта за период.
- `entry_structured` отдельной таблицей (а не колонками в entries), чтобы сырьё и результат
  ИИ жили раздельно и результат можно было перегенерировать/очистить.
