package me.riverfrozer.penguinsmagic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.snackbar.Snackbar
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.databinding.ActivityMainBinding
import me.riverfrozer.penguinsmagic.screening.BackgroundAccess
import me.riverfrozer.penguinsmagic.screening.DeviceGuidance
import me.riverfrozer.penguinsmagic.screening.ScreeningStatus
import me.riverfrozer.penguinsmagic.ui.RoleGuide

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private var applyingSwitchState = false

    /** 用户被引导去授权时置位，回到应用后据此自动把开关打开。 */
    private var awaitingAuthorization = false

    private val summaryChanges: () -> Unit = { updateDrawerSummary() }

    private val closeDrawerOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.drawerLayout.closeDrawers()
        }
    }

    /** 系统「默认来电筛选应用」变更广播：定制系统手动授权后常常只能靠它得知结果。 */
    private val screeningChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isDefault = intent.getBooleanExtra(
                TelecomManager.EXTRA_IS_DEFAULT_CALL_SCREENING_APP,
                false,
            )
            ScreeningStatus.onSystemAnnounced(isDefault)
            refreshBlockingSwitch()
        }
    }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 不看 resultCode：MIUI 之类的授权页点了完成也可能没写角色，只能复查真实状态
            afterAuthorization()
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 拒绝也继续走角色流程：不授予 READ_CONTACTS 时系统本就不会把联系人的来电交进来
            if (ScreeningStatus.isGranted(this)) {
                awaitingAuthorization = false
                applyBlocking(true, R.string.role_granted)
            } else {
                requestScreeningRole()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.historyFragment,
                R.id.subscriptionsFragment,
                R.id.blockRulesFragment,
                R.id.whitelistFragment,
                R.id.aboutFragment,
            ),
            binding.drawerLayout,
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        NavigationUI.setupWithNavController(binding.navView, navController)
        // 选中侧边栏条目后自动收起抽屉
        navController.addOnDestinationChangedListener { _, _, _ ->
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.nav_open_drawer,
            R.string.nav_close_drawer,
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                closeDrawerOnBack.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                closeDrawerOnBack.isEnabled = false
            }
        })
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)

        applySwitchFromPrefs()
        binding.blockingSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingSwitchState) return@setOnCheckedChangeListener
            onBlockingRequested(checked)
        }
    }

    override fun onStart() {
        super.onStart()
        Repositories.history.onChange(summaryChanges)
        Repositories.subscriptions.onChange(summaryChanges)
        updateDrawerSummary()
        ContextCompat.registerReceiver(
            this,
            screeningChangedReceiver,
            IntentFilter(TelecomManager.ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        // 从厂商设置页手动授权后回来，角色接口可能依然报未持有，这里连同证据一起复查
        refreshBlockingSwitch()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(screeningChangedReceiver) }
        Repositories.history.offChange(summaryChanges)
        Repositories.subscriptions.offChange(summaryChanges)
        Repositories.flushAll()
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    /** 顶栏开关与关于页共用同一套授权流程。 */
    fun startAuthorizationFlow() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            // 运行时权限问在角色授权之前：两者一起才算完整的授权链路，MIUI 上也只有这类权限会正常弹窗
            awaitingAuthorization = !ScreeningStatus.isGranted(this)
            if (awaitingAuthorization) applyBlocking(false)
            runtimePermissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (ScreeningStatus.isGranted(this)) {
            applyBlocking(true, R.string.blocking_on)
            return
        }
        awaitingAuthorization = true
        applyBlocking(false)
        requestScreeningRole()
    }

    private fun onBlockingRequested(checked: Boolean) {
        if (!checked) {
            awaitingAuthorization = false
            applyBlocking(false, R.string.blocking_off)
            return
        }
        startAuthorizationFlow()
    }

    /**
     * 角色接口可用就弹系统授权框；MIUI/HyperOS 常常不提供这个入口，或不弹窗直接返回，
     * 这时改为把用户送到厂商的设置页并给出路径说明，而不是只报一句「系统不支持」。
     */
    private fun requestScreeningRole() {
        val intent = DeviceGuidance.roleIntent(this)
        val launched = intent != null && runCatching { roleLauncher.launch(intent) }.isSuccess
        if (!launched) showGuide()
    }

    private fun showGuide() {
        RoleGuide.show(this) { intent ->
            if (runCatching { roleLauncher.launch(intent) }.isFailure) {
                DeviceGuidance.openSettings(this)
            }
        }
    }

    private fun afterAuthorization() {
        if (ScreeningStatus.isGranted(this)) {
            awaitingAuthorization = false
            applyBlocking(true, R.string.role_granted)
        } else {
            // 授权框可能压根没弹出来，别让用户以为点一下就完成了
            showGuide()
        }
    }

    /** 从系统页面返回后复查：手动授权在角色接口上看不出来，靠广播与来电证据补上。 */
    private fun refreshBlockingSwitch() {
        if (awaitingAuthorization && ScreeningStatus.isGranted(this)) {
            awaitingAuthorization = false
            applyBlocking(true, R.string.role_granted)
            return
        }
        applySwitchFromPrefs()
    }

    private fun missingRuntimePermissions(): List<String> = RUNTIME_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun applyBlocking(enabled: Boolean, feedback: Int? = null) {
        Repositories.prefs.blockingEnabled = enabled
        applySwitchFromPrefs()
        if (feedback != null) {
            Snackbar.make(binding.root, feedback, Snackbar.LENGTH_SHORT).show()
        }
        if (enabled) requestBatteryExemption()
    }

    /**
     * 省电策略会冻住后台进程，筛查服务在两次来电之间被杀掉就只能等下次唤醒。
     * 这个弹窗只在第一次真正开启拦截时问一次，之后关于页那行状态自己会说清楚。
     */
    private fun requestBatteryExemption() {
        val prefs = Repositories.prefs
        if (prefs.batteryPromptShown) return
        val intent = BackgroundAccess.exemptionDialogIntent(this) ?: return
        prefs.batteryPromptShown = true
        if (runCatching { startActivity(intent) }.isFailure) {
            Snackbar.make(binding.root, R.string.battery_prompt_manual, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun applySwitchFromPrefs() {
        applyingSwitchState = true
        binding.blockingSwitch.isChecked = Repositories.prefs.blockingEnabled
        applyingSwitchState = false
    }

    private fun updateDrawerSummary() {
        val header = binding.navView.getHeaderView(0)
        val stats = Repositories.history.stats(System.currentTimeMillis())
        header.findViewById<TextView>(R.id.nav_summary).text = getString(
            R.string.nav_header_summary,
            stats.blockedTotal,
            Repositories.subscriptions.all().size,
        )
    }

    private companion object {
        /** 一次问完：缺哪个问哪个，都齐了就直接进角色流程。 */
        val RUNTIME_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            // 通知从 33 起才是运行时权限；在更早的系统上问它等于永远问不出结果
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
