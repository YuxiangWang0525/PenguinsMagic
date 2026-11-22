package me.riverfrozer.penguinsmagic.data

/**
 * 本机规则的号码比对。
 *
 * 号段按前缀匹配：1300000 拦下 13000001234 这一整批集团号。比对前双方都去掉 +86，
 * 否则同一条规则在「+86130…」与「130…」两种来电写法下只拦得住一种。
 */
object RulePatterns {

    /** 号段最短位数：再短就会把一大片正常号码一起拦掉，那不是用户要的「集团号段」。 */
    const val MIN_PREFIX_DIGITS = 6

    /** 用户输入转成存储用的比对形式；不合法返回 null。 */
    fun canonical(raw: String?, kind: BlockRule.Kind): String? {
        val normalized = NumberNormalizer.forMatching(raw) ?: return null
        if (kind == BlockRule.Kind.PREFIX && normalized.count { it.isDigit() } < MIN_PREFIX_DIGITS) return null
        return normalized
    }

    /** 来电的候选写法里有没有命中这条规则。 */
    fun matches(rule: BlockRule, variants: List<String>): Boolean {
        val candidates = variants.map { it.removePrefix("+86") }
        return when (rule.kind) {
            BlockRule.Kind.NUMBER -> rule.pattern in candidates
            BlockRule.Kind.PREFIX -> candidates.any { it.startsWith(rule.pattern) }
        }
    }
}
