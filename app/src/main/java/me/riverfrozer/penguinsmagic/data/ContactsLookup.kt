package me.riverfrozer.penguinsmagic.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * 本机通讯录匹配。
 *
 * 注意 `CallScreeningService` 的行为是反直觉的：没有 READ_CONTACTS 时系统**不会**把联系人的
 * 来电交给筛查，一旦授予了这个权限，联系人的来电也会进来。所以授权之后必须自己认出熟人并
 * 直接放行，否则一个被运营商回收复用的号码命中骚扰库就会把家人朋友挂掉。号码只在本地比对，
 * 不会读出来发给任何接口。
 */
object ContactsLookup {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** 命中返回联系人名字，不在通讯录、没权限或查询失败时返回 null。 */
    fun findName(context: Context, rawNumber: String): String? {
        if (!hasPermission(context)) return null
        val resolver = context.contentResolver
        return lookupCandidates(rawNumber).firstNotNullOfOrNull { queryName(resolver, it) }
    }

    /**
     * 交给通讯录提供程序的写法。原始来电显示放第一个（提供程序自带归一化，多数情况直接命中），
     * 再补上归一化后的 +86 / 国内 11 位两种形式，应对存库写法和来电显示前缀不一致的情况。
     */
    internal fun lookupCandidates(rawNumber: String): List<String> = buildList {
        add(rawNumber.trim())
        addAll(NumberNormalizer.queryVariants(rawNumber))
    }.filter { it.isNotEmpty() }.distinct()

    private fun queryName(resolver: ContentResolver, number: String): String? = runCatching {
        val filter = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        resolver.query(filter, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)?.takeIf { it.isNotBlank() }
            }
    }.getOrNull()
}
