# Деплой DevLog на сервер (Docker: Postgres + Ktor + Caddy)

Стек: **Postgres** (данные) + **Ktor** (наш бэкенд) + **Caddy** (HTTPS через DuckDNS).
Всё живёт в одной папке и своей сети; наружу торчит только Caddy на порту **${DEVLOG_PORT}**
(по умолчанию 34444). Соседний проект Expenses (порт 34443) не затрагиваем.

Сервер: `root@45.140.147.225` (SSH-алиас `brother`), рабочая папка `/root/app/lev/devlog`.
Адрес после деплоя: `https://<домен>:<порт>` — то, что впишешь в `.env` (см. ниже).
Это первый деплой — база стартует пустой, таблицы приложение создаёт само при запуске.

---

## 1. Собрать jar на ПК

Из PowerShell в корне репозитория:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd backend
.\gradlew.bat buildFatJar        # -> build/libs/devlog-backend-all.jar
cd ..
```
_(Если локальный сервер запущен через `gradlew run` — сначала останови его, Ctrl+C.)_

## 2. Залить на сервер

```powershell
# папку деплоя целиком (создаст /root/app/lev/devlog как копию deploy/server)
scp -r deploy/server brother:/root/app/lev/devlog
# и сам jar рядом с его Dockerfile
scp backend/build/libs/devlog-backend-all.jar brother:/root/app/lev/devlog/backend/
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
  (45.140.147.225). Если завёл новый домен под DevLog — пропиши ему этот IP.
- Проверь снаружи (подставь свои домен и порт):
```bash
curl -s https://<домен>:<порт>/health          # ожидаем {"status":"ok"}
```
- Открой `https://<домен>:<порт>/` в браузере — увидишь страницу входа DevLog.
  Зарегистрируйся, добавь запись, собери отчёт — как проверяли локально.

## Обновление бэкенда потом

```powershell
cd backend; .\gradlew.bat buildFatJar; cd ..
scp backend/build/libs/devlog-backend-all.jar brother:/root/app/lev/devlog/backend/
```
```bash
cd /root/app/lev/devlog && docker compose up -d --build devlog-backend
```

## Полезное

```bash
docker compose ps                 # статус контейнеров
docker compose logs -f devlog-backend   # логи бэкенда (Hikari подключился, ИИ вкл/выкл)
docker exec devlog-pg psql -U postgres -d devlog -c "\dt"   # список таблиц (ждём 5)
docker compose down               # остановить всё (данные в томах сохраняются)
```
