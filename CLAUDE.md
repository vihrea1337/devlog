# DevLog — контекст проекта для Claude

> Рабочий дневник / летопись разработчика. Сырые заметки о проделанной работе →
> ИИ структурирует в отчёт с датами, шагами, решениями и итогами для сдачи работодателю.

## Читать при старте сессии
- `docs/product-concept.md` — что за продукт и зачем
- `docs/architecture.md` — техническая архитектура и контракт API
- `docs/data-model.md` — схема базы данных
- `docs/roadmap.md` — фазы работы с чек-листами (где мы сейчас)

## Формат работы
- **Код пишет Claude** (как в Expenses/Suomeksi), с подробными русскими комментариями «для junior».
  Пользователь тестирует и разбирает готовое.
- Инфраструктурные команды на сервере выполняет **пользователь сам** (у Claude нет доступа к серверу).
- Коммиты **без** трейлера `Co-Authored-By`.
- Пользователь — junior, объяснять как новичку, расшифровывать термины.

## Структура репозитория
- `backend/` — сервер Ktor (самостоятельный Gradle-проект).
- `androidApp/` — Android-приложение (самостоятельный Gradle-проект).
- `shared/` — **общий модуль с DTO**: один контракт API на бэкенд и Android,
  подключается обоим через `includeBuild("../shared")`. Меняешь поле — меняй здесь,
  оба проекта увидят это на компиляции. Тесты: `./gradlew :shared:test` из `backend/`.
- `deploy/server/` — файлы деплоя и runbook, `docs/` — проектные документы.

## Стек (зафиксирован)
- **Бэкенд:** Kotlin + Ktor + PostgreSQL + Exposed + HikariCP.
- **Схема БД:** миграции Flyway (`backend/src/main/resources/db/migration/V…__.sql`).
  Новая колонка = правка `Tables.kt` **и** новый файл миграции; совпадение стережёт
  тест `SchemaMigrationTest`. Подробности — `docs/data-model.md`.
- **ИИ:** Groq (OpenAI-совместимый API, бесплатно), модель `openai/gpt-oss-20b`. Ключ только
  на сервере (`backend/secrets.properties` → `groq.api.key`, в .gitignore), обработка в фоне.
  _(Изначально планировался Claude API — заменён на Groq ради бесплатности на старте.)_
- **Клиенты:** Kotlin Multiplatform + Compose Multiplatform (Android/iOS/Desktop из общего кода).
  Веб на старте — HTML-страница, которую отдаёт сам бэкенд.
- **Авторизация:** JWT, пароли хешируются, изоляция пользователей (multi-tenant) с Фазы 1.
- **Деплой:** Docker на сервере брата `/root/app/lev`, HTTPS через Caddy + DuckDNS
  (порт из диапазона 34000–35000), по образцу Expenses.

## Статус (на 2026-08-12, конец дня)

**Прод:** `https://vihreaschedule.duckdns.org:34444` — сервер брата переустановлен и
развёрнут с нуля (Ubuntu 24.04, Docker, Caddy+DuckDNS). **База пустая**: старые записи
стёрлись вместе с сервером. Сейчас в проде код по коммит `a4462ed` включительно.
Пользователь погасил окружение вечером — Docker и сервер могут быть выключены.

**Готово и работает:** JWT-аккаунты, CRUD записей и проектов, выключатель ИИ по проекту,
фоновая ИИ-структуризация (Groq) очередью в базе, отчёты за период (Markdown + HTML +
публичная ссылка + PDF печатью), календарь активности и серии дней, поиск и теги,
тёмная тема, миграции Flyway, живые обновления по SSE, мягкое удаление и синхронизация,
импорт коммитов с GitHub, Android-приложение (лента + проекты), общий модуль `shared/`.

**Тесты:** 96 в `backend/` + 4 контрактных в `shared/`. CI зелёный.

**НЕ развёрнуто в проде** (написано после последнего деплоя): импорт GitHub (миграция V4),
Android с проектами, общий модуль `shared/`. При следующем деплое накатится V4.

**НЕ проверено вживую:** импорт реальных коммитов; APK на телефоне (ни разу);
вёрстка панели GitHub (скриншот превью снять не успели).

## Что делать дальше (по согласованному плану)
1. Поднять окружение и задеплоить накопленное; проверить импорт коммитов под логином
   `vihrea1337` (приватный `devlog` в публичной активности не виден — только открытые репо).
2. Отчёты в Android-приложении (сейчас только лента и проекты).
3. Офлайн-кэш в Android поверх готового `since`-протокола: в `shared/` переедут
   Ktor Client и SQLDelight.
4. Импорт выгрузок из чатов; приватные репозитории GitHub (нужен токен и хранилище секретов).

## Деплой / редеплой
- Файлы: `deploy/server/` (docker-compose + Caddyfile + Dockerfile'ы + README-runbook).
- Секреты на сервере (не в git): `.env` (DB_PASSWORD, DUCKDNS_TOKEN, DEVLOG_DOMAIN, DEVLOG_PORT),
  `devlog.env` (GROQ_API_KEY, JWT_SECRET). Домен — отдельный DuckDNS `vihreaschedule.duckdns.org`.
- Обновить бэкенд: `gradlew distTar` → `scp backend/build/distributions/devlog-backend-0.1.0.tar`
  в `/root/app/lev/devlog/backend/devlog-backend.tar` → на сервере
  `docker compose up -d --build devlog-backend`. **Именно distTar, а не fat-jar:**
  в fat-jar склеиваются файлы `META-INF/services`, Flyway теряет плагины и МОЛЧА
  пропускает миграции — сервер поднимается с пустой базой.
- Caddy на сервере уже собран; пересобирать его не нужно. Если всё же придётся —
  версии в `caddy/Dockerfile` закреплены намеренно (Caddy 2.10 + duckdns v0.5.0).

## Команды сборки/запуска
- **Сборка/тесты:** `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
  из `backend/`: `./gradlew test` / `distTar` / `run`; тесты общего модуля — `./gradlew :shared:test`.
- **Локальный запуск вживую:** поднять Docker Desktop → `docker compose -f backend/docker-compose.yml up -d`
  (Postgres `devlog-pg` на порту 5433) → `./gradlew -p backend run` → http://localhost:8080.
- **Android:** из `androidApp/` c `JAVA_HOME=...jbr`, `.\gradlew.bat :app:assembleDebug`
  (→ `app/build/outputs/apk/debug/app-debug.apk`). Адрес API — в `data/DevLogApi.kt` (`BASE_URL`).
  SDK берётся из `androidApp/local.properties` (`sdk.dir`, в .gitignore).
