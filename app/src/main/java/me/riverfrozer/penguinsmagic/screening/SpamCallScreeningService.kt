package me.riverfrozer.penguinsmagic.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.riverfrozer.penguinsmagic.data.ContactsLookup
import me.riverfrozer.penguinsmagic.data.Repositories

/**
 * 系统来电筛选入口。号码交给 [ScreeningEngine]，命中就静默拒接；
 * 查询失败、超时或开关关闭一律放行，宁可漏拦也不误挂正常来电。
 *
 * 没有 READ_CONTACTS 时系统不会把联系人的来电交给我们；授权了这个权限后联系人的来电也会进来，
 * 于是改成先在本机通讯录里比对，认出熟人直接放行，再问服务端。
 * 判定顺序：白名单/暂时放行 → 本机规则 → 通讯录 → 连锁封禁窗口 → 订阅查询。
 * 用户点名要放行的号码排最前，用户自己写下的拦截规则次之。
 */
class SpamCallScreeningService : CallScreeningService() {

    private val engine = ScreeningEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val answered = AtomicBoolean(false)

    override fun onScreenCall(callDetails: Call.Details) {
        // 只有来电会被采纳，外呼直接不管
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        // 记下系统真的把来电交给我们了：角色接口在某些系统上不反映实际生效状态
        ScreeningStatus.recordHandledCall()
        answered.set(false)

        // 号码来自 tel: URI 的 schemeSpecificPart，Call.Details 上没有直接的 number 属性
        val handle = callDetails.handle
        val rawNumber = if (handle?.scheme == PhoneAccount.SCHEME_TEL) {
            handle.schemeSpecificPart.orEmpty()
        } else {
            ""
        }
        if (!Repositories.prefs.blockingEnabled || rawNumber.isBlank()) {
            respond(callDetails, allowResponse())
            return
        }

        scope.launch {
            val now = System.currentTimeMillis()
            val chain = Repositories.chain
            // 白名单与暂时放行是用户点名要放进来的号码，压过下面所有拦截判定
            if (Repositories.whitelist.contains(rawNumber) || chain.isTempAllowed(rawNumber, now)) {
                respond(callDetails, allowResponse())
                return@launch
            }
            // 用户自己写进本机规则的号码压过通讯录白名单：那是他的指令，不是服务商的判定
            val localRule = Repositories.rules.match(rawNumber)
            // 通讯录比对放在这里而不是主线程回调里：provider 查询耗时不可控，会吃掉 5 秒预算
            if (localRule == null && ContactsLookup.findName(applicationContext, rawNumber) != null) {
                respond(callDetails, allowResponse())
                return@launch
            }
            // 连锁封禁窗口里的陌生号码：既不是白名单也不是联系人，直接拒接并延长窗口
            if (localRule == null && Repositories.prefs.chainBanEnabled && chain.isActive(now)) {
                val chained = engine.chainBlock(rawNumber, now)
                chain.extend(System.currentTimeMillis())
                respond(callDetails, blockResponse())
                BlockNotifier.notifyBlocked(applicationContext, chained)
                return@launch
            }
            val outcome = runCatching { engine.screen(rawNumber, localRule) }.getOrNull()
            if (!isActive) return@launch
            if (outcome != null && outcome.blocked) {
                if (Repositories.prefs.chainBanEnabled) chain.arm(rawNumber, System.currentTimeMillis())
                respond(callDetails, blockResponse())
                // 先应答再发通知：5 秒预算只够用来决定接不接
                BlockNotifier.notifyBlocked(applicationContext, outcome)
            } else {
                respond(callDetails, allowResponse())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Repositories.flushAll()
        super.onDestroy()
    }

    /** 同一通电话只能应答一次，且必须在系统绑定的 5 秒内。 */
    private fun respond(callDetails: Call.Details, response: CallResponse) {
        if (!answered.compareAndSet(false, true)) return
        runCatching { respondToCall(callDetails, response) }
    }

    private fun allowResponse(): CallResponse = CallResponse.Builder().build()

    private fun blockResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(true)
        // 关掉系统那条千篇一律的来电提醒，改由 BlockNotifier 发一条带类型与来源的
        .setSkipNotification(true)
        .build()
}
