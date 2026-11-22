package me.riverfrozer.penguinsmagic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 可空字符串字段的读取。
 *
 * 这里守的是一条单元测试原本抓不到的差异：android.jar 里 `optString("scamCategory")`
 * 对显式 null 返回字符串 "null"，JVM 那份返回空串，所以真机上第二个标签永远写着 null
 * 而测试全绿。[optionalString] 把两种写法都收敛成空串，界面无需再猜这是品牌还是异常。
 */
class JsonTextTest {

    @Test
    fun `explicit json null is blank, not the word null`() {
        val obj = JSONObject("""{"brand":null,"subtagKind":"brand"}""")
        assertEquals("", obj.optionalString("brand"))
        assertEquals("brand", obj.optionalString("subtagKind"))
    }

    @Test
    fun `a stale word null left in a saved file reads as blank`() {
        val obj = JSONObject("""{"scamCategory":"null","typeName":" 广告推销 "}""")
        assertEquals("", obj.optionalString("scamCategory"))
        assertEquals("广告推销", obj.optionalString("typeName"))
    }

    @Test
    fun `absent key is blank`() {
        assertEquals("", JSONObject("""{}""").optionalString("note"))
    }

    @Test
    fun `label falls through to brand when the scam field is blank`() {
        val rule = RuleInfo(
            typeId = 1,
            typeName = "广告推销",
            blockWholeType = false,
            subtagId = 2,
            subtagName = "快递物流",
            subtagKind = "brand",
            brand = "快递物流",
            scamCategory = JSONObject("""{"scamCategory":null}""").optionalString("scamCategory"),
        )
        assertEquals("快递物流", rule.detailLabel)
    }
}
