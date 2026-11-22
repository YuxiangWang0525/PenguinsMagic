package me.riverfrozer.penguinsmagic.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.riverfrozer.penguinsmagic.R
import me.riverfrozer.penguinsmagic.data.ContactsLookup
import me.riverfrozer.penguinsmagic.screening.DeviceGuidance
import me.riverfrozer.penguinsmagic.screening.ScreeningRole

/**
 * 授权引导：能走系统角色对话框就走，走不通就把用户送到对应的系统页面，
 * 而不是像以前那样只提示一句「当前系统未提供来电筛选角色」。
 */
object RoleGuide {

    fun show(context: Context, onUseRoleDialog: (Intent) -> Unit) {
        val roleIntent = DeviceGuidance.roleIntent(context)
        val declared = ScreeningRole.isProperlyDeclared(context)
        val body = StringBuilder(
            context.getString(
                when {
                    !declared -> R.string.guide_body_declared
                    DeviceGuidance.isXiaomi -> R.string.guide_body_xiaomi
                    else -> R.string.guide_body_generic
                },
            ),
        )
        if (declared && !ContactsLookup.hasPermission(context)) {
            body.append(context.getString(R.string.guide_note_contacts))
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.guide_title)
            .setMessage(body)
        if (roleIntent != null) {
            builder.setPositiveButton(R.string.guide_action_role) { _, _ -> onUseRoleDialog(roleIntent) }
        }
        builder.setNeutralButton(R.string.guide_action_settings) { _, _ ->
            if (!DeviceGuidance.openSettings(context)) {
                Toast.makeText(context, R.string.guide_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.show()
    }
}
