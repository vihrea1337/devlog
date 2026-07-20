package io.github.vihrea1337.devlog

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Работа с паролями. В базе храним НЕ сам пароль, а его bcrypt-хеш — необратимую
 * "свёртку". Проверить пароль можно (verify), а восстановить исходный из хеша — нет.
 * bcrypt специально медленный и с "солью" внутри — это защита от перебора.
 */
object Passwords {
    private const val COST = 12 // "стоимость": больше = медленнее хеш = дороже перебор

    fun hash(raw: String): String =
        BCrypt.withDefaults().hashToString(COST, raw.toCharArray())

    fun verify(raw: String, hash: String): Boolean =
        BCrypt.verifyer().verify(raw.toCharArray(), hash).verified
}
