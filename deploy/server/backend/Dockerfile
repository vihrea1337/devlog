# Образ бэкенда. Кладём рядом с этим файлом дистрибутив devlog-backend.tar,
# собранный командой `./gradlew distTar` (переименуй файл из build/distributions/).
#
# ПОЧЕМУ ДИСТРИБУТИВ, А НЕ ОДИН FAT-JAR: в fat-jar все библиотеки распаковываются в один
# архив, и файлы META-INF/services конфликтуют по имени — часть теряется. Из-за этого Flyway
# терял свои плагины, не понимал имена файлов миграций и молча их пропускал: сервер
# поднимался с пустой базой. В дистрибутиве библиотеки лежат отдельными jar-ами в lib/,
# ничего не сливается. Подробности — в backend/build.gradle.kts.
#
# Хост/порт/БД задаются переменными окружения из docker-compose (HOST=0.0.0.0, PORT=8080).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY devlog-backend.tar /tmp/app.tar
RUN tar xf /tmp/app.tar -C /app --strip-components=1 && rm /tmp/app.tar
ENTRYPOINT ["/app/bin/devlog-backend"]
