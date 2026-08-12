import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Обычная Kotlin-библиотека под JVM.
    kotlin("jvm") version "2.1.0"
    // Учит @Serializable-классы превращаться в JSON и обратно.
    kotlin("plugin.serialization") version "2.1.0"
}

// Группа и имя нужны, чтобы бэкенд и Android-приложение подключали модуль
// как обычную зависимость "io.github.vihrea1337.devlog:shared".
group = "io.github.vihrea1337.devlog"
version = "1.0"

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

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
