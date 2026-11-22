package me.riverfrozer.penguinsmagic.data

/**
 * 连锁封禁的时间算术。单独放一个纯对象里，是为了能在 JVM 上把「延长」与「上限」的含义测清楚：
 * 延长 30 秒是在剩余窗口上再加 30 秒，不是从当前时刻重算 30 秒——后者在窗口还剩 80 秒时
 * 等于把封禁缩短；上限则从这次封禁的起点算起，呼入再多次也不能把窗口推过起点 + 上限。
 */
object ChainBanPolicy {

    /** 一个号码被拦下后，陌生号码一律拒接的初始窗口：1 分 30 秒。 */
    const val ARM_MS = 90_000L

    /** 窗口里每再拦下一个陌生号码，封禁延长 30 秒。 */
    const val EXTEND_MS = 30_000L

    /** 「暂时放行」的有效期：够回拨一次，又不会变成永久白名单。 */
    const val TEMP_PASS_MS = 10 * 60_000L

    const val DEFAULT_MAX_MINUTES = 5
    const val MIN_MAX_MINUTES = 1
    const val MAX_MAX_MINUTES = 30

    fun maxMs(minutes: Int): Long = minutes.coerceIn(MIN_MAX_MINUTES, MAX_MAX_MINUTES) * 60_000L

    fun isActive(until: Long, nowMillis: Long): Boolean = until > nowMillis

    /** 真正的拦截触发封禁；已经在封禁中时不能把更长的窗口改短，也不能超过上限。 */
    fun armedUntil(currentUntil: Long, startedAt: Long, nowMillis: Long, maxMs: Long): Long =
        minOf(maxOf(currentUntil, nowMillis + ARM_MS), startedAt + maxMs)

    fun extendedUntil(currentUntil: Long, startedAt: Long, nowMillis: Long, maxMs: Long): Long =
        minOf(maxOf(currentUntil, nowMillis) + EXTEND_MS, startedAt + maxMs)
}