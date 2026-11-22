package me.riverfrozer.penguinsmagic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 连锁封禁的窗口与「暂时放行」名单。
 *
 * 落盘而不是只放内存：筛查服务在两次来电之间可能被系统杀掉，重启后窗口若归零，
 * 用户看到的「封禁中」和实际判定就会对不上。
 */
class ChainBanRepository(context: Context, private val prefs: AppPrefs) {

    private val writer = DebouncedWriter(File(context.filesDir, FILE))
    private val lock = Any()
    private var until = 0L
    private var startedAt = 0L
    private var trigger = ""
    private val tempPasses = HashMap<String, Long>()
    private val changes = ChangeNotifier()

    init {
        val stored = writer.readObject()
        until = stored.optLong("until")
        startedAt = stored.optLong("startedAt")
        trigger = stored.optionalString("trigger")
        val passes = stored.optJSONArray("tempPasses") ?: JSONArray()
        for (index in 0 until passes.length()) {
            val obj = passes.optJSONObject(index) ?: continue
            val number = obj.optionalString("number")
            if (number.isNotBlank()) tempPasses[number] = obj.optLong("expiresAt")
        }
    }

    fun isActive(nowMillis: Long): Boolean = synchronized(lock) {
        ChainBanPolicy.isActive(effectiveUntilLocked(), nowMillis)
    }

    fun remainingMs(nowMillis: Long): Long = synchronized(lock) {
        maxOf(0L, effectiveUntilLocked() - nowMillis)
    }

    /** 已经顶到用户设的上限：再拦下去也不会延长，界面要说清楚，别让人以为开关失灵。 */
    fun isCapped(nowMillis: Long): Boolean = synchronized(lock) {
        ChainBanPolicy.isActive(effectiveUntilLocked(), nowMillis) && until >= startedAt + maxMsLocked()
    }

    /** 启动这次封禁的号码，让用户知道是被谁连累的。 */
    fun triggerNumber(): String = synchronized(lock) { trigger }

    fun arm(rawNumber: String, nowMillis: Long) {
        synchronized(lock) {
            // 上一轮已经结束就重新起算，上限跟着新的起点走
            if (!ChainBanPolicy.isActive(effectiveUntilLocked(), nowMillis)) startedAt = nowMillis
            until = ChainBanPolicy.armedUntil(until, startedAt, nowMillis, maxMsLocked())
            trigger = NumberNormalizer.forDisplay(rawNumber)
            persistLocked()
        }
        changes.notifyChanged()
    }

    fun extend(nowMillis: Long) {
        synchronized(lock) {
            until = ChainBanPolicy.extendedUntil(until, startedAt, nowMillis, maxMsLocked())
            persistLocked()
        }
        changes.notifyChanged()
    }

    /** 用户调小上限时立刻收紧当前窗口，也避免之后调大又把已经结束的封禁复活。 */
    fun clampToCap() {
        synchronized(lock) {
            until = effectiveUntilLocked()
            persistLocked()
        }
        changes.notifyChanged()
    }

    /** 关掉开关就立刻解除，不留一个用户看不见的尾巴。 */
    fun clear() {
        synchronized(lock) {
            until = 0L
            startedAt = 0L
            trigger = ""
            persistLocked()
        }
        changes.notifyChanged()
    }

    fun tempAllow(rawNumber: String, nowMillis: Long): Boolean {
        val number = NumberNormalizer.forMatching(rawNumber) ?: return false
        synchronized(lock) {
            tempPasses[number] = nowMillis + ChainBanPolicy.TEMP_PASS_MS
            pruneLocked(nowMillis)
            persistLocked()
        }
        changes.notifyChanged()
        return true
    }

    /** 暂时放行的到期时间；0 表示没有或已过期。 */
    fun tempPassUntil(rawNumber: String, nowMillis: Long): Long {
        val number = NumberNormalizer.forMatching(rawNumber) ?: return 0L
        return synchronized(lock) {
            val expiresAt = tempPasses[number] ?: 0L
            if (expiresAt > nowMillis) expiresAt else 0L
        }
    }

    fun isTempAllowed(rawNumber: String, nowMillis: Long): Boolean =
        tempPassUntil(rawNumber, nowMillis) > 0L

    fun flush() {
        synchronized(lock) { writer.flush { snapshotLocked() } }
    }

    fun onChange(listener: () -> Unit) {
        changes.add(listener)
    }

    fun offChange(listener: () -> Unit) {
        changes.remove(listener)
    }

    private fun maxMsLocked(): Long = ChainBanPolicy.maxMs(prefs.chainBanMaxMinutes)

    /** 读的时候也夹一次上限：用户刚调小滑块，不必等下一次拦截才生效。 */
    private fun effectiveUntilLocked(): Long = minOf(until, startedAt + maxMsLocked())

    private fun pruneLocked(nowMillis: Long) {
        tempPasses.entries.removeAll { it.value <= nowMillis }
    }

    private fun persistLocked() {
        writer.write { snapshotLocked() }
    }

    private fun snapshotLocked(): String {
        val passes = JSONArray()
        tempPasses.forEach { (number, expiresAt) ->
            passes.put(JSONObject().put("number", number).put("expiresAt", expiresAt))
        }
        return JSONObject()
            .put("until", until)
            .put("startedAt", startedAt)
            .put("trigger", trigger)
            .put("tempPasses", passes)
            .toString()
    }

    private companion object {
        const val FILE = "chain_ban.json"
    }
}