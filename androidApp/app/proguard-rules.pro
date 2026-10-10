# Правила R8 для release-сборки.
# Retrofit и kotlinx.serialization приходят со своими правилами внутри библиотек,
# здесь — то, что нужно именно нашему коду.

# Классы с @Serializable из shared/ (DTO): R8 не должен выкидывать их сериализаторы.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.vihrea1337.devlog.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class io.github.vihrea1337.devlog.**$$serializer { *; }

# Retrofit: методы интерфейса API читаются через рефлексию.
-keep,allowobfuscation interface io.github.vihrea1337.devlog.android.data.DevLogApi

# Ktor CIO (синхронизация в shared/) ссылается на классы, которых на Android нет.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
