package me.riverfrozer.penguinsmagic.data

import android.content.Context

/** 服务与界面共用同一批仓库实例，历史记录才能即时出现在首页。 */
object Repositories {

    @Volatile
    private var holder: Holder? = null

    fun init(context: Context) {
        if (holder == null) {
            synchronized(this) {
                if (holder == null) holder = Holder(context.applicationContext)
            }
        }
    }

    private fun require(): Holder = holder
        ?: error("Repositories.init must run from Application.onCreate")

    val prefs: AppPrefs get() = require().prefs
    val subscriptions: SubscriptionRepository get() = require().subscriptions
    val history: HistoryRepository get() = require().history
    val cache: DecisionCache get() = require().cache
    val rules: BlockRuleRepository get() = require().rules
    val whitelist: WhitelistRepository get() = require().whitelist
    val chain: ChainBanRepository get() = require().chain

    fun flushAll() {
        holder?.let {
            it.subscriptions.flush()
            it.history.flush()
            it.cache.flush()
            it.rules.flush()
            it.whitelist.flush()
            it.chain.flush()
        }
    }

    private class Holder(context: Context) {
        val prefs = AppPrefs(context)
        val subscriptions = SubscriptionRepository(context)
        val history = HistoryRepository(context)
        val cache = DecisionCache(context)
        val rules = BlockRuleRepository(context)
        val whitelist = WhitelistRepository(context)
        val chain = ChainBanRepository(context, prefs)
    }
}
