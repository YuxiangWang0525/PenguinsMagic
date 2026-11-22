package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机自定义拦截规则。与订阅判定分开存：这些是用户自己的指令，
 * 命中即拦，不花配额、不等网络，也不受服务端改分类的影响。
 */
class BlockRuleRepository(context: Context) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val lock = Any()
    private val items = ArrayList<BlockRule>()
    private val changes = ChangeNotifier()
    private var nextId = 1L

    init {
        var maxId = 0L
        val stored = writer.readArray()
        for (index in 0 until stored.length()) {
            val obj = stored.optJSONObject(index) ?: continue
            val rule = fromJson(obj) ?: continue
            items.add(rule)
            if (rule.id > maxId) maxId = rule.id
        }
        nextId = maxId + 1
    }

    fun all(): List<BlockRule> = synchronized(lock) { items.map { it.copy() } }

    fun find(id: Long): BlockRule? = synchronized(lock) {
        items.firstOrNull { it.id == id }?.copy()
    }

    /** 返回落库后的 id。pattern 或 kind 变了就是另一条规则，整条替换而不是就地改。 */
    fun upsert(rule: BlockRule): Long {
        val id = synchronized(lock) {
            val at = items.indexOfFirst { it.id == rule.id }
            val saved = if (at >= 0) {
                rule.copy(id = items[at].id).also { items[at] = it }
            } else {
                rule.copy(id = nextId++).also { items.add(it) }
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

    fun update(id: Long, mutate: (BlockRule) -> Unit) {
        synchronized(lock) {
            val target = items.firstOrNull { it.id == id } ?: return
            mutate(target)
            persistLocked()
        }
        changes.notifyChanged()
    }

    /** 命中一条启用中的规则：整号优先，其次最长号段——更具体的那条说了算。 */
    fun match(rawNumber: String): BlockRule? {
        val variants = NumberNormalizer.queryVariants(rawNumber)
        if (variants.isEmpty()) return null
        return synchronized(lock) {
            items.asSequence()
                .filter { it.enabled && RulePatterns.matches(it, variants) }
                .sortedWith(
                    compareByDescending<BlockRule> { it.kind == BlockRule.Kind.NUMBER }
                        .thenByDescending { it.pattern.length }
                )
                .firstOrNull()
                ?.copy()
        }
    }

    /**
     * 订阅判定拦截后自动学习（保存为策略）。只写整号：要不要拦一整批号段是用户的判断，
     * 应用不能从一个号码替他推断出「这是集团号段」。
     *
     * 已有规则覆盖这个号码就不重复写；写满上限时只淘汰自动学来的旧条目，
     * 用户手写的规则永远不会被自动删掉。
     */
    fun learn(rawNumber: String, sourceName: String, nowMillis: Long): Boolean {
        val pattern = RulePatterns.canonical(rawNumber, BlockRule.Kind.NUMBER) ?: return false
        if (match(rawNumber) != null) return false
        synchronized(lock) {
            if (items.any { it.kind == BlockRule.Kind.NUMBER && it.pattern == pattern }) return false
            if (items.size >= MAX_RULES && !evictLearnedLocked()) return false
            items.add(
                BlockRule(
                    id = nextId++,
                    pattern = pattern,
                    kind = BlockRule.Kind.NUMBER,
                    learnedFrom = sourceName,
                    createdAt = nowMillis,
                )
            )
            persistLocked()
        }
        changes.notifyChanged()
        return true
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

    private fun evictLearnedLocked(): Boolean {
        val oldest = items.filter { it.learnedFrom.isNotBlank() }.minByOrNull { it.createdAt } ?: return false
        items.remove(oldest)
        return true
    }

    private fun persistLocked() {
        writer.write { snapshotLocked() }
    }

    private fun snapshotLocked(): String {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return array.toString()
    }

    private fun toJson(rule: BlockRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("pattern", rule.pattern)
        put("kind", rule.kind.name)
        put("enabled", rule.enabled)
        put("note", rule.note)
        put("learnedFrom", rule.learnedFrom)
        put("createdAt", rule.createdAt)
    }

    private fun fromJson(obj: JSONObject): BlockRule? {
        val pattern = obj.optionalString("pattern")
        if (pattern.isBlank()) return null
        val kind = runCatching { BlockRule.Kind.valueOf(obj.optionalString("kind")) }
            .getOrDefault(BlockRule.Kind.NUMBER)
        return BlockRule(
            id = obj.optLong("id"),
            pattern = pattern,
            kind = kind,
            enabled = obj.optBoolean("enabled", true),
            note = obj.optionalString("note"),
            learnedFrom = obj.optionalString("learnedFrom"),
            createdAt = obj.optLong("createdAt"),
        )
    }

    private companion object {
        const val FILE = "block_rules.json"
        const val MAX_RULES = 2000
    }
}
