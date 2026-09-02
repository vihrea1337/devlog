import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Обычная Kotlin-библиотека под JVM.
    kotlin("jvm") version "2.1.0"
    // Учит @Serializable-классы превращаться в JSON и обратно.
    kotlin("plugin.serialization") version "2.1.0"
    // Генерирует типобезопасный код БД из .sq-файлов (offline-кэш записей).
    id("app.cash.sqldelight") version "2.0.2"
}

// Группа и имя нужны, чтобы бэкенд и Android-приложение подключали модуль
// как обычную зависимость "io.github.vihrea1337.devlog:shared".
group = "io.github.vihrea1337.devlog"
version = "1.0"

sqldelight {
    databases {
        // Имя класса БД, который сгенерирует плагин: DevLogDatabase.
        // driver в него подставляет тот, кто подключает модуль (JVM-тест или Android).
        create("DevLogDatabase") {
            packageName.set("io.github.vihrea1337.devlog.db")
        }
    }
}

java {
    // Java 17, а не 21: под неё компилируется Android-приложение, и более новый
    // байт-код оно бы не приняло. Бэкенд (JVM 21) более старые класс-файлы читает спокойно.
    // Указываем именно уровень байт-кода, а не toolchain: отдельной JDK 17 на машине нет,
    // и требовать её ради пары классов с данными незачем.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

repositories {
    mavenCentral()
}

dependencies {
    // api, а не implementation: аннотации сериализации видны и тем, кто подключает модуль.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Ktor Client: тот же движок CIO, что уже использует бэкенд для походов к Groq —
    // чистый Kotlin/coroutines, без платформенных зависимостей, поэтому работает
    // одинаково и на JVM (тесты), и на Android.
    api("io.ktor:ktor-client-core:3.0.3")
    api("io.ktor:ktor-client-cio:3.0.3")
    api("io.ktor:ktor-client-content-negotiation:3.0.3")
    api("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // SQLDelight: рантайм + расширение для Flow (превращает Query<T> в Flow<List<T>>,
    // чтобы UI сам перерисовывался при изменении локальной БД).
    api("app.cash.sqldelight:runtime:2.0.2")
    api("app.cash.sqldelight:coroutines-extensions:2.0.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    // JDBC-драйвер SQLite — только для тестов на JVM: реальному Android-приложению
    // нужен другой драйвер (android-driver), его подключает androidApp.
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
