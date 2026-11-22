package me.riverfrozer.penguinsmagic.data

import me.riverfrozer.penguinsmagic.net.Failure
import me.riverfrozer.penguinsmagic.net.FailureKind

/**
 * 把一次查询的结论回写到订阅状态里：额度、退避、需要重新配置的令牌。
 * 来电查询和手动「测试连接」共用这套逻辑，列表里的状态才和实际一致。
 */
object ProbeStatus {

    private const val HOUR_MS = 60L * 60 * 1000

    /** 429 不带 Retry-After，按文档建议退避到下一个 UTC 整点。 */
    fun nextUtcHour(nowMillis: Long): Long = (nowMillis / HOUR_MS + 1) * HOUR_MS

    fun apply(subscriptionId: Long, info: BlockInfo?, failure: Failure?, now: Long) {
        Repositories.subscriptions.update(subscriptionId) { subscription ->
            subscription.lastProbeAt = now
            if (info != null) {
                subscription.lastErrorCode = 0
                subscription.lastErrorMessage = ""
                subscription.backoffUntil = 0
                info.quota?.let { quota ->
                    subscription.hourlyLimit = quota.hourlyLimit
                    subscription.quotaUsed = quota.usedInCurrentHour
                    subscription.quotaRemaining = quota.remaining ?: -1
                }
            } else if (failure != null) {
                subscription.lastErrorCode = failure.httpCode
                subscription.lastErrorMessage = failure.message
                subscription.backoffUntil = when (failure.kind) {
                    FailureKind.RATE_LIMITED -> nextUtcHour(now)
                    FailureKind.SERVER, FailureKind.NETWORK -> now + COOLDOWN_MS
                    else -> 0L
                }
            }
        }
    }

    private const val COOLDOWN_MS = 60_000L
}
