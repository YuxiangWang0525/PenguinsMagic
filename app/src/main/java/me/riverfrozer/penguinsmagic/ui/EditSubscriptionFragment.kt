package me.riverfrozer.penguinsmagic.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.NumberNormalizer
import me.riverfrozer.penguinsmagic.data.Repositories
import me.riverfrozer.penguinsmagic.data.SecureTokenStore
import me.riverfrozer.penguinsmagic.data.Subscription
import me.riverfrozer.penguinsmagic.data.diagnosis
import me.riverfrozer.penguinsmagic.databinding.FragmentEditSubscriptionBinding
import me.riverfrozer.penguinsmagic.net.Diagnostics
import me.riverfrozer.penguinsmagic.screening.ScreeningEngine

/** 添加或编辑一个订阅：服务地址、调用令牌、鉴权头，并可以立刻试查一个号码。 */
class EditSubscriptionFragment : Fragment() {

    private var _binding: FragmentEditSubscriptionBinding? = null
    private val binding get() = _binding!!

    private val diagnostics = Diagnostics()
    private var subscriptionId = SubscriptionsFragment.NEW_SUBSCRIPTION
    private var existing: Subscription? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEditSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subscriptionId = arguments?.getLong(SubscriptionsFragment.EXTRA_ID)
            ?: SubscriptionsFragment.NEW_SUBSCRIPTION
        existing = Repositories.subscriptions.find(subscriptionId)

        binding.urlLayout.helperText = getString(R.string.helper_base_url)
        binding.tokenLayout.helperText = if (existing != null) {
            getString(R.string.token_kept)
        } else {
            getString(R.string.helper_token)
        }

        val loaded = existing
        if (loaded != null) {
            binding.inputName.setText(loaded.name)
            binding.inputUrl.setText(loaded.baseUrl)
            binding.inputHeader.setText(loaded.headerOverride)
            binding.policySwitch.isChecked = loaded.learnBlocked
            binding.deleteSubscription.visibility = View.VISIBLE
        }
        binding.inputSample.setText(SAMPLE_NUMBER)

        binding.testConnection.setOnClickListener { runTest() }
        binding.saveSubscription.setOnClickListener { save() }
        binding.deleteSubscription.setOnClickListener { confirmDelete() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun runTest() {
        val url = normalizedUrl() ?: return reportFieldError(binding.urlLayout, R.string.error_url_invalid)
        val token = currentToken() ?: return reportFieldError(binding.tokenLayout, R.string.token_required)
        val headerOverride = binding.inputHeader.text?.toString()?.trim().orEmpty()
        val sample = binding.inputSample.text?.toString().orEmpty()
        if (sample.isBlank()) {
            return reportFieldError(binding.sampleLayout, R.string.test_no_number)
        }
        if (NumberNormalizer.normalize(sample) == null) {
            return reportFieldError(binding.sampleLayout, R.string.test_number_invalid)
        }

        binding.testConnection.isEnabled = false
        binding.resultCard.visibility = View.VISIBLE
        binding.resultText.setText(R.string.test_running_hint)
        val savedId = if (existing == null) null else subscriptionId
        lifecycleScope.launch {
            val report = try {
                diagnostics.probe(savedId, url, token, headerOverride, sample)
            } finally {
                _binding?.testConnection?.isEnabled = true
            }
            _binding?.resultText?.text = describe(report)
        }
    }

    private fun describe(report: Diagnostics.Report): String = buildString {
        val health = report.health
        when {
            health != null -> appendLine(
                getString(R.string.test_health_ok, health.name, health.version, health.database)
            )
            report.healthFailure != null -> appendLine(
                getString(R.string.test_health_failed, report.healthFailure.message)
            )
        }
        appendLine(
            if (report.headerFromHealth) {
                getString(R.string.test_header_line, report.headerUsed)
            } else {
                getString(R.string.test_header_manual, report.headerUsed)
            }
        )
        val info = report.info
        when {
            info != null -> {
                val verdict = when {
                    info.blocked -> getString(R.string.test_result_hit)
                    info.reason == ScreeningEngine.REASON_NOT_ENABLED ->
                        getString(R.string.test_result_marked)
                    else -> getString(R.string.test_result_pass)
                }
                appendLine(getString(R.string.test_lookup_ok, report.queriedNumber, verdict))
                info.rule?.let { rule ->
                    appendLine(
                        getString(
                            R.string.test_lookup_detail,
                            rule.typeName.ifBlank { "-" },
                            rule.detailLabel.ifBlank { "-" },
                            info.reason,
                        )
                    )
                }
                info.quota?.let { quota ->
                    appendLine(
                        getString(
                            R.string.test_quota,
                            quota.usedInCurrentHour,
                            quota.remaining?.toString() ?: getString(R.string.unlimited),
                        )
                    )
                }
            }
            report.lookupFailure != null -> append(
                getString(R.string.test_lookup_failed, report.lookupFailure.message)
            )
        }
    }.trim()

    private fun save() {
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        val url = normalizedUrl()
        val token = binding.inputToken.text?.toString()?.trim().orEmpty()

        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.error_name_required)
            return
        }
        if (url == null) {
            binding.urlLayout.error = getString(
                if (binding.inputUrl.text.isNullOrBlank()) R.string.error_url_required else R.string.error_url_invalid
            )
            return
        }
        if (token.isBlank() && existing?.let { Repositories.subscriptions.tokenFor(it.id) }.isNullOrEmpty()) {
            binding.tokenLayout.error = getString(R.string.token_required)
            return
        }

        val base = existing ?: Subscription(id = 0L, name = name, baseUrl = url)
        val candidate = base.copy(
            name = name,
            baseUrl = url,
            headerOverride = binding.inputHeader.text?.toString()?.trim().orEmpty(),
            enabled = base.enabled,
            learnBlocked = binding.policySwitch.isChecked,
        )
        when (val outcome = Repositories.subscriptions.upsert(candidate, token.ifBlank { null })) {
            is SecureTokenStore.Save.Failed -> {
                // 异常链摊开在结果卡片里：Keystore 失败的原因分层嵌套，只看最外层等于什么都没看到
                val detail = getString(R.string.token_save_failed, outcome.cause.diagnosis())
                binding.resultCard.visibility = View.VISIBLE
                binding.resultText.text = detail
                showSnackbar(detail)
            }
            is SecureTokenStore.Save.Downgraded -> {
                // 订阅存下来了，但不能让用户以为令牌还在 Keystore 里；留在本页把话说完
                binding.resultCard.visibility = View.VISIBLE
                binding.resultText.text = getString(R.string.token_saved_detail_private, outcome.cause.diagnosis())
                showSnackbar(R.string.subscription_saved_private)
            }
            else -> {
                showSnackbar(R.string.subscription_saved)
                findNavController().popBackStack()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(R.string.subscription_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.subscription_delete) { _, _ ->
                Repositories.subscriptions.delete(subscriptionId)
                showSnackbar(R.string.subscription_removed)
                findNavController().popBackStack()
            }
            .show()
    }

    private fun normalizedUrl(): String? {
        val raw = binding.inputUrl.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        return try {
            val uri = Uri.parse(raw)
            if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                raw.trimEnd('/')
            } else {
                null
            }
        } catch (invalid: Exception) {
            null
        }
    }

    /** 新建时必须填；编辑时留空表示沿用安全存储里的旧令牌。 */
    private fun currentToken(): String? {
        val typed = binding.inputToken.text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed
        val stored = existing?.let { Repositories.subscriptions.tokenFor(it.id) }
        return stored?.takeIf { it.isNotBlank() }
    }

    private fun reportFieldError(layout: TextInputLayout, messageRes: Int) {
        layout.error = getString(messageRes)
    }

    private fun showSnackbar(messageRes: Int) = showSnackbar(getString(messageRes))

    private fun showSnackbar(message: CharSequence) {
        _binding?.let {
            Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val SAMPLE_NUMBER = "13000001234"
    }
}
