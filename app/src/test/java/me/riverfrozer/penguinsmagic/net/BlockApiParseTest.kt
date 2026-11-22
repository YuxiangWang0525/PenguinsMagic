package me.riverfrozer.penguinsmagic.net

import me.riverfrozer.penguinsmagic.data.BlockInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 断言取自 blocker-api.md 的响应结构；文档样例里的号码是真实号码，这里统一换成本应用的示例号 1300000 占位号段。 */
class BlockApiParseTest {

    private val api = BlockApi()

    private fun info(body: String): BlockInfo {
        val result = api.interpret(200, body)
        assertTrue("期望成功，实际 $result", result is ApiResult.Success)
        return api.parseBlockInfo((result as ApiResult.Success).value, "13000001234")
    }

    private fun failureKind(code: Int, body: String): FailureKind =
        (api.interpret(code, body) as ApiResult.Failed).failure.kind

    @Test
    fun `blocked by rule carries brand as the detail`() {
        val info = info(
            """
            {"success":true,"code":"ok","message":null,"data":{
              "number":"13000005678","hit":true,"action":"block","inDatabase":true,
              "reason":"blocked_by_rule",
              "rule":{"typeId":1,"typeName":"广告推销","blockWholeType":false,"subtagId":28,
                      "subtagName":"跨越速运","subtagKind":"brand","brand":"跨越速运",
                      "scamCategory":null,"note":null},
              "quota":{"hourlyLimit":600,"usedInCurrentHour":9,"remaining":591}}}
            """.trimIndent()
        )
        assertTrue(info.blocked)
        assertEquals("13000005678", info.number)
        assertEquals("广告推销", info.rule!!.typeName)
        assertEquals(1, info.rule!!.typeId)
        assertEquals(28, info.rule!!.subtagId)
        assertEquals("跨越速运", info.rule!!.detailLabel)
        assertEquals("", info.rule!!.scamCategory)
        assertFalse(info.rule!!.isScam)
        assertEquals(600, info.quota!!.hourlyLimit)
        assertEquals(9, info.quota!!.usedInCurrentHour)
        assertEquals(591, info.quota!!.remaining!!)
    }

    @Test
    fun `whole type block still reports the scam method`() {
        val info = info(
            """
            {"success":true,"data":{
              "number":"13000000001","hit":true,"action":"block","inDatabase":true,
              "reason":"blocked_by_rule",
              "rule":{"typeId":18,"typeName":"诈骗","blockWholeType":true,"subtagId":19,
                      "subtagName":"冒充公检法","subtagKind":"scam","brand":null,
                      "scamCategory":"冒充公检法","note":"冒充公检法示例"},
              "quota":{"hourlyLimit":600,"usedInCurrentHour":11,"remaining":589}}}
            """.trimIndent()
        )
        assertTrue(info.blocked)
        assertTrue(info.rule!!.isScam)
        assertTrue(info.rule!!.blockWholeType)
        assertEquals("冒充公检法", info.rule!!.detailLabel)
        assertEquals("", info.rule!!.brand)
    }

    @Test
    fun `in database but not enabled is marked, not blocked`() {
        val info = info(
            """
            {"success":true,"data":{
              "number":"13000000002","hit":false,"action":"pass","inDatabase":true,
              "reason":"in_database_but_not_enabled",
              "rule":{"typeId":11,"typeName":"服务通知","blockWholeType":false,"subtagId":12,
                      "subtagName":"银行通知","subtagKind":"brand","brand":"银行通知",
                      "scamCategory":null,"note":null},
              "quota":{"hourlyLimit":0,"usedInCurrentHour":12,"remaining":null}}}
            """.trimIndent()
        )
        assertFalse(info.blocked)
        assertTrue(info.inDatabase)
        assertEquals("in_database_but_not_enabled", info.reason)
        assertEquals("银行通知", info.rule!!.detailLabel)
        assertEquals(0, info.quota!!.hourlyLimit)
        assertNull(info.quota!!.remaining)
    }

    @Test
    fun `number missing from the database has no rule`() {
        val info = info(
            """
            {"success":true,"data":{
              "number":"13000000009","hit":false,"action":"pass","inDatabase":false,
              "reason":"not_in_database","rule":null,
              "quota":{"hourlyLimit":600,"usedInCurrentHour":5,"remaining":595}}}
            """.trimIndent()
        )
        assertFalse(info.blocked)
        assertFalse(info.inDatabase)
        assertEquals("not_in_database", info.reason)
        assertNull(info.rule)
        assertEquals("", info.rule?.detailLabel ?: "")
    }

    @Test
    fun `normalized number falls back to the queried one`() {
        val info = info(
            """
            {"success":true,"data":{"hit":false,"action":"pass","inDatabase":false,
              "reason":"not_in_database","rule":null,"quota":null}}
            """.trimIndent()
        )
        assertEquals("13000001234", info.number)
        assertNull(info.quota)
    }

    @Test
    fun `action block alone is enough to decide`() {
        val info = info(
            """{"success":true,"data":{"number":"13000000001","hit":false,"action":"block",
              "inDatabase":true,"reason":"blocked_by_rule","rule":null,"quota":null}}""".trimIndent()
        )
        assertTrue(info.blocked)
    }

    @Test
    fun `error statuses map to the documented failure kinds`() {
        assertEquals(
            FailureKind.AUTH,
            failureKind(401, """{"success":false,"message":"调用令牌无效或已吊销","data":null}"""),
        )
        assertEquals(
            FailureKind.AUTH,
            failureKind(403, """{"success":false,"message":"账户已被停用","data":null}"""),
        )
        assertEquals(
            FailureKind.RATE_LIMITED,
            failureKind(429, """{"success":false,"message":"已达到每账户每小时 1 次调用上限，请稍后重试"}"""),
        )
        assertEquals(
            FailureKind.BAD_REQUEST,
            failureKind(400, """{"success":false,"message":"电话号码只能包含数字、区号 - 与国际区号 +"}"""),
        )
        assertEquals(FailureKind.PROTOCOL, failureKind(404, """{"success":false,"message":"接口不存在"}"""))
        assertEquals(FailureKind.SERVER, failureKind(500, "not json at all"))
    }

    @Test
    fun `envelope problems are protocol failures`() {
        assertEquals(
            FailureKind.PROTOCOL,
            failureKind(200, """{"success":false,"code":"bad_request","message":"缺少查询参数 number","data":null}"""),
        )
        assertEquals(FailureKind.PROTOCOL, failureKind(200, """{"success":true,"code":"ok","data":null}"""))
        assertEquals(FailureKind.PROTOCOL, failureKind(200, "<html>502</html>"))
    }

    @Test
    fun `health payload defaults the header name`() {
        val health = api.interpret(
            200,
            """{"success":true,"data":{"name":"Chlorine","version":"0.1.0","database":"sqlite","tokenHeader":"x-auth-token"}}"""
        )
        val data = (health as ApiResult.Success).value
        assertEquals("x-auth-token", data.optString("tokenHeader"))
        assertEquals("Chlorine", data.optString("name"))
    }
}
