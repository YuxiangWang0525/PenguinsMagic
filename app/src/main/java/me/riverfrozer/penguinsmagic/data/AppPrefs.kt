package me.riverfrozer.penguinsmagic.data

import android.content.Context

/** 顶栏拦截开关，服务与界面读同一份。 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(SECTION, Context.MODE_PRIVATE)

    var blockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCKING, value).apply()
        }

    /** 系统最近一次真的把来电交给本服务的时间戳，定制系统授权不走角色接口，只能靠它证明生效。 */
    var lastScreenedAt: Long
        get() = prefs.getLong(KEY_LAST_SCREENED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SCREENED, value).apply()
        }

    /** 系统的「默认来电筛选应用变更」广播给出的结论；null 表示还没收到过。 */
    var screeningConfirmedBySystem: Boolean?
        get() = if (prefs.contains(KEY_SCREENING_CONFIRMED)) prefs.getBoolean(KEY_SCREENING_CONFIRMED, false) else null
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_SCREENING_CONFIRMED) else editor.putBoolean(KEY_SCREENING_CONFIRMED, value)
            editor.apply()
        }

    /** 开机复查上一次发出「资格丢失」提醒的时间，避免每次广播都弹一条。 */
    var lastBootReminderAt: Long
        get() = prefs.getLong(KEY_BOOT_REMINDER_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_BOOT_REMINDER_AT, value).apply()
        }

    /** 电池优化弹窗只主动问一次，之后交给关于页那两行状态。 */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BATTERY_PROMPT, value).apply()
        }

    /** 连锁封禁：拦下一个号码后短时间内拒接所有陌生号码。默认关，误拦的代价由用户自己决定要不要担。 */
    var chainBanEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHAIN_BAN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CHAIN_BAN, value).apply()
        }

    /** 连锁封禁的总时长上限（分钟），从触发那一刻算起，延长多少次都不能越过它。 */
    var chainBanMaxMinutes: Int
        get() = prefs.getInt(KEY_CHAIN_MAX, ChainBanPolicy.DEFAULT_MAX_MINUTES)
        set(value) {
            prefs.edit()
                .putInt(KEY_CHAIN_MAX, value.coerceIn(ChainBanPolicy.MIN_MAX_MINUTES, ChainBanPolicy.MAX_MAX_MINUTES))
                .apply()
        }

    private companion object {
        const val SECTION = "settings"
        const val KEY_BLOCKING = "blocking_enabled"
        const val KEY_LAST_SCREENED = "last_screened_at"
        const val KEY_SCREENING_CONFIRMED = "screening_confirmed"
        const val KEY_BOOT_REMINDER_AT = "boot_reminder_at"
        const val KEY_BATTERY_PROMPT = "battery_prompt_shown"
        const val KEY_CHAIN_BAN = "chain_ban_enabled"
        const val KEY_CHAIN_MAX = "chain_ban_max_minutes"
    }
}
