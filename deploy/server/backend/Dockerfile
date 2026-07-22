# Образ бэкенда для сервера: кладём готовый fat-jar рядом с этим файлом.
# Перед сборкой положи сюда devlog-backend-all.jar (собранный ./gradlew buildFatJar).
# Хост/порт/БД задаются переменными окружения из docker-compose (HOST=0.0.0.0, PORT=8080).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY devlog-backend-all.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
