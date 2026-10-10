import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Плагин компилятора Compose (с Kotlin 2.x подключается отдельно, версия = версии Kotlin).
    id("org.jetbrains.kotlin.plugin.compose")
    // Плагин kotlinx.serialization — чтобы @Serializable-классы умели превращаться в JSON
    // (и чтобы маршруты навигации были обычными @Serializable-классами).
    id("org.jetbrains.kotlin.plugin.serialization")
    // KSP + Hilt: генерация кода внедрения зависимостей на этапе компиляции.
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    // namespace — базовый пакет для сгенерированного класса R и т.п.
    namespace = "io.github.vihrea1337.devlog.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vihrea1337.devlog"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            // R8: выкидывает неиспользуемый код и сжимает имена — APK заметно меньше.
            // Правила для Retrofit/kotlinx.serialization — в proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем отладочным ключом: APK раздаётся со своего сайта, не через Google Play,
            // и ключ магазина не нужен. Иначе release-сборка вышла бы неустанавливаемой.
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        // В юнит-тестах (на обычной JVM, без телефона) вызовы android.* возвращают
        // значения по умолчанию, а не падают — ViewModel тестируем без эмулятора.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Общий модуль: модели обмена с сервером. Одно определение на бэкенд и приложение —
    // раньше эти же классы дублировались здесь, и добавленное на сервере поле легко
    // было забыть в приложении.
    implementation("io.github.vihrea1337.devlog:shared:1.0")

    // Compose BOM — "спецификация версий": он сам подбирает согласованные версии всех compose-библиотек,
    // поэтому у отдельных compose-зависимостей версию не указываем.
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Расширенный набор значков Material (календарь, отчёт, GitHub-подобные и т.п.).
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Базовые androidx + интеграция Compose с Activity и жизненным циклом.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    // Заставка при запуске (Android 12+ API, с поддержкой старых версий).
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Навигация между экранами; маршруты — типобезопасные @Serializable-классы.
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Hilt: DI-контейнер + связка с ViewModel (hiltViewModel()) и с WorkManager.
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager: фоновая синхронизация и напоминания, которые переживают закрытие
    // приложения и перезагрузку телефона, и учитывают условия (есть сеть / нет).
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // DataStore — хранение настроек (вместо SharedPreferences): асинхронно, через Flow.
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Сеть: Retrofit (HTTP-клиент) + конвертер kotlinx.serialization (JSON <-> классы) + сам JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Драйвер SQLDelight для настоящего файла БД на Android (схема/запросы — в shared,
    // Ktor Client и рантайм SQLDelight приходят оттуда же транзитивно).
    implementation("app.cash.sqldelight:android-driver:2.0.2")
    // Движок OkHttp для Ktor Client (синхронизация из shared/). Движок CIO по умолчанию
    // делает TLS сам и на Android ломается о network_security_config: HTTPS-синхронизация
    // в debug-сборке падала с CertificateException. OkHttp использует TLS платформы.
    implementation("io.ktor:ktor-client-okhttp:3.0.3")

    // Юнит-тесты ViewModel: JUnit, тестовый диспетчер корутин, Turbine — удобная
    // проверка Flow («пришло такое состояние, потом такое»).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
}
