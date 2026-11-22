package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 以「订阅 + 归一化号码」为 key 缓存结论：来电查询天然突发，而服务端按账户限流。
 * 命中结果短 TTL（管理员随时可能改分类），未入库结果长 TTL。
 */
class DecisionCache(context: Context) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val lock = Any()
    private val entries = HashMap<String, Cached>()

    private data class Cached(val info: BlockInfo, val expiresAt: Long)

    init {
        val now = System.currentTimeMillis()
        val stored = writer.readArray()
        for (index in 0 until stored.length()) {
            val obj = stored.optJSONObject(index) ?: continue
            val expiresAt = obj.optLong("expiresAt")
            if (expiresAt <= now) continue
            val info = fromJson(obj.optJSONObject("data")) ?: continue
            val key = obj.optString("key")
            if (key.isBlank()) continue
            entries[key] = Cached(info, expiresAt)
        }
    }

    fun get(subscriptionId: Long, number: String, nowMillis: Long): BlockInfo? {
        val key = key(subscriptionId, number)
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (entry.expiresAt <= nowMillis) {
                entries.remove(key)
                writer.write { snapshotLocked() }
                return null
            }
            return entry.info
        }
    }

    fun put(subscriptionId: Long, info: BlockInfo, nowMillis: Long) {
        val ttl = when {
            info.blocked -> BLOCKED_TTL_MS
            info.inDatabase -> BLOCKED_TTL_MS
            else -> MISS_TTL_MS
        }
        val key = key(subscriptionId, info.number)
        synchronized(lock) {
            entries[key] = Cached(info, nowMillis + ttl)
            if (entries.size > MAX_ENTRIES) evictLocked()
            writer.write { snapshotLocked() }
        }
    }

    fun clear(): Int = synchronized(lock) {
        val removed = entries.size
        entries.clear()
        writer.write { snapshotLocked() }
        removed
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun flush() {
        synchronized(lock) { writer.flush { snapshotLocked() } }
    }

    private fun evictLocked() {
        val overflow = entries.size - MAX_ENTRIES
        if (overflow <= 0) return
        entries.entries.sortedBy { it.value.expiresAt }
            .take(overflow)
            .forEach { entries.remove(it.key) }
    }

    private fun key(subscriptionId: Long, number: String) = "$subscriptionId|$number"

    private fun snapshotLocked(): String {
        val array = JSONArray()
        entries.forEach { (key, value) ->
            array.put(JSONObject().apply {
                put("key", key)
                put("expiresAt", value.expiresAt)
                put("data", toJson(value.info))
            })
        }
        return array.toString()
    }

    private fun toJson(info: BlockInfo): JSONObject = JSONObject().apply {
        put("number", info.number)
        put("hit", info.hit)
        put("action", info.action)
        put("inDatabase", info.inDatabase)
        put("reason", info.reason)
        info.rule?.let { rule ->
            put("rule", JSONObject().apply {
                put("typeId", rule.typeId)
                put("typeName", rule.typeName)
                put("blockWholeType", rule.blockWholeType)
                put("subtagId", rule.subtagId)
                put("subtagName", rule.subtagName)
                put("subtagKind", rule.subtagKind)
                put("brand", rule.brand)
                put("scamCategory", rule.scamCategory)
            })
        }
    }

    private fun fromJson(obj: JSONObject?): BlockInfo? {
        if (obj == null) return null
        val number = obj.optionalString("number")
        if (number.isBlank()) return null
        val ruleObject = obj.optJSONObject("rule")
        return BlockInfo(
            number = number,
            hit = obj.optBoolean("hit"),
            action = obj.optionalString("action"),
            inDatabase = obj.optBoolean("inDatabase"),
            reason = obj.optionalString("reason"),
            rule = ruleObject?.let {
                RuleInfo(
                    typeId = it.optInt("typeId"),
                    typeName = it.optionalString("typeName"),
                    blockWholeType = it.optBoolean("blockWholeType"),
                    subtagId = it.optInt("subtagId"),
                    subtagName = it.optionalString("subtagName"),
                    subtagKind = it.optionalString("subtagKind"),
                    brand = it.optionalString("brand"),
                    scamCategory = it.optionalString("scamCategory"),
                )
            },
            quota = null,
        )
    }

    private companion object {
        const val FILE = "number_cache.json"
        const val MAX_ENTRIES = 5000
        const val BLOCKED_TTL_MS = 12L * 60 * 60 * 1000
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
