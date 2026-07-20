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
    // Ядро Ktor + сам HTTP-сервер (движок Netty).
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    // Согласование форматов ответа + JSON через kotlinx.serialization.
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    // Логгер — чтобы видеть, что происходит на сервере.
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Для тестов сервера (поднимают Ktor в памяти, без реального порта).
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
}
