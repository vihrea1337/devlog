# DevLog — модель данных (схема БД)

PostgreSQL. Идентификаторы — UUID (глобально уникальны, удобны при синхронизации
нескольких клиентов). Время — `timestamptz` (с зоной), даты работы — `date`.
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

## entries — записи (сырьё за день)
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users | дублируется для быстрых per-user выборок и безопасности |
| project_id | uuid FK→projects, NULL | запись может быть вне проекта |
| occurred_on | date | за какой день работа (не дата создания!) |
| raw_text | text | сырой текст как ввели/импортировали |
| source | varchar(20) | `manual` \| `github` \| `claude` \| `import` |
| source_ref | varchar(500), NULL | ссылка на коммит/URL источника |
| status | varchar(20) | `draft` \| `queued` \| `processing` \| `structured` \| `failed` |
| time_spent_min | int, NULL | потрачено минут (опционально) |
| created_at | timestamptz | |
| updated_at | timestamptz | для синхронизации |

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

## integrations — внешние источники (закладка под Фазу 4)
| колонка | тип | заметки |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK→users | |
| type | varchar(20) | `github` \| `gitlab` |
| credentials_ref | varchar(200) | ССЫЛКА на секрет, не сам токен в открытом виде |
| config | jsonb | какие репозитории тянуть и т.п. |
| created_at | timestamptz | |

## Связи
- `users 1—∞ projects`, `users 1—∞ entries`, `users 1—∞ reports`, `users 1—∞ integrations`
- `projects 1—∞ entries` (или запись без проекта)
- `entries 1—1 entry_structured`

## Заметки по реализации
- Все выборки фильтруются по `user_id` из JWT — изоляция пользователей.
- Индексы: `entries(user_id, occurred_on)` для ленты и сборки отчёта за период.
- `entry_structured` отдельной таблицей (а не колонками в entries), чтобы сырьё и результат
  ИИ жили раздельно и результат можно было перегенерировать/очистить.
