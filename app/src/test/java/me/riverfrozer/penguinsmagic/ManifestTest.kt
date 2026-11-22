package me.riverfrozer.penguinsmagic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单里来电筛选服务的声明。
 *
 * 这一项必须有测试：android:permission 写错成别的名字（比如听起来更顺手的 BIND_CALL_SCREENING，
 * 平台上并没有这个权限）时，编译、打包、安装全部照常，角色资格检查却判定不合格，
 * 表现是「授权框一闪而过」和「拦截永远不生效」，从日志外完全看不出是自己有问题。
 */
class ManifestTest {

    private val manifest: String by lazy {
        // 单元测试的工作目录是模块目录，从 Android Studio 跑时可能是项目根目录
        listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("找不到 AndroidManifest.xml，测试的工作目录不对：${File(".").absolutePath}")
    }

    @Test
    fun `screening service is guarded by the permission telecom actually holds`() {
        assertTrue(
            "来电筛选服务必须由 BIND_SCREENING_SERVICE 保护，否则系统既不会绑定也不会授权",
            manifest.contains("android:permission=\"android.permission.BIND_SCREENING_SERVICE\""),
        )
    }

    @Test
    fun `screening service is not guarded by a made up permission name`() {
        assertTrue("BIND_CALL_SCREENING 不是平台权限", "BIND_CALL_SCREENING" !in manifest)
    }

    @Test
    fun `screening service is exported with the telecom action and its own class name`() {
        val service = manifest.substringAfter("<service").substringBefore("</service>")
        assertEquals(".screening.SpamCallScreeningService", service.substringAfter("android:name=\"").substringBefore("\""))
        assertTrue("service 必须 exported，系统才绑得到", Regex("android:exported=\"true\"").containsMatchIn(service))
        assertTrue(
            "intent-filter 的 action 必须是 android.telecom.CallScreeningService",
            service.contains("android:name=\"android.telecom.CallScreeningService\""),
        )
    }

    @Test
    fun `runtime permissions needed by the flow are all declared`() {
        listOf(
            "android.permission.INTERNET",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_CONTACTS",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach {
            assertTrue("清单缺少 $it", manifest.contains("<uses-permission android:name=\"$it\""))
        }
    }

    /**
     * 后台活着与开机复查靠这三项：漏一项照样编译、照样装机，只是拦截在某个时刻悄悄失效。
     */
    @Test
    fun `background and boot permissions are declared`() {
        listOf(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ).forEach {
            assertTrue("清单缺少 $it", manifest.contains("<uses-permission android:name=\"$it\""))
        }
    }

    @Test
    fun `boot receiver is exported and listens for boot completion`() {
        val receiver = manifest.substringAfter("<receiver").substringBefore("</receiver>")
        assertTrue("receiver 必须 exported，否则开机广播根本不投递", receiver.contains("android:exported=\"true\""))
        assertTrue(".screening.BootReceiver" in receiver)
        assertTrue(
            "只收 BOOT_COMPLETED：重启或升级后没人复查资格，失效是完全看不出来的那种",
            receiver.contains("android:name=\"android.intent.action.BOOT_COMPLETED\""),
        )
    }
}
