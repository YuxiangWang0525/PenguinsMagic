package me.riverfrozer.penguinsmagic.screening

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread
import me.riverfrozer.penguinsmagic.data.Repositories

/**
 * 开机与升级覆盖安装后复查一次拦截资格。
 *
 * Why: 厂商系统在重启后可能把「默认来电筛选应用」改回自带拨号，用户不会察觉，只是骚扰电话
 * 又开始响了。这里能查到的就提醒，查不到的一律闭嘴——在没实现角色接口的系统上报「资格丢失」
 * 属于误报，比不报更糟。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        Repositories.init(appContext)
        // 广播接收器的存活窗口只有几秒，检查放线程里做，用 goAsync 保住这次接收
        val pendingResult = goAsync()
        thread {
            try {
                val prefs = Repositories.prefs
                val now = System.currentTimeMillis()
                val lost = prefs.blockingEnabled &&
                    ScreeningRole.isAvailable(appContext) &&
                    !ScreeningRole.isHeld(appContext)
                if (lost && now - prefs.lastBootReminderAt >= REMINDER_COOLDOWN_MS) {
                    prefs.lastBootReminderAt = now
                    BlockNotifier.notifyAuthorizationNeeded(appContext)
                }
                Repositories.flushAll()
            } catch (failure: Exception) {
                // 开机时进程刚起，这里出任何问题都不该拖垮随后的来电筛查
                Log.w(TAG, "boot recheck failed", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        /** 最多一天提醒一次：开机、升级可能连着送到多个广播。 */
        const val REMINDER_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }
}
