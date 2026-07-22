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

## Стек (зафиксирован)
- **Бэкенд:** Kotlin + Ktor + PostgreSQL + Exposed + HikariCP.
- **ИИ:** Groq (OpenAI-совместимый API, бесплатно), модель `openai/gpt-oss-20b`. Ключ только
  на сервере (`backend/secrets.properties` → `groq.api.key`, в .gitignore), обработка в фоне.
  _(Изначально планировался Claude API — заменён на Groq ради бесплатности на старте.)_
- **Клиенты:** Kotlin Multiplatform + Compose Multiplatform (Android/iOS/Desktop из общего кода).
  Веб на старте — HTML-страница, которую отдаёт сам бэкенд.
- **Авторизация:** JWT, пароли хешируются, изоляция пользователей (multi-tenant) с Фазы 1.
- **Деплой:** Docker на сервере брата `/root/app/lev`, HTTPS через Caddy + DuckDNS
  (порт из диапазона 34000–35000), по образцу Expenses.

## Статус
- **Фаза 1 (MVP: веб + бэкенд) завершена и проверена вживую end-to-end (2026-07-22).**
  Работает: регистрация/вход (JWT), CRUD записей, фоновая ИИ-структуризация (Groq),
  отчёт за период (Markdown + ИИ-причёсывание), публичная ссылка на отчёт `/r/{token}`,
  веб-страница. 20 тестов на H2 зелёные. Прогнано на реальном Postgres в Docker + Groq.
- **Фаза 2 начата: ✅ деплой на сервер брата (2026-07-22).** DevLog в проде:
  `https://vihreaschedule.duckdns.org:34444` (Docker: `devlog-pg` + `devlog-backend` + `devlog-caddy`,
  папка `/root/app/lev/devlog`, порт 34444). Деплой-файлы и runbook — в `deploy/server/`.
- **Фаза 2: ✅ CI (GitHub Actions), ✅ проекты (CRUD + выключатель ИИ по проекту + веб).**
  Осталось по Фазе 2: PDF-экспорт отчёта.
- **Фаза 3 начата: ✅ Android-приложение (2026-07-22)** — `androidApp/` (обычное Android на
  Compose + Retrofit, НЕ KMP пока): вход/регистрация (JWT), лента записей со статусом ИИ и
  структурой, добавление/удаление/переобработка; ходит в боевой API, собирается в CI.

## Деплой / редеплой
- Файлы: `deploy/server/` (docker-compose + Caddyfile + Dockerfile'ы + README-runbook).
- Секреты на сервере (не в git): `.env` (DB_PASSWORD, DUCKDNS_TOKEN, DEVLOG_DOMAIN, DEVLOG_PORT),
  `devlog.env` (GROQ_API_KEY, JWT_SECRET). Домен — отдельный DuckDNS `vihreaschedule.duckdns.org`.
- Обновить бэкенд: `gradlew buildFatJar` → `scp` jar в `/root/app/lev/devlog/backend/` →
  на сервере `docker compose up -d --build devlog-backend`.

## Команды сборки/запуска
- **Сборка/тесты:** `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
  из `backend/`: `./gradlew buildFatJar` / `test` / `run`.
- **Локальный запуск вживую:** поднять Docker Desktop → `docker compose -f backend/docker-compose.yml up -d`
  (Postgres `devlog-pg` на порту 5433) → `./gradlew -p backend run` → http://localhost:8080.
- **Android:** из `androidApp/` c `JAVA_HOME=...jbr`, `.\gradlew.bat :app:assembleDebug`
  (→ `app/build/outputs/apk/debug/app-debug.apk`). Адрес API — в `data/DevLogApi.kt` (`BASE_URL`).
  SDK берётся из `androidApp/local.properties` (`sdk.dir`, в .gitignore).
