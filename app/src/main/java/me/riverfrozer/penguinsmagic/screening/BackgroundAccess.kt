package me.riverfrozer.penguinsmagic.screening

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 让应用在后台一直活着：电池优化走标准接口，自启动没有接口。
 *
 * 小米的「自启动」开关不向应用暴露任何查询接口，所以这里只负责跳转，界面必须把状态
 * 写成「需要你自己确认」——报一个读得来的布尔值才是真的骗人。
 * 跳转一律先试再退：这些页面在定制系统上不一定存在，只有真跳过去了才知道。
 */
object BackgroundAccess {

    /** 省电策略会不会冻结本应用的后台进程。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 系统弹窗直接向用户要豁免；已经豁免过就返回 null，不再打扰。 */
    fun exemptionDialogIntent(context: Context): Intent? =
        if (isIgnoringBatteryOptimizations(context)) null else dialogIntent(context)

    /** 电池优化设置页（应用列表），弹窗不可用时的退路。 */
    fun batteryListIntent(context: Context): Intent = intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 小米与红米的自启动管理页；其他厂商不提供可跳转的入口。 */
    fun hasAutostartPage(): Boolean = DeviceGuidance.isXiaomi

    /** 依次尝试，返回 true 表示真的跳出去了。 */
    fun openBatterySettings(context: Context): Boolean = launchAny(
        context,
        listOfNotNull(dialogIntent(context), batteryListIntent(context), appDetailsIntent(context)),
    )

    fun openAutostartSettings(context: Context): Boolean = launchAny(
        context,
        listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            appDetailsIntent(context),
        ),
    )

    fun appDetailsIntent(context: Context): Intent =
        intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package=${context.packageName}"))

    private fun intent(action: String): Intent =
        Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun dialogIntent(context: Context): Intent =
        intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package=${context.packageName}"))

    private fun launchAny(context: Context, candidates: List<Intent>): Boolean {
        for (target in candidates) {
            if (runCatching { context.startActivity(target) }.isSuccess) return true
        }
        return false
    }
}
