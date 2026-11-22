package me.riverfrozer.penguinsmagic.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 调用令牌等同账户身份，所以首选只留 Android Keystore 加密后的密文。
 *
 * 国产机的 keystore2 并不总是可用（分身空间、厂商密钥服务被禁用、系统升级后密钥丢失等），
 * 那种情况下整套拦截功能会直接不可用。于是退一步：把令牌放进应用私有目录的普通文件——
 * 不加密，但只有本进程能读、allowBackup 已经关掉、不进日志；等 Keystore 恢复可用，
 * 下一次读取该令牌时会自动搬回加密存储并删掉明文。
 */
class SecureTokenStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SECTION, Context.MODE_PRIVATE)

    /** 当前有多少令牌分别落在哪种存储里，给关于页如实交代安全级别。 */
    data class Storage(val encrypted: Int, val inPrivateDir: Int) {
        val total: Int get() = encrypted + inPrivateDir
    }

    /** 加密或落盘能不能成功、有没有降级，界面要按这四种情况分别说话。 */
    sealed class Save {
        data object Encrypted : Save()
        data class Downgraded(val cause: Exception) : Save()
        data object Kept : Save()
        data class Failed(val cause: Exception) : Save()
    }

    /** Keystore 可用就加密存；否则退到私有目录。两条路都写不进去才算失败。 */
    fun save(key: String, token: String): Save {
        val keystoreFailure = encryptIntoPrefs(key, token)
        return when {
            keystoreFailure == null -> {
                removePrivateCopy(key)
                Save.Encrypted
            }
            writePrivateCopy(key, token) -> {
                Log.w(TAG, "keystore unusable, token falls back to the private dir", keystoreFailure)
                Save.Downgraded(keystoreFailure)
            }
            else -> Save.Failed(keystoreFailure)
        }
    }

    /** 解不开（含密钥被系统清除）时返回 null，调用方按「需要重新配置」处理。 */
    fun load(key: String): String? = loadEncrypted(key) ?: loadPrivateAndUpgrade(key)

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
        removePrivateCopy(key)
    }

    /**
     * secure_tokens 里只放令牌密文，所以条目数就是加密存储的条数。
     * 搬运的那一瞬间两份并存，按明文算降级、密文那一侧不再重复计数。
     */
    fun storage(): Storage {
        val privateKeys = appContext.fileList()
            ?.filter { it.startsWith(PRIVATE_PREFIX) }
            ?.map { it.removePrefix(PRIVATE_PREFIX) }
            ?.toSet()
            ?: emptySet()
        val encrypted = prefs.all.keys.count { it !in privateKeys }
        return Storage(encrypted, privateKeys.size)
    }

    private fun loadEncrypted(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val body = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val keyMaterial = secretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyMaterial, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (failure: Exception) {
            Log.w(TAG, "token $key unreadable", failure)
            null
        }
    }

    /** 私有目录只是临时避难所：能加密了就立刻搬回加密存储，搬成功了才删明文。 */
    private fun loadPrivateAndUpgrade(key: String): String? {
        val plain = readPrivateCopy(key) ?: return null
        if (encryptIntoPrefs(key, plain) == null) removePrivateCopy(key)
        return plain
    }

    private fun encryptIntoPrefs(key: String, token: String): Exception? = try {
        val secret = secretKey() ?: throw TokenStorageFailure("AndroidKeyStore 里没有可用的 AES 密钥，生成过程见日志")
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        val payload = iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        // 用 commit 不用 apply：apply 的写盘异常是异步吞掉的，那时「保存成功」并不真的等于落盘
        val stored = prefs.edit()
            .putString(key, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        if (stored) null else TokenStorageFailure("密文没能写入 $SECTION 存储")
    } catch (failure: Exception) {
        Log.w(TAG, "keystore save failed for $key", failure)
        failure
    }

    private fun privateFile(key: String) = File(appContext.filesDir, "$PRIVATE_PREFIX$key")

    private fun writePrivateCopy(key: String, token: String): Boolean = try {
        // openFileOutput 而不是直接写 File：这样才会按 MODE_PRIVATE 建文件权限
        appContext.openFileOutput("$PRIVATE_PREFIX$key", Context.MODE_PRIVATE).use {
            it.write(token.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (failure: Exception) {
        Log.w(TAG, "private dir write failed for $key", failure)
        false
    }

    private fun readPrivateCopy(key: String): String? = try {
        val file = privateFile(key)
        if (!file.isFile) null else file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (failure: Exception) {
        Log.w(TAG, "private dir read failed for $key", failure)
        null
    }

    private fun removePrivateCopy(key: String) {
        runCatching { privateFile(key).delete() }
    }

    private fun secretKey(): SecretKey? = try {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        existing ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey() as SecretKey
        }
    } catch (failure: Exception) {
        Log.w(TAG, "AndroidKeyStore AES key unavailable", failure)
        null
    }

    private companion object {
        const val SECTION = "secure_tokens"
        const val TAG = "SecureTokenStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "penguins_magic_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val PRIVATE_PREFIX = "plain_token_"
    }
}

/** 加密或落盘没能成功，但没有系统异常可抛时自己造一个，好让界面有东西可显示。 */
class TokenStorageFailure(message: String) : Exception(message)

/**
 * 异常链一路摊开。Keystore 的失败原因通常套在 KeyStoreException 里面层，
 * 只打印最外层等于什么都没打印；这里绝不含令牌明文。
 */
fun Throwable.diagnosis(): String = generateSequence(this) { it.cause }
    .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.localizedMessage ?: "（无消息）"}" }
