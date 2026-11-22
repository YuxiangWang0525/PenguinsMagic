package me.riverfrozer.penguinsmagic.data

/** 一次 getBlockInfo 查询里 `rule` 对象的原样映射。 */
data class RuleInfo(
    val typeId: Int,
    val typeName: String,
    val blockWholeType: Boolean,
    val subtagId: Int,
    val subtagName: String,
    val subtagKind: String,
    val brand: String,
    val scamCategory: String,
) {
    /** 首页要展示的「品牌或诈骗手段」：优先子标签给出的具体名称。 */
    val detailLabel: String
        get() = when {
            scamCategory.isNotBlank() -> scamCategory
            brand.isNotBlank() -> brand
            subtagName.isNotBlank() -> subtagName
            else -> ""
        }

    val isScam: Boolean get() = subtagKind == "scam"
}

data class Quota(
    val hourlyLimit: Int,
    val usedInCurrentHour: Int,
    val remaining: Int?,
)

/** 服务端 `data` 字段。 */
data class BlockInfo(
    val number: String,
    val hit: Boolean,
    val action: String,
    val inDatabase: Boolean,
    val reason: String,
    val rule: RuleInfo?,
    val quota: Quota?,
) {
    val blocked: Boolean get() = hit || action == "block"
}

data class Subscription(
    val id: Long,
    var name: String,
    var baseUrl: String,
    /** 用户手写的请求头名，留空表示用 /health 探测到的 [resolvedHeader]。 */
    var headerOverride: String = "",
    /** /health 的 tokenHeader，缺省 x-auth-token。 */
    var resolvedHeader: String = DEFAULT_HEADER,
    var enabled: Boolean = true,
    /** 拦截成功后把号码写进本机规则（保存为策略），而不是只留在有时效的缓存里。 */
    var learnBlocked: Boolean = false,
    var lastProbeAt: Long = 0L,
    /** 0 表示没有需要用户处理的错误。 */
    var lastErrorCode: Int = 0,
    var lastErrorMessage: String = "",
    var hourlyLimit: Int = 0,
    var quotaUsed: Int = 0,
    /** -1 表示不限制或未知。 */
    var quotaRemaining: Int = -1,
    /** 429 之后退避到的毫秒时间戳（UTC 整点对齐）。 */
    var backoffUntil: Long = 0L,
) {
    val headerName: String get() = headerOverride.ifBlank { resolvedHeader }

    /** 令牌失效/账户停用这类错误必须用户重新配置才能恢复。 */
    val needsReconfig: Boolean get() = lastErrorCode == 401 || lastErrorCode == 403

    companion object {
        const val DEFAULT_HEADER = "x-auth-token"
    }
}

/** 拦截历史里的一行。相同号码在 [HistoryRepository] 的去重窗口内会合并计数。 */
data class BlockRecord(
    val number: String,
    val normalized: String,
    val typeName: String,
    val detail: String,
    val isScam: Boolean,
    val blocked: Boolean,
    val reason: String,
    val sourceName: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
) {
    fun withCount(newCount: Int, newLastSeen: Long): BlockRecord =
        copy(count = newCount, lastSeen = newLastSeen)
}

/**
 * 本机自定义拦截条目：一个整号，或一段号码前缀。
 *
 * 号段是为了拦下营销集团批量买走的一整批号码——13000001234 被拦一次，
 * 用户把 1300000 写成号段，后面同批的号码不用再花订阅配额去问。
 */
data class BlockRule(
    val id: Long,
    /** 归一化并去掉 +86 之后的比对形式，见 [RulePatterns]。 */
    val pattern: String,
    val kind: Kind,
    var enabled: Boolean = true,
    var note: String = "",
    /** 自动学习时记下是哪条订阅判的；空串表示用户手写。 */
    var learnedFrom: String = "",
    var createdAt: Long = 0L,
) {
    enum class Kind { NUMBER, PREFIX }

    /** 历史与通知里的来源标签：号段要让人一眼看出它拦的是一批号码。 */
    val sourceLabel: String get() = if (kind == Kind.PREFIX) "$pattern 号段" else pattern
}

/**
 * 白名单里的一个号码：用户明确说「这个要放进来」，压过本机规则、订阅判定与连锁封禁。
 * 只做整号，不做号段——放行的范围必须由用户一个个号码确认。
 */
data class WhitelistEntry(
    val id: Long,
    /** 归一化并去掉 +86 之后的比对形式，见 [NumberNormalizer.forMatching]。 */
    val number: String,
    var note: String = "",
    var createdAt: Long = 0L,
)
