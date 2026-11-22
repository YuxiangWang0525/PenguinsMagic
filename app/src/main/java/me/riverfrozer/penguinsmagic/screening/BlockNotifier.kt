package me.riverfrozer.penguinsmagic.screening

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.riverfrozer.penguinsmagic.MainActivity
import me.riverfrozer.penguinsmagic.R

/**
 * 拦截结果的通知。系统那一条被 `setSkipNotification(true)` 关掉了，这里是唯一的一条，
 * 所以内容要自己说清楚：类型进标题，号码、品牌或手段、来源订阅进正文。
 *
 * 通知发不出去不影响拦截本身：拒接在 [SpamCallScreeningService] 里已经应答完了。
 */
object BlockNotifier {

    private const val BLOCK_CHANNEL = "block_alerts"
    private const val ALERT_CHANNEL = "auth_alerts"
    private const val ALERT_NOTIFICATION_ID = 401

    /** 同一号码反复来电只更新它那一条通知，不在通知栏里堆成一屏。 */
    fun notifyBlocked(context: Context, outcome: ScreenOutcome) {
        val rule = outcome.info?.rule
        val typeName = rule?.typeName.orEmpty()
        post(
            context = context,
            channelId = channel(
                context,
                BLOCK_CHANNEL,
                R.string.notify_channel_block,
                NotificationManager.IMPORTANCE_LOW,
            ),
            id = outcome.displayNumber.hashCode(),
            title = if (typeName.isBlank()) {
                context.getString(R.string.notify_blocked_generic)
            } else {
                context.getString(R.string.notify_blocked_title, typeName)
            },
            body = blockNotificationBody(outcome.displayNumber, rule?.detailLabel.orEmpty(), outcome.sourceName),
        )
    }

    /** 开机或升级后资格丢了：这是用户不打开应用就永远不会知道的失效。 */
    fun notifyAuthorizationNeeded(context: Context) {
        post(
            context = context,
            channelId = channel(
                context,
                ALERT_CHANNEL,
                R.string.notify_channel_alert,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            id = ALERT_NOTIFICATION_ID,
            title = context.getString(R.string.notify_reauth_title),
            body = context.getString(R.string.notify_reauth_body),
        )
    }

    private fun post(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        body: String,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        // 权限可能在上面那句检查和这一句之间被撤回，SecurityException 只能接住：
        // 拦截已经生效，不能因为提醒发不出去把筛查服务一起带崩
        runCatching { manager.notify(id, notification) }
    }

    private fun channel(context: Context, id: String, nameRes: Int, importance: Int): String {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), importance)
        )
        return id
    }
}

/**
 * 正文按「号码 · 品牌或手段 · 来源订阅」拼，缺哪项就少哪段。
 *
 * 服务端可空字段现在统一读成空串，这里不能让它变成中间多出来的一个孤立分隔符。
 */
internal fun blockNotificationBody(number: String, detail: String, sourceName: String): String =
    listOf(number, detail, sourceName).filter { it.isNotBlank() }.joinToString(" · ")
