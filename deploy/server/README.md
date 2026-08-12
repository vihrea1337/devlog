# Деплой DevLog на сервер (Docker: Postgres + Ktor + Caddy)

Стек: **Postgres** (данные) + **Ktor** (наш бэкенд) + **Caddy** (HTTPS через DuckDNS).
Всё живёт в одной папке и своей сети; наружу торчит только Caddy на порту **${DEVLOG_PORT}**
(по умолчанию 34444).

Сервер: SSH-алиас из `~/.ssh/config` (`vps` — свой VPS, `brother` — сервер брата),
рабочая папка `/root/app/lev/devlog`. Ниже в командах подставляй нужный алиас.
Адрес после деплоя: `https://<домен>:<порт>` — то, что впишешь в `.env` (см. ниже).
Схему базы накатывает Flyway при старте бэкенда (миграции в `V…__.sql`).

---

## 1. Собрать дистрибутив на ПК

Из PowerShell в корне репозитория:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd backend
.\gradlew.bat distTar            # -> build/distributions/devlog-backend-0.1.0.tar
cd ..
```

_В прод едет **дистрибутив**, а не один «толстый» jar: в fat-jar склеиваются файлы
`META-INF/services`, из-за чего Flyway терял плагины и молча пропускал миграции —
сервер поднимался с пустой базой. Подробности в `backend/build.gradle.kts`._
_(Если локальный сервер запущен через `gradlew run` — сначала останови его, Ctrl+C.)_

## 2. Залить на сервер

```powershell
# папку деплоя целиком (создаст /root/app/lev/devlog как копию deploy/server)
scp -r deploy/server brother:/root/app/lev/devlog
# и дистрибутив рядом с его Dockerfile (имя ровно devlog-backend.tar — так ждёт Dockerfile)
scp backend/build/distributions/devlog-backend-0.1.0.tar brother:/root/app/lev/devlog/backend/devlog-backend.tar
```
_(Если `scp -r` ругается, что `/root/app/lev/devlog` уже есть — залей содержимое: `scp -r deploy/server/* brother:/root/app/lev/devlog/`.)_

## 3. Настроить секреты и поднять (на сервере)

```bash
ssh brother
cd /root/app/lev/devlog

cp .env.example .env && nano .env               # DB_PASSWORD, DUCKDNS_TOKEN, DEVLOG_DOMAIN, DEVLOG_PORT
cp devlog.env.example devlog.env && nano devlog.env   # GROQ_API_KEY, JWT_SECRET
# подсказка для JWT_SECRET: openssl rand -hex 32

docker compose up -d --build                    # соберёт backend + caddy и поднимет всё
docker compose logs -f caddy                    # дождись строки о выпуске сертификата (Ctrl+C — выйти)
```

## 4. DuckDNS + проверка

- На https://www.duckdns.org убедись, что домен из `DEVLOG_DOMAIN` указывает на IP этого сервера
  (узнать: `ssh brother 'curl -s ifconfig.me'`). Если завёл новый домен под DevLog — пропиши ему этот IP.
- Проверь снаружи (подставь свои домен и порт):
```bash
curl -s https://<домен>:<порт>/health          # ожидаем {"status":"ok"}
```
- Открой `https://<домен>:<порт>/` в браузере — увидишь страницу входа DevLog.
  Зарегистрируйся, добавь запись, собери отчёт — как проверяли локально.

## Обновление бэкенда потом

```powershell
cd backend; .\gradlew.bat distTar; cd ..
scp backend/build/distributions/devlog-backend-0.1.0.tar brother:/root/app/lev/devlog/backend/devlog-backend.tar
```
```bash
cd /root/app/lev/devlog && docker compose up -d --build devlog-backend
```

## Полезное

```bash
docker compose ps                 # статус контейнеров
docker compose logs -f devlog-backend   # логи бэкенда (Hikari подключился, ИИ вкл/выкл)
docker exec devlog-pg psql -U postgres -d devlog -c "\dt"   # список таблиц
docker exec devlog-pg psql -U postgres -d devlog -c "SELECT version, description FROM flyway_schema_history"
docker compose down               # остановить всё (данные в томах сохраняются)
```

## Если что-то не поднялось

**`env file /root/app/lev/devlog/devlog.env not found`** — не создан файл секретов бэкенда:
`cp devlog.env.example devlog.env && nano devlog.env`. Файлов секретов ДВА: `.env` (для
docker-compose) и `devlog.env` (для самого бэкенда), оба в git не хранятся.

**`xcaddy ... [FATAL] exit status 1` при сборке caddy** — версии Caddy и плагина DuckDNS
разъехались. В `caddy/Dockerfile` версии закреплены намеренно (Caddy 2.10 + duckdns v0.5.0):
плавающий `caddy:2-builder` тянет Caddy 2.11 на библиотеке libdns v1.1, а плагин собран
под libdns v1.0.0-beta.1 со старым интерфейсом. Не заменяй пины на `:2`, пока плагин
не обновят.

**Сборка Caddy падает или висит на слабом сервере** (1 ядро, <1 ГБ ОЗУ) — компилировать
Go на таком железе тяжело. Собери образ на своём ПК и перевези готовым:
```powershell
docker build -t devlog-caddy:duckdns deploy/server/caddy/
docker save devlog-caddy:duckdns | gzip > caddy-duckdns.tar.gz
scp caddy-duckdns.tar.gz vps:/root/app/lev/devlog/
```
```bash
gunzip -c caddy-duckdns.tar.gz | docker load
# и в docker-compose.yml у сервиса caddy заменить `build: ./caddy` на `image: devlog-caddy:duckdns`
```

**Сертификат не выпускается** — проверь, что домен в `.env` написан латиницей и что на
duckdns.org у него указан IP именно этого сервера.

**Сервер работает, но в базе нет таблиц** (в логах `relation "entries" does not exist`) —
миграции не применились. Загляни в лог бэкенда: если там `SQL migrations were detected but
not run because they did not follow the filename convention`, значит в деплой уехал fat-jar
вместо дистрибутива. В fat-jar теряются файлы `META-INF/services`, Flyway остаётся без своих
плагинов и молча пропускает миграции. Собирай `./gradlew distTar` и клади на сервер
`backend/devlog-backend.tar`.
