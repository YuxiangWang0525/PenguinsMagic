package me.riverfrozer.penguinsmagic.screening

import android.content.Context
import me.riverfrozer.penguinsmagic.data.Repositories

/**
 * 「拦截到底生效了没有」以 [ScreeningRole.isHeld] 为准，另叠两个旁证：
 * 系统发出的默认筛选应用变更广播，以及筛查服务真的被调用过。
 *
 * 旁证只在角色接口不反映真实状态的系统上兜底——小米实测走的就是标准角色接口，
 * 一旦 [ScreeningRole.isProperlyDeclared] 为 false，两个旁证会永远为空，
 * 那种情况该修的是清单而不是这里。
 */
object ScreeningStatus {

    /** 「被调用过」只在一段时间内算证据，权限被收回后不至于长期显示已生效。 */
    private const val EVIDENCE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    data class State(
        val granted: Boolean,
        val viaRole: Boolean,
        val viaSystemAnnounce: Boolean,
        val lastScreenedAt: Long,
        val roleAvailable: Boolean,
    )

    fun read(context: Context, now: Long = System.currentTimeMillis()): State {
        val prefs = Repositories.prefs
        val held = ScreeningRole.isHeld(context)
        val announced = prefs.screeningConfirmedBySystem
        val lastScreenedAt = prefs.lastScreenedAt
        val hasEvidence = announced != false &&
            lastScreenedAt > 0L && now - lastScreenedAt <= EVIDENCE_TTL_MS
        return State(
            granted = held || announced == true || hasEvidence,
            viaRole = held,
            viaSystemAnnounce = announced == true && !held,
            lastScreenedAt = lastScreenedAt,
            roleAvailable = ScreeningRole.isAvailable(context),
        )
    }

    fun isGranted(context: Context): Boolean = read(context).granted

    /** 系统把一通来电交给我们处理，这是拦截真的生效的最硬证据。 */
    fun recordHandledCall(now: Long = System.currentTimeMillis()) {
        Repositories.prefs.lastScreenedAt = now
    }

    /** 收到 `ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED` 时更新；被收回时把旧证据一并作废。 */
    fun onSystemAnnounced(isDefault: Boolean) {
        val prefs = Repositories.prefs
        prefs.screeningConfirmedBySystem = isDefault
        if (!isDefault) prefs.lastScreenedAt = 0L
    }
}
