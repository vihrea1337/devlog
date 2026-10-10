# Правила R8 для release-сборки.
# Ktor и kotlinx.serialization приходят со своими правилами внутри библиотек,
# здесь — то, что нужно именно нашему коду.

# Классы с @Serializable из shared/ (DTO): R8 не должен выкидывать их сериализаторы.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.vihrea1337.devlog.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class io.github.vihrea1337.devlog.**$$serializer { *; }

# Ktor (и движок CIO из shared/) ссылается на классы, которых на Android нет.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
