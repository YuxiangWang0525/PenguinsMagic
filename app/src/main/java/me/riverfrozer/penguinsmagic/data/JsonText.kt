package me.riverfrozer.penguinsmagic.data

import org.json.JSONObject

/**
 * 读取一个允许为 null 的字符串字段，缺失一律得到空串。
 *
 * Why: Android 自带那份 org.json 的 `optString` 把显式 null 读成字符串 "null"，
 * 单元测试用的 JVM 版本读成空串——所以真机上「诈骗手段」标签永远写着 null，
 * 而 27 个单元测试全绿。判定「这个字段有没有值」不能交给两个实现各自的 null 行为。
 *
 * 缓存与历史文件里还留着这个 bug 期间写下的字面量 "null"，那种写法同样当作没有值。
 */
internal fun JSONObject.optionalString(name: String): String {
    if (isNull(name)) return ""
    val value = opt(name)?.toString()?.trim().orEmpty()
    return if (value == "null") "" else value
}
