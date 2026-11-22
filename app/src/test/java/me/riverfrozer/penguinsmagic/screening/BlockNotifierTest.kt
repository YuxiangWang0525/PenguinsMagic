package me.riverfrozer.penguinsmagic.screening

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知正文的拼接。
 *
 * 服务端字段现在统一读成空串，正文里不能因此多出一个孤立分隔符，也不能留个空格上去。
 */
class BlockNotifierTest {

    @Test
    fun `every present piece shows up in order`() {
        assertEquals(
            "13000001234 · 快递物流 · 主订阅",
            blockNotificationBody("13000001234", "快递物流", "主订阅"),
        )
    }

    @Test
    fun `missing detail or source leaves no dangling separator`() {
        assertEquals("13000001234", blockNotificationBody("13000001234", "", ""))
        assertEquals("13000001234 · 主订阅", blockNotificationBody("13000001234", "  ", "主订阅"))
    }
}