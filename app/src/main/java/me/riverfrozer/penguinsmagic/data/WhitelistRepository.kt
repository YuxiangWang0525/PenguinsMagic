package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自己的白名单。与通讯录分开：通讯录是系统里的熟人，这里是用户明确点名要放行的号码，
 * 连锁封禁期间也照样放行。
 */
class WhitelistRepository(context: Context) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val lock = Any()
    private val items = ArrayList<WhitelistEntry>()
    private val changes = ChangeNotifier()
    private var nextId = 1L

    init {
        var maxId = 0L
        val stored = writer.readArray()
        for (index in 0 until stored.length()) {
            val obj = stored.optJSONObject(index) ?: continue
            val entry = fromJson(obj) ?: continue
            items.add(entry)
            if (entry.id > maxId) maxId = entry.id
        }
        nextId = maxId + 1
    }

    fun all(): List<WhitelistEntry> = synchronized(lock) { items.map { it.copy() } }

    fun find(id: Long): WhitelistEntry? = synchronized(lock) {
        items.firstOrNull { it.id == id }?.copy()
    }

    fun upsert(entry: WhitelistEntry): Long {
        val id = synchronized(lock) {
            val at = items.indexOfFirst { it.id == entry.id }
            val saved = if (at >= 0) {
                entry.copy(id = items[at].id).also { items[at] = it }
            } else {
                entry.copy(id = nextId++).also { items.add(it) }
            }
            persistLocked()
            saved.id
        }
        changes.notifyChanged()
        return id
    }

    fun delete(id: Long) {
        synchronized(lock) {
            items.removeAll { it.id == id }
            persistLocked()
        }
        changes.notifyChanged()
    }

    /** 从拦截记录一键加白：同一个号码只留一条。 */
    fun add(rawNumber: String, note: String, nowMillis: Long): Boolean {
        val number = NumberNormalizer.forMatching(rawNumber) ?: return false
        synchronized(lock) {
            if (items.any { it.number == number }) return false
            items.add(WhitelistEntry(id = nextId++, number = number, note = note, createdAt = nowMillis))
            persistLocked()
        }
        changes.notifyChanged()
        return true
    }

    fun contains(rawNumber: String): Boolean {
        val number = NumberNormalizer.forMatching(rawNumber) ?: return false
        return synchronized(lock) { items.any { it.number == number } }
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

    private fun toJson(entry: WhitelistEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("number", entry.number)
        put("note", entry.note)
        put("createdAt", entry.createdAt)
    }

    private fun fromJson(obj: JSONObject): WhitelistEntry? {
        val number = obj.optionalString("number")
        if (number.isBlank()) return null
        return WhitelistEntry(
            id = obj.optLong("id"),
            number = number,
            note = obj.optionalString("note"),
            createdAt = obj.optLong("createdAt"),
        )
    }

    private companion object {
        const val FILE = "whitelist.json"
    }
}