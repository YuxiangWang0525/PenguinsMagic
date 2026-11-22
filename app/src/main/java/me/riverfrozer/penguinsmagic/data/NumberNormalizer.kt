package me.riverfrozer.penguinsmagic.data

/**
 * 按服务端约定归一化号码：去掉分隔符、校验字符与位数，并为 +86 / 国内 11 位
 * 两种写法生成候选，因为服务端不做国家码归一。
 */
object NumberNormalizer {

    private const val MIN_DIGITS = 5
    private const val MAX_DIGITS = 24
    private val SEPARATORS = charArrayOf(' ', '-', '_', '(', ')', '.', '/')

    /** 归一化后的号码；不可拨号（含字母、+ 位置非法、位数不对）时返回 null。 */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val source = raw.trim()
        val sb = StringBuilder(source.length)
        var hasPlus = false
        for ((index, ch) in source.withIndex()) {
            when {
                ch in SEPARATORS -> Unit
                ch.isDigit() -> sb.append(ch)
                ch == '+' -> {
                    if (index != 0 || hasPlus) return null
                    hasPlus = true
                    sb.append(ch)
                }
                else -> return null
            }
        }
        val digits = sb.count { it.isDigit() }
        if (digits < MIN_DIGITS || digits > MAX_DIGITS) return null
        return sb.toString()
    }

    /**
     * 需要依次查询的写法。主写法就是归一化结果；+86 与国内 11 位互为候选，
     * 只在主写法查不到时才会发起第二次请求。
     */
    fun queryVariants(raw: String?): List<String> {
        val primary = normalize(raw) ?: return emptyList()
        val domestic = primary.removePrefix("+86")
        return when {
            primary.startsWith("+86") && domestic.length == 11 -> listOf(primary, domestic)
            !primary.startsWith("+") && primary.length == 11 && primary[0] == '1' ->
                listOf(primary, "+86$primary")
            else -> listOf(primary)
        }
    }

    /** 去掉 +86 前缀，用于展示更贴近来电显示。 */
    fun forDisplay(raw: String?): String {
        val normalized = normalize(raw) ?: return raw.orEmpty()
        return if (normalized.startsWith("+86") && normalized.length == 14) {
            normalized.substring(3)
        } else {
            normalized
        }
    }

    /** 本机比对用的写法：白名单与本机规则都按它存，+86 与国内写法才能互相对上。 */
    fun forMatching(raw: String?): String? = normalize(raw)?.removePrefix("+86")
}
