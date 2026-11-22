package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 订阅了哪些拦截接口，令牌单独放在 [SecureTokenStore] 里。 */
class SubscriptionRepository(context: Context) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val tokens = SecureTokenStore(context)
    private val lock = Any()
    private val items = ArrayList<Subscription>()
    private val changes = ChangeNotifier()
    private var nextId = 1L

    init {
        var maxId = 0L
        val stored = writer.readArray()
        for (index in 0 until stored.length()) {
            val obj = stored.optJSONObject(index) ?: continue
            val subscription = fromJson(obj) ?: continue
            items.add(subscription)
            if (subscription.id > maxId) maxId = subscription.id
        }
        nextId = maxId + 1
    }

    fun all(): List<Subscription> = synchronized(lock) { items.map { it.copy() } }

    fun find(id: Long): Subscription? = synchronized(lock) {
        items.firstOrNull { it.id == id }?.copy()
    }

    /**
     * token 为 null 表示沿用已保存的令牌。只有返回 Failed 才算没保存，
     * 那时订阅列表完全没动过——不会留下「提示失败但列表里多了一条没令牌的订阅」，
     * 那种订阅会被 [queryable] 静默跳过，用户以为在拦，其实一通电话都没查过。
     */
    fun upsert(subscription: Subscription, token: String?): SecureTokenStore.Save {
        val outcome: SecureTokenStore.Save = synchronized(lock) {
            val at = items.indexOfFirst { it.id == subscription.id }
            val targetId = if (at >= 0) items[at].id else nextId
            val save = if (token.isNullOrBlank()) {
                SecureTokenStore.Save.Kept
            } else {
                tokens.save(tokenKey(targetId), token.trim())
            }
            if (save is SecureTokenStore.Save.Failed) return save
            if (at >= 0) {
                items[at].apply {
                    name = subscription.name
                    baseUrl = subscription.baseUrl
                    headerOverride = subscription.headerOverride
                    enabled = subscription.enabled
                    learnBlocked = subscription.learnBlocked
                }
            } else {
                items.add(subscription.copy(id = nextId++))
            }
            persistLocked()
            save
        }
        changes.notifyChanged()
        return outcome
    }

    fun delete(id: Long) {
        synchronized(lock) {
            items.removeAll { it.id == id }
            tokens.delete(tokenKey(id))
            persistLocked()
        }
        changes.notifyChanged()
    }

    fun update(id: Long, mutate: (Subscription) -> Unit) {
        synchronized(lock) {
            val target = items.firstOrNull { it.id == id } ?: return
            mutate(target)
            persistLocked()
        }
        changes.notifyChanged()
    }

    fun tokenFor(id: Long): String? = tokens.load(tokenKey(id))

    /** 令牌目前分别存在哪两种存储里，关于页用它如实交代安全级别。 */
    fun tokenStorage(): SecureTokenStore.Storage = tokens.storage()

    /** 有令牌、已启用、且没有停在退避或需要重新配置状态里的订阅。 */
    fun queryable(nowMillis: Long): List<Pair<Subscription, String>> {
        val candidates = synchronized(lock) {
            items.filter { it.enabled && !it.needsReconfig && it.backoffUntil <= nowMillis }
                .map { it.copy() }
        }
        return candidates.mapNotNull { subscription ->
            val token = tokens.load(tokenKey(subscription.id))
            if (token.isNullOrBlank()) null else subscription to token
        }
    }

    fun flush() {
        synchronized(lock) { writer.flush { snapshotLocked() } }
    }

    fun onChange(listener: () -> Unit) {
        changes.add(listener)
    }

    fun offChange(listener: () -> Unit) {
        changes.remove(listener)
    }

    private fun persistLocked() {
        writer.write { snapshotLocked() }
    }

    private fun snapshotLocked(): String {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return array.toString()
    }

    private fun tokenKey(id: Long) = "sub_$id"

    private fun toJson(subscription: Subscription): JSONObject = JSONObject().apply {
        put("id", subscription.id)
        put("name", subscription.name)
        put("baseUrl", subscription.baseUrl)
        put("headerOverride", subscription.headerOverride)
        put("resolvedHeader", subscription.resolvedHeader)
        put("enabled", subscription.enabled)
        put("learnBlocked", subscription.learnBlocked)
        put("lastProbeAt", subscription.lastProbeAt)
        put("lastErrorCode", subscription.lastErrorCode)
        put("lastErrorMessage", subscription.lastErrorMessage)
        put("hourlyLimit", subscription.hourlyLimit)
        put("quotaUsed", subscription.quotaUsed)
        put("quotaRemaining", subscription.quotaRemaining)
        put("backoffUntil", subscription.backoffUntil)
    }

    private fun fromJson(obj: JSONObject): Subscription? {
        val url = obj.optString("baseUrl")
        if (url.isBlank()) return null
        return Subscription(
            id = obj.optLong("id"),
            name = obj.optString("name").ifBlank { url },
            baseUrl = url.trimEnd('/'),
            headerOverride = obj.optString("headerOverride"),
            resolvedHeader = obj.optString("resolvedHeader")
                .ifBlank { Subscription.DEFAULT_HEADER },
            enabled = obj.optBoolean("enabled", true),
            learnBlocked = obj.optBoolean("learnBlocked", false),
            lastProbeAt = obj.optLong("lastProbeAt"),
            lastErrorCode = obj.optInt("lastErrorCode"),
            lastErrorMessage = obj.optionalString("lastErrorMessage"),
            hourlyLimit = obj.optInt("hourlyLimit"),
            quotaUsed = obj.optInt("quotaUsed"),
            quotaRemaining = obj.optInt("quotaRemaining", -1),
            backoffUntil = obj.optLong("backoffUntil"),
        )
    }

    private companion object {
        const val FILE = "subscriptions.json"
    }
}
