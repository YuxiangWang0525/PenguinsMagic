package me.riverfrozer.penguinsmagic.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar
import me.riverfrozer.penguinsmagic.MainActivity
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.ContactsLookup
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.databinding.FragmentAboutBinding
import me.riverfrozer.penguinsmagic.screening.BackgroundAccess
import me.riverfrozer.penguinsmagic.screening.DeviceGuidance
import me.riverfrozer.penguinsmagic.screening.ScreeningRole
import me.riverfrozer.penguinsmagic.screening.ScreeningStatus

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.requestRole.setOnClickListener { requestAuthorization() }
        binding.recheckRole.setOnClickListener { render() }
        binding.openBattery.setOnClickListener {
            openSystemPage(BackgroundAccess.openBatterySettings(requireContext()))
        }
        binding.openAutostart.setOnClickListener {
            openSystemPage(BackgroundAccess.openAutostartSettings(requireContext()))
        }
        binding.clearCache.setOnClickListener {
            val removed = Repositories.cache.clear()
            Snackbar.make(binding.root, getString(R.string.about_cache_cleared, removed), Snackbar.LENGTH_SHORT)
                .show()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val context = requireContext()
        val state = ScreeningStatus.read(context)
        val declared = ScreeningRole.isProperlyDeclared(context)
        val granted = state.granted
        binding.roleStatus.text = when {
            // 角色接口认下来的授权
            state.viaRole -> getString(R.string.about_role_granted)
            // 角色接口查不到，但系统广播或来电证据证明拦截真的在跑
            granted -> getString(R.string.about_role_granted_oem, handledAgo(state.lastScreenedAt))
            // 声明不合格：授权框不会出现，改了设置也没用，跟厂商系统无关
            !declared -> getString(R.string.about_role_not_qualified)
            state.roleAvailable -> getString(R.string.about_role_missing)
            else -> getString(R.string.about_role_manual)
        }
        binding.roleMeta.text = getString(
            R.string.about_role_meta,
            yesNo(state.viaRole),
            yesNo(state.viaSystemAnnounce),
            handledAgo(state.lastScreenedAt),
            yesNo(DeviceGuidance.isXiaomi),
            yesNo(ContactsLookup.hasPermission(context)),
            yesNo(declared),
        )
        binding.roleIcon.setImageResource(if (granted) R.drawable.ic_shield else R.drawable.ic_warning)
        binding.roleIcon.imageTintList = ColorStateList.valueOf(
            StatusText.colorOf(
                context,
                if (granted) StatusText.ATTR_OK else StatusText.ATTR_ERROR,
            )
        )
        binding.requestRole.setText(
            if (state.roleAvailable && declared) R.string.about_role_action else R.string.about_open_settings,
        )

        val storage = Repositories.subscriptions.tokenStorage()
        binding.tokenStorage.text = if (storage.total == 0) {
            getString(R.string.about_token_storage_none)
        } else {
            getString(R.string.about_token_storage, storage.encrypted, storage.inPrivateDir)
        }
        // 有令牌落在私有目录就把这行标黄：这是安全级别的下降，不该混在灰色辅助文字里
        binding.tokenStorage.setTextColor(
            StatusText.colorOf(
                context,
                if (storage.inPrivateDir > 0) StatusText.ATTR_WARNING else StatusText.ATTR_MUTED,
            )
        )
        binding.cacheSize.text = getString(R.string.about_cache_count, Repositories.cache.size())
        renderBackground(context)
        binding.versionText.text = getString(R.string.about_version_body, versionName(), versionCode())
        renderCopyright()
    }

    /** 署名与两条链接；链接色按品牌主色 99CCFF，正文仍是灰色。 */
    private fun renderCopyright() {
        val linkColor = ContextCompat.getColor(requireContext(), R.color.aqua_primary)
        binding.copyrightCodename.apply {
            text = withLink(
                getString(R.string.about_codename_label),
                getString(R.string.about_codename),
                getString(R.string.about_codename_url),
            )
            setLinkTextColor(linkColor)
            movementMethod = LinkMovementMethod.getInstance()
        }
        binding.copyrightGithub.apply {
            text = withLink(
                getString(R.string.about_github_label),
                getString(R.string.about_github_url),
                getString(R.string.about_github_url),
            )
            setLinkTextColor(linkColor)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val today = Calendar.getInstance()
        binding.birthdayText.visibility =
            if (isBirthday(today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))) View.VISIBLE else View.GONE
    }

    private fun withLink(label: String, linkText: String, url: String): SpannableString {
        val text = SpannableString(label + linkText)
        text.setSpan(URLSpan(url), label.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    /**
     * 后台与提醒三行各说各的实话：电池豁免和通知开关问得到系统，自启动开关问不到
     * （平台没有接口），所以那一行写的是「需要你自己确认」，不能猜一个状态出来。
     */
    private fun renderBackground(context: Context) {
        val exempt = BackgroundAccess.isIgnoringBatteryOptimizations(context)
        binding.backgroundStatus.text =
            getString(if (exempt) R.string.about_battery_ok else R.string.about_battery_restricted)
        binding.backgroundStatus.setTextColor(
            StatusText.colorOf(context, if (exempt) StatusText.ATTR_MUTED else StatusText.ATTR_WARNING),
        )

        val canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled()
        binding.notificationStatus.text =
            getString(if (canNotify) R.string.about_notifications_on else R.string.about_notifications_off)
        binding.notificationStatus.setTextColor(
            StatusText.colorOf(context, if (canNotify) StatusText.ATTR_MUTED else StatusText.ATTR_WARNING),
        )

        val hasAutostartPage = BackgroundAccess.hasAutostartPage()
        binding.autostartStatus.text =
            getString(if (hasAutostartPage) R.string.about_autostart_xiaomi else R.string.about_autostart_other)
        binding.openAutostart.visibility = if (hasAutostartPage) View.VISIBLE else View.GONE
    }

    private fun openSystemPage(launched: Boolean) {
        if (!launched) {
            Snackbar.make(binding.root, R.string.guide_settings_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    /** 授权流程统一由 MainActivity 持有，避免两处各建一套 launcher。 */
    private fun requestAuthorization() {
        val activity = requireActivity()
        if (activity is MainActivity) {
            activity.startAuthorizationFlow()
        } else {
            RoleGuide.show(requireContext()) { intent ->
                runCatching { startActivity(intent) }.onFailure { DeviceGuidance.openSettings(requireContext()) }
            }
        }
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    private fun handledAgo(timestamp: Long): String =
        if (timestamp <= 0L) getString(R.string.never) else StatusText.time(timestamp)

    private fun versionName(): String = packageInfo().versionName ?: ""

    private fun versionCode(): Long = PackageInfoCompat.getLongVersionCode(packageInfo())

    @Suppress("DEPRECATION")
    private fun packageInfo() =
        requireContext().packageManager.getPackageInfo(requireContext().packageName, PackageManager.GET_CONFIGURATIONS)
}

/**
 * 彩蛋只在手机本地日期的 10 月 5 日出现。
 * [Calendar.MONTH] 从 0 起算，写成 `month == 10` 会变成 11 月，所以这里用常量并单独测一遍。
 */
internal fun isBirthday(month: Int, dayOfMonth: Int): Boolean =
    month == Calendar.OCTOBER && dayOfMonth == 5
