package io.github.vihrea1337.devlog

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование персональных секретов (сейчас — токен GitHub) перед тем, как класть их в БД.
 * AES-256-GCM: один блок и шифрует, и проверяет целостность (не нужен отдельный HMAC).
 *
 * Ключ — НЕ отдельный секрет, а SHA-256 от JWT_SECRET: тот секрет уже обязателен на внешнем
 * адресе (requireSecureSecret в Application.kt), заводить ради этого ещё одну переменную
 * окружения, которую надо не забыть прописать при деплое, не нужно.
 *
 * Формат хранения: base64(iv (12 байт) + шифротекст+тег). IV случайный на каждое шифрование —
 * повторное шифрование одного и того же токена даёт разные строки, это нормально и ожидаемо.
 */
object TokenCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val key: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(JwtService.secret.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherText)
    }

    /** null, если строка испорчена/зашифрована другим ключом — не роняем сервер на этом. */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipherText = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
}
