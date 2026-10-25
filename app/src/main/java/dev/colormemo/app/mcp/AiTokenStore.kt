package dev.colormemo.app.mcp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.colormemo.core.mcp.LocalMcpServer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local credential, encrypted separately from sync settings and excluded from backups. */
class AiTokenStore internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val key: (Boolean) -> SecretKey,
) {
    constructor(context: Context) : this(context.getSharedPreferences("private_ai", Context.MODE_PRIVATE))

    private constructor(preferences: SharedPreferences) : this(
        read = { preferences.getString("token_encrypted_v1", null) },
        write = { check(preferences.edit().putString("token_encrypted_v1", it).commit()) { "Cannot save MCP token" } },
        key = ::deviceKey,
    )

    // SharedPreferences can update its in-memory value even when commit fails.
    // Retry that write before returning the credential on a later startup attempt.
    private var writeFailed = false

    @Synchronized
    fun getOrCreate(): String {
        val encoded = read() ?: return reset()
        val packed = Base64.getDecoder().decode(encoded)
        require(packed.size == IV_BYTES + TOKEN_LENGTH + 16) { "Invalid saved MCP token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(AAD)
        val token = cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)).toString(Charsets.UTF_8)
        require(token.matches(Regex("[A-Za-z0-9_-]{$TOKEN_LENGTH}"))) { "Invalid saved MCP token" }
        if (writeFailed) persist(encoded)
        return token
    }

    @Synchronized
    fun reset(): String {
        val token = LocalMcpServer.generateToken()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(AAD)
        check(cipher.iv.size == IV_BYTES)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        persist(Base64.getEncoder().encodeToString(cipher.iv + encrypted))
        return token
    }

    private fun persist(encoded: String) {
        writeFailed = true
        write(encoded)
        writeFailed = false
    }

    private companion object {
        const val KEY_ALIAS = "colormemo.mcp.v1"
        const val IV_BYTES = 12
        const val TOKEN_LENGTH = 43
        val AAD = "dev.colormemo.app:mcp-token:v1".toByteArray(Charsets.UTF_8)

        fun deviceKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "Missing MCP token key" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }
    }
}
