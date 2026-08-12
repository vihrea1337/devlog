plugins {
    // "application" — умеет запускать сервер и собирать его в исполняемый вид.
    application
    // Kotlin для JVM — компилирует Kotlin в байт-код, который выполняет Java.
    kotlin("jvm") version "2.1.0"
    // kotlinx.serialization — учит компилятор превращать классы в JSON и обратно.
    kotlin("plugin.serialization") version "2.1.0"
    // Плагин Ktor — удобный запуск и сборка "толстого" JAR (один файл для деплоя).
    id("io.ktor.plugin") version "3.0.3"
}

group = "io.github.vihrea1337.devlog"
version = "0.1.0"

application {
    // Класс с функцией main(), с которого стартует сервер.
    // Файл Application.kt Kotlin превращает в класс с именем ApplicationKt.
    mainClass.set("io.github.vihrea1337.devlog.ApplicationKt")
}

kotlin {
    // Компилировать под Java 21 (её JDK идёт с Android Studio).
    jvmToolchain(21)
}

repositories {
    // Откуда Gradle качает библиотеки.
    mavenCentral()
}

dependencies {
    // Общий модуль: модели обмена с клиентами (DTO). Одно определение на всех —
    // раньше те же классы жили отдельно на бэкенде и в Android и могли разъехаться.
    implementation("io.github.vihrea1337.devlog:shared:1.0")

    // Ядро Ktor + сам HTTP-сервер (движок Netty).
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    // Согласование форматов ответа + JSON через kotlinx.serialization.
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    // Логгер — чтобы видеть, что происходит на сервере.
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // --- Работа с базой данных ---
    // Exposed — Kotlin-обёртка над SQL: таблицы описываем Kotlin-объектами,
    // запросы пишем типобезопасно на Kotlin, а не строками SQL.
    val exposedVersion = "0.57.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")      // ядро: типы колонок, DSL
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")      // мост Exposed → JDBC (выполнение запросов)
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion") // колонки дата/время (java.time)
    // JDBC-драйвер PostgreSQL — как JVM физически общается именно с Postgres.
    implementation("org.postgresql:postgresql:42.7.4")
    // HikariCP — пул соединений: держит готовые подключения к БД и переиспользует их.
    implementation("com.zaxxer:HikariCP:6.2.1")
    // Flyway — миграции схемы: SQL-файлы в resources/db/migration применяются по порядку,
    // применённые версии Flyway помнит в таблице flyway_schema_history. Без него добавить
    // колонку в уже существующую боевую базу нечем (SchemaUtils умеет только создавать таблицы).
    val flywayVersion = "11.1.0"
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    // В Flyway 10+ поддержка каждой СУБД вынесена в отдельный модуль.
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // --- Авторизация ---
    // JWT (JSON Web Token): выдаём токен при входе и проверяем его на защищённых ручках.
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    // Хеширование паролей (bcrypt) — в базе храним только хеш, не сам пароль.
    implementation("at.favre.lib:bcrypt:0.10.2")
    // Единая обработка ошибок → аккуратные JSON-ответы вместо стектрейса.
    implementation("io.ktor:ktor-server-status-pages")
    // SSE (Server-Sent Events) — сервер сам шлёт клиенту события по открытому соединению.
    // Нужен, чтобы лента обновлялась сразу, а не опросом раз в несколько секунд.
    implementation("io.ktor:ktor-server-sse")

    // --- HTTP-клиент для Groq (ИИ-обработка записей) ---
    implementation("io.ktor:ktor-client-core")                // ядро клиента
    implementation("io.ktor:ktor-client-cio")                 // движок: кто реально шлёт запросы
    implementation("io.ktor:ktor-client-content-negotiation") // разбирать JSON-ответы Groq

    // Для тестов сервера (поднимают Ktor в памяти, без реального порта).
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
    // H2 — встроенная база «в памяти»: тестируем авторизацию на настоящем SQL без Postgres/Docker.
    testImplementation("com.h2database:h2:2.2.224")
}

/**
 * ПОЧЕМУ МЫ НЕ ДЕПЛОИМ «ТОЛСТЫЙ» JAR.
 *
 * buildFatJar распаковывает все библиотеки в один архив, и файлы `META-INF/services`
 * при этом конфликтуют по имени: побеждает какой-то один, остальные пропадают.
 * Через эти файлы библиотеки находят свои модули (механизм ServiceLoader).
 *
 * Что это сломало: flyway-core перечисляет там 26 своих плагинов, а flyway-database-postgresql —
 * 3, и список затирался. Без потерянных плагинов Flyway переставал понимать имена файлов
 * миграций («Unrecognised migration name format» на корректном `V1__initial_schema.sql`)
 * и МОЛЧА их пропускал: сервер поднимался с пустой базой и валился на первом запросе.
 * Через `gradlew run` этого не видно — там нет fat-jar, ресурсы читаются из папки сборки.
 *
 * Штатное `mergeServiceFiles()` в этой связке (плагин Ktor + его копия Shadow) до задачи
 * не доезжает. Поэтому в прод едет ДИСТРИБУТИВ (`./gradlew distTar`): библиотеки остаются
 * отдельными jar-ами, ничего не сливается и не теряется. buildFatJar оставлен только
 * для быстрых локальных проверок.
 */

tasks.test {
    // В тестах ИИ выключен принудительно: детерминированно и без сетевых обращений к Groq
    // (даже если рядом лежит secrets.properties с реальным ключом).
    systemProperty("devlog.ai.disabled", "true")
}
