package me.riverfrozer.penguinsmagic.screening

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager

/** 厂商系统的来电拦截入口各不相同，这里只负责「角色接口走不通时去哪里」。 */
object DeviceGuidance {

    /** MIUI 与 HyperOS 共用小米的厂商/品牌标识。 */
    val isXiaomi: Boolean by lazy {
        val haystack = listOf(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT, Build.DEVICE, Build.DISPLAY)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        listOf("xiaomi", "redmi", "hongmi", "poco", "mione", "miui", "hyperos").any { it in haystack }
    }

    /**
     * 系统角色授权对话框。角色不可用、厂商没实现，或本包的服务声明不合格时返回 null——
     * 最后这种情况下弹对话框只会静默失败，不如直接把真实原因说清楚。
     */
    fun roleIntent(context: Context): Intent? =
        ScreeningRole.requestIntent(context)
            ?.takeIf { ScreeningRole.isAvailable(context) && ScreeningRole.isProperlyDeclared(context) }

    private fun candidates(context: Context): List<Intent> = listOf(
        // 原生与多数系统：电话设置里能选来电筛选应用
        Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS),
        // 定制系统的「特殊应用权限 / 骚扰拦截」大多能从应用详情页进
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package=${context.packageName}")),
        // 兜底：设置首页，配合文案里的路径说明
        Intent(Settings.ACTION_SETTINGS),
    )

    /** 依次试到能打开为止，返回 true 表示已经跳到某个系统页面。 */
    fun openSettings(context: Context): Boolean {
        for (intent in candidates(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
