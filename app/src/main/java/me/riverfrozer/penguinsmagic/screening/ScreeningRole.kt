package me.riverfrozer.penguinsmagic.screening

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 「来电筛选应用」角色，即系统是否会把来电交给本服务。 */
object ScreeningRole {

    private fun manager(context: Context): RoleManager? =
        context.getSystemService(RoleManager::class.java)

    fun isAvailable(context: Context): Boolean =
        manager(context)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true

    fun isHeld(context: Context): Boolean =
        manager(context)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    fun requestIntent(context: Context): Intent? =
        manager(context)?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)

    /**
     * 清单里服务的 `android:permission` 必须是 BIND_SCREENING_SERVICE，写成别的名字（哪怕只是
     * 名字听起来更贴切的 BIND_CALL_SCREENING，平台上并没有这个权限）时角色资格检查直接不通过：
     * 授权对话框静默返回、telecom 也绑不上服务，症状和「厂商系统不支持」一模一样。
     */
    @Suppress("DEPRECATION")
    fun isProperlyDeclared(context: Context): Boolean {
        val serviceInfo = runCatching {
            context.packageManager.getServiceInfo(
                ComponentName(context, SpamCallScreeningService::class.java),
                0,
            )
        }.getOrNull()
        return serviceInfo?.permission == Manifest.permission.BIND_SCREENING_SERVICE
    }
}
