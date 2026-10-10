// Корневой build-файл. Здесь только объявляем плагины и их версии (apply false — то есть
// сами плагины подключаются в модуле :app, а тут просто фиксируем версии для всего проекта).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    // KSP — генератор кода на этапе компиляции; через него работает Hilt.
    // Версия = версия Kotlin + версия самого KSP.
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
    // Hilt — внедрение зависимостей (DI): объекты вроде репозиториев и HTTP-клиента
    // создаёт и раздаёт он, экраны их только «просят» через конструктор.
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
}
