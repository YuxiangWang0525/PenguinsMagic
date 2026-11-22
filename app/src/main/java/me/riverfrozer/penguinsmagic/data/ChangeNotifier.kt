package me.riverfrozer.penguinsmagic.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 仓库变更通知，负责把回调切回主线程。
 *
 * Why: 拦截跑在 Dispatchers.IO 上，[SubscriptionRepository.update] 与
 * [HistoryRepository.record] 都会在这里通知监听者，而监听者全是在改界面。
 * 直接在工作线程回调就是 CalledFromWrongThreadException（Expected: main，
 * Calling: DefaultDispatcher-worker-N），且只有真的来电话、真的查到结果时才复现。
 * 放在通知这一层统一处理，界面注册回调时不用各自记得切线程——记漏一处就是一个闪退。
 */
class ChangeNotifier {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 已经在主线程时当场回调：用户按下保存后列表要立刻变，不该多等一次消息循环。 */
    fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) invokeAll() else main.post(::invokeAll)
    }

    private fun invokeAll() {
        listeners.forEach { it() }
    }

    private companion object {
        val main = Handler(Looper.getMainLooper())
    }
}
