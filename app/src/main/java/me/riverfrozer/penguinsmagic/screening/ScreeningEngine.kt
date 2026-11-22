package me.riverfrozer.penguinsmagic.screening

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.riverfrozer.penguinsmagic.data.BlockInfo
import me.riverfrozer.penguinsmagic.data.BlockRule
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.ProbeStatus
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.data.RuleInfo
import me.riverfrozer.penguinsmagic.data.Subscription
import me.riverfrozer.penguinsmagic.net.ApiResult
import me.riverfrozer.penguinsmagic.net.BlockApi
import me.riverfrozer.penguinsmagic.net.Failure

/** 一通来电的筛查结论。 */
data class ScreenOutcome(
    val blocked: Boolean,
    val marked: Boolean,
    val info: BlockInfo?,
    val displayNumber: String,
    val sourceName: String,
    val fromCache: Boolean,
    val networkProbes: Int,
    val failures: List<Failure>,
    val timedOut: Boolean,
)

private typealias Decision = Pair<Subscription, BlockInfo>

/**
 * 先吃缓存，再向所有已启用的订阅并行问一次 getBlockInfo；任一命中即拦截。
 * 全部失败或超时都按放行处理，绝不因为服务端抖动误挂正常来电。
 */
class ScreeningEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun screen(
        rawNumber: String,
        localRule: BlockRule?,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): ScreenOutcome {
        val display = NumberNormalizer.forDisplay(rawNumber)
        val variants = NumberNormalizer.queryVariants(rawNumber)
        if (variants.isEmpty()) return pass(display)

        // 用户自己写下的规则先判：不花配额、不等网络，也不受服务端改分类的影响
        if (localRule != null) return localBlock(display, localRule, System.currentTimeMillis())

        val now = System.currentTimeMillis()
        val subscriptions = Repositories.subscriptions.queryable(now)
        if (subscriptions.isEmpty()) return pass(display)

        val cached = scanCache(subscriptions, variants, now)
        cached.blocked?.let {
            return settle(it, display, fromCache = true, probes = 0, failures = emptyList(), timedOut = false)
        }

        if (cached.pending.isEmpty()) {
            val decision = cached.marked ?: (subscriptions.first().first to cleanPass(variants))
            return settle(decision, display, fromCache = true, probes = 0, failures = emptyList(), timedOut = false)
        }

        val deadline = now + budgetMs
        val results = Channel<ProbeResult>(Channel.UNLIMITED)
        val group = Job()
        val api = BlockApi(BlockApi.LIVE_CONNECT_MS, BlockApi.LIVE_READ_MS)
        cached.pending.forEach { (subscription, token) ->
            scope.launch(group) { results.trySend(probeOne(api, subscription, token, variants, now)) }
        }

        val collected = ArrayList<Decision>()
        val failures = ArrayList<Failure>()
        var blocked: Decision? = null
        var marked = cached.marked
        var timedOut = false
        try {
            for (i in 0 until cached.pending.size) {
                if (blocked != null) break
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    timedOut = true
                    break
                }
                // 超时说明这一批查询没能在预算内回来，剩下的按原样放行
                val result = withTimeoutOrNull(remaining) { results.receive() }
                if (result == null) {
                    timedOut = true
                    break
                }
                val info = result.info
                when {
                    info != null -> {
                        collected += result.subscription to info
                        if (info.blocked) blocked = result.subscription to info
                        else if (marked == null && isMarked(info)) marked = result.subscription to info
                    }
                    result.failure != null -> failures += result.failure
                }
            }
        } finally {
            group.cancel("screening finished")
            results.close()
        }

        val decision = blocked
            ?: marked
            ?: (subscriptions.first().first to cleanPass(variants))
        return settle(
            decision = decision,
            displayNumber = display,
            fromCache = false,
            probes = collected.size,
            failures = failures.toList(),
            timedOut = timedOut,
        )
    }

    private class ProbeResult(
        val subscription: Subscription,
        val info: BlockInfo?,
        val failure: Failure?,
    )

    private class CacheScan(
        val blocked: Decision?,
        val marked: Decision?,
        val pending: List<Pair<Subscription, String>>,
    )

    private fun scanCache(
        subscriptions: List<Pair<Subscription, String>>,
        variants: List<String>,
        now: Long,
    ): CacheScan {
        var blocked: Decision? = null
        var marked: Decision? = null
        val pending = ArrayList<Pair<Subscription, String>>()
        for ((subscription, token) in subscriptions) {
            val cached = variants.firstNotNullOfOrNull {
                Repositories.cache.get(subscription.id, it, now)
            }
            when {
                cached == null -> pending += subscription to token
                cached.blocked -> blocked = blocked ?: (subscription to cached)
                marked == null && isMarked(cached) -> marked = subscription to cached
            }
        }
        return CacheScan(blocked, marked, pending)
    }

    private fun probeOne(
        api: BlockApi,
        subscription: Subscription,
        token: String,
        variants: List<String>,
        now: Long,
    ): ProbeResult {
        var lastFailure: Failure? = null
        for (variant in variants) {
            when (val result = api.blockInfo(subscription.baseUrl, token, subscription.headerName, variant)) {
                is ApiResult.Success -> {
                    val info = result.value
                    // 保存为策略的订阅：拦截结论交给 settle 转成本机规则，这里不再占一份缓存
                    if (!(info.blocked && subscription.learnBlocked)) {
                        Repositories.cache.put(subscription.id, info, now)
                    }
                    ProbeStatus.apply(subscription.id, info, null, now)
                    // 主写法没查到号码时，再试 +86 的另一种书写
                    if (info.blocked || info.inDatabase) return ProbeResult(subscription, info, null)
                }
                is ApiResult.Failed -> {
                    // 同一个服务换一种写法也是同样的错，直接结束这个订阅的查询
                    ProbeStatus.apply(subscription.id, null, result.failure, now)
                    lastFailure = result.failure
                    break
                }
            }
        }
        return ProbeResult(subscription, null, lastFailure)
    }

    private fun settle(
        decision: Decision,
        displayNumber: String,
        fromCache: Boolean,
        probes: Int,
        failures: List<Failure>,
        timedOut: Boolean,
    ): ScreenOutcome {
        val (subscription, info) = decision
        val blocked = info.blocked
        val marked = !blocked && isMarked(info)
        if (blocked || marked) {
            Repositories.history.record(displayNumber, info, subscription.name, System.currentTimeMillis())
        }
        // 保存为策略：这条订阅判下的拦截转成本机规则，下次同一个号码不再花配额去问
        if (blocked && subscription.learnBlocked) {
            val learnedAt = System.currentTimeMillis()
            // 学不进去（比如用户手动停用了同号规则）就退回缓存，否则每通来电都要重新问一次
            if (!Repositories.rules.learn(displayNumber, subscription.name, learnedAt)) {
                Repositories.cache.put(subscription.id, info, learnedAt)
            }
        }
        return ScreenOutcome(
            blocked = blocked,
            marked = marked,
            info = info,
            displayNumber = displayNumber,
            sourceName = subscription.name,
            fromCache = fromCache,
            networkProbes = probes,
            failures = failures,
            timedOut = timedOut,
        )
    }

    /** 本机规则命中：历史与通知都要能看出「这是你自己定的规则，不是服务商判的」。 */
    private fun localBlock(displayNumber: String, rule: BlockRule, now: Long): ScreenOutcome =
        manualBlock(displayNumber, REASON_LOCAL_RULE, LOCAL_RULE_TYPE, rule.note, rule.sourceLabel, now)

    /**
     * 连锁封禁命中：窗口里的陌生号码一律拒接，不查订阅、不花配额。
     * 第二个标签写上触发这次封禁的号码，用户才知道自己是被谁连累的。
     */
    fun chainBlock(rawNumber: String, now: Long): ScreenOutcome = manualBlock(
        displayNumber = NumberNormalizer.forDisplay(rawNumber),
        reason = REASON_CHAIN_BAN,
        typeName = CHAIN_BAN_TYPE,
        detail = Repositories.chain.triggerNumber(),
        sourceName = CHAIN_BAN_TYPE,
        now = now,
    )

    private fun manualBlock(
        displayNumber: String,
        reason: String,
        typeName: String,
        detail: String,
        sourceName: String,
        now: Long,
    ): ScreenOutcome {
        val info = BlockInfo(
            number = displayNumber,
            hit = true,
            action = "block",
            inDatabase = true,
            reason = reason,
            rule = RuleInfo(
                typeId = 0,
                typeName = typeName,
                blockWholeType = false,
                subtagId = 0,
                subtagName = detail,
                subtagKind = "",
                brand = "",
                scamCategory = "",
            ),
            quota = null,
        )
        Repositories.history.record(displayNumber, info, sourceName, now)
        return ScreenOutcome(
            blocked = true,
            marked = false,
            info = info,
            displayNumber = displayNumber,
            sourceName = sourceName,
            fromCache = false,
            networkProbes = 0,
            failures = emptyList(),
            timedOut = false,
        )
    }

    private fun pass(displayNumber: String) = ScreenOutcome(
        blocked = false,
        marked = false,
        info = null,
        displayNumber = displayNumber,
        sourceName = "",
        fromCache = false,
        networkProbes = 0,
        failures = emptyList(),
        timedOut = false,
    )

    private fun isMarked(info: BlockInfo) =
        !info.blocked && info.inDatabase && info.reason == REASON_NOT_ENABLED

    private fun cleanPass(variants: List<String>) = BlockInfo(
        number = variants.first(),
        hit = false,
        action = "pass",
        inDatabase = false,
        reason = "not_in_database",
        rule = null,
        quota = null,
    )

    companion object {
        const val REASON_NOT_ENABLED = "in_database_but_not_enabled"
        const val REASON_LOCAL_RULE = "local_rule"
        const val REASON_CHAIN_BAN = "chain_ban"
        const val LOCAL_RULE_TYPE = "本机规则"
        const val CHAIN_BAN_TYPE = "连锁封禁"
        const val DEFAULT_BUDGET_MS = 2500L
    }
}
