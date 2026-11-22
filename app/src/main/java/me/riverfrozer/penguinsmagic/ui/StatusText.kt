package me.riverfrozer.penguinsmagic.ui

import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.Subscription

/** 订阅卡片上的一行状态 + 一行辅助信息。 */
data class SubscriptionStatus(
    val text: String,
    @param:AttrRes val colorAttr: Int,
    val meta: String,
)

object StatusText {

    private val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun time(millis: Long): String = stamp.format(Date(millis))

    /** 连锁封禁剩余时间，m:ss：向上取整到秒，免得最后一秒显示成 0:00 却还在封禁。 */
    fun countdown(millis: Long): String {
        val total = maxOf(0L, (millis + 999L) / 1000L)
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    private fun elapsed(millis: Long, now: Long): String =
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

    fun status(context: Context, subscription: Subscription, now: Long): SubscriptionStatus {
        val text: String
        val colorAttr: Int
        when {
            !subscription.enabled -> {
                text = context.getString(R.string.subscription_disabled)
                colorAttr = ATTR_MUTED
            }
            subscription.lastProbeAt == 0L -> {
                text = context.getString(R.string.subscription_never_tested)
                colorAttr = ATTR_MUTED
            }
            subscription.lastErrorCode == 401 -> {
                text = context.getString(R.string.status_token_invalid)
                colorAttr = ATTR_ERROR
            }
            subscription.lastErrorCode == 403 -> {
                text = context.getString(R.string.status_account_disabled)
                colorAttr = ATTR_ERROR
            }
            subscription.lastErrorCode == 429 && subscription.backoffUntil > now -> {
                text = context.getString(R.string.status_rate_limited, elapsed(subscription.backoffUntil, now))
                colorAttr = ATTR_WARNING
            }
            subscription.lastErrorCode == 400 -> {
                text = context.getString(R.string.status_bad_request)
                colorAttr = ATTR_WARNING
            }
            subscription.lastErrorCode in 500..599 -> {
                text = context.getString(R.string.status_server_error, subscription.lastErrorCode)
                colorAttr = ATTR_WARNING
            }
            subscription.lastErrorCode == 0 || subscription.lastErrorCode == 200 -> {
                text = context.getString(R.string.status_ok)
                colorAttr = ATTR_OK
            }
            else -> {
                text = subscription.lastErrorMessage.ifBlank {
                    context.getString(R.string.status_network_error)
                }
                colorAttr = ATTR_WARNING
            }
        }
        return SubscriptionStatus(text, colorAttr, meta(context, subscription, now))
    }

    private fun meta(context: Context, subscription: Subscription, now: Long): String {
        val parts = mutableListOf(
            context.getString(R.string.meta_header, subscription.headerName),
        )
        if (subscription.lastProbeAt > 0L) {
            parts += context.getString(R.string.meta_last_probe, elapsed(subscription.lastProbeAt, now))
        }
        parts += if (subscription.hourlyLimit > 0) {
            context.getString(R.string.meta_quota, subscription.quotaUsed, subscription.hourlyLimit)
        } else {
            context.getString(R.string.meta_quota_unlimited)
        }
        return parts.joinToString(" · ")
    }

    @ColorInt
    fun colorOf(context: Context, @AttrRes attr: Int): Int =
        MaterialColors.getColor(context, attr, Color.BLACK)

    @AttrRes val ATTR_OK: Int = androidx.appcompat.R.attr.colorPrimary
    @AttrRes val ATTR_WARNING: Int = com.google.android.material.R.attr.colorTertiary
    @AttrRes val ATTR_ERROR: Int = androidx.appcompat.R.attr.colorError
    @AttrRes val ATTR_MUTED: Int = com.google.android.material.R.attr.colorOnSurfaceVariant
}
