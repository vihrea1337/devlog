import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Плагин компилятора Compose (с Kotlin 2.x подключается отдельно, версия = версии Kotlin).
    id("org.jetbrains.kotlin.plugin.compose")
    // Плагин kotlinx.serialization — чтобы @Serializable-классы умели превращаться в JSON.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // namespace — базовый пакет для сгенерированного класса R и т.п.
    namespace = "io.github.vihrea1337.devlog.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vihrea1337.devlog"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Для минимального приложения не урезаем код (проще собирать и понимать).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Включаем Jetpack Compose (декларативный UI).
        compose = true
    }

    // Разрешаем держать исходники в папке src/main/kotlin (а не только java).
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM — "спецификация версий": он сам подбирает согласованные версии всех compose-библиотек,
    // поэтому у отдельных compose-зависимостей версию не указываем.
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Базовые androidx + интеграция Compose с Activity и жизненным циклом.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Сеть: Retrofit (HTTP-клиент) + конвертер kotlinx.serialization (JSON <-> классы) + сам JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
