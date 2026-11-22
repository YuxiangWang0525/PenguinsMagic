package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryStats(
    val blockedTotal: Int,
    val blockedToday: Int,
    val markedTotal: Int,
)

/** 拦截历史：号码 + 类型 + 品牌或诈骗手段。同一号码一天内合并计数。 */
class HistoryRepository(context: Context) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val lock = Any()
    private val records = ArrayList<BlockRecord>()
    private val changes = ChangeNotifier()

    init {
        val stored = writer.readArray()
        for (index in 0 until stored.length()) {
            val obj = stored.optJSONObject(index) ?: continue
            fromJson(obj)?.let { records.add(it) }
        }
        trimLocked()
    }

    fun all(): List<BlockRecord> = synchronized(lock) {
        records.sortedByDescending { it.lastSeen }
    }

    fun stats(nowMillis: Long): HistoryStats = synchronized(lock) {
        val todayStart = dayStartUtc(nowMillis)
        var blockedTotal = 0
        var blockedToday = 0
        var markedTotal = 0
        for (record in records) {
            if (record.blocked) {
                blockedTotal += record.count
                if (record.lastSeen >= todayStart) blockedToday += record.count
            } else {
                markedTotal += record.count
            }
        }
        HistoryStats(blockedTotal, blockedToday, markedTotal)
    }

    fun record(
        displayNumber: String,
        info: BlockInfo,
        sourceName: String,
        timestamp: Long,
    ) {
        val rule = info.rule
        val entry = BlockRecord(
            number = displayNumber,
            normalized = info.number,
            typeName = rule?.typeName.orEmpty(),
            detail = rule?.detailLabel.orEmpty(),
            isScam = rule?.isScam == true,
            blocked = info.blocked,
            reason = info.reason,
            sourceName = sourceName,
            firstSeen = timestamp,
            lastSeen = timestamp,
            count = 1,
        )
        synchronized(lock) {
            val index = records.indexOfFirst {
                it.normalized == entry.normalized &&
                    it.sourceName == entry.sourceName &&
                    it.blocked == entry.blocked &&
                    entry.lastSeen - it.lastSeen < MERGE_WINDOW_MS
            }
            if (index >= 0) {
                val existing = records[index]
                records[index] = existing.withCount(existing.count + 1, entry.lastSeen)
                    .copy(typeName = entry.typeName.ifBlank { existing.typeName })
            } else {
                records.add(entry)
            }
            trimLocked()
            writer.write { snapshotLocked() }
        }
        changes.notifyChanged()
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
            writer.write { snapshotLocked() }
        }
        changes.notifyChanged()
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

    private fun trimLocked() {
        if (records.size > MAX_RECORDS) {
            val sorted = records.sortedByDescending { it.lastSeen }
            records.clear()
            records.addAll(sorted.take(MAX_RECORDS))
        }
    }

    private fun snapshotLocked(): String {
        val array = JSONArray()
        records.forEach { array.put(toJson(it)) }
        return array.toString()
    }

    private fun toJson(record: BlockRecord): JSONObject = JSONObject().apply {
        put("number", record.number)
        put("normalized", record.normalized)
        put("typeName", record.typeName)
        put("detail", record.detail)
        put("isScam", record.isScam)
        put("blocked", record.blocked)
        put("reason", record.reason)
        put("sourceName", record.sourceName)
        put("firstSeen", record.firstSeen)
        put("lastSeen", record.lastSeen)
        put("count", record.count)
    }

    private fun fromJson(obj: JSONObject): BlockRecord? {
        val number = obj.optString("number")
        if (number.isBlank()) return null
        return BlockRecord(
            number = number,
            normalized = obj.optString("normalized").ifBlank { number },
            typeName = obj.optionalString("typeName"),
            detail = obj.optionalString("detail"),
            isScam = obj.optBoolean("isScam"),
            blocked = obj.optBoolean("blocked"),
            reason = obj.optionalString("reason"),
            sourceName = obj.optionalString("sourceName"),
            firstSeen = obj.optLong("firstSeen"),
            lastSeen = obj.optLong("lastSeen"),
            count = obj.optInt("count", 1),
        )
    }

    private fun dayStartUtc(nowMillis: Long): Long {
        // 用本地时区的自然日：用户关心的是「今天」
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private companion object {
        const val FILE = "history.json"
        const val MAX_RECORDS = 1000
        const val MERGE_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
