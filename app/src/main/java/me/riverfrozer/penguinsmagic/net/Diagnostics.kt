package me.riverfrozer.penguinsmagic.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.riverfrozer.penguinsmagic.data.BlockInfo
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.ProbeStatus
import me.riverfrozer.penguinsmagic.data.Subscription

/**
 * 「测试连接」用：先读 /health 拿请求头名称，再拿一个号码走一遍 getBlockInfo，
 * 把服务端实际会返回的结论直接展示给用户。
 */
class Diagnostics {

    data class Report(
        val health: Health?,
        val healthFailure: Failure?,
        val headerUsed: String,
        val headerFromHealth: Boolean,
        val queriedNumber: String,
        val info: BlockInfo?,
        val lookupFailure: Failure?,
    )

    suspend fun probe(
        subscriptionId: Long?,
        baseUrl: String,
        token: String,
        headerOverride: String,
        sampleNumber: String,
    ): Report = withContext(Dispatchers.IO) {
        val api = BlockApi()
        val healthResult = api.health(baseUrl)
        val discovered = (healthResult as? ApiResult.Success)?.value
        val header = headerOverride.ifBlank { discovered?.tokenHeader ?: Subscription.DEFAULT_HEADER }

        val variants = NumberNormalizer.queryVariants(sampleNumber)
        val number = variants.firstOrNull().orEmpty()
        if (number.isEmpty()) {
            return@withContext Report(
                health = discovered,
                healthFailure = (healthResult as? ApiResult.Failed)?.failure,
                headerUsed = header,
                headerFromHealth = discovered != null && headerOverride.isBlank(),
                queriedNumber = "",
                info = null,
                lookupFailure = Failure(FailureKind.BAD_REQUEST, 0, "测试号码不是可查询的号码"),
            )
        }

        val lookup = api.blockInfo(baseUrl, token, header, number)
        val info = (lookup as? ApiResult.Success)?.value
        val failure = (lookup as? ApiResult.Failed)?.failure
        if (subscriptionId != null) {
            ProbeStatus.apply(subscriptionId, info, failure, System.currentTimeMillis())
        }
        Report(
            health = discovered,
            healthFailure = (healthResult as? ApiResult.Failed)?.failure,
            headerUsed = header,
            headerFromHealth = discovered != null && headerOverride.isBlank(),
            queriedNumber = info?.number ?: number,
            info = info,
            lookupFailure = failure,
        )
    }
}
