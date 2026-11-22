package me.riverfrozer.penguinsmagic.net

import android.net.Uri
import me.riverfrozer.penguinsmagic.data.BlockInfo
import me.riverfrozer.penguinsmagic.data.Quota
import me.riverfrozer.penguinsmagic.data.RuleInfo
import me.riverfrozer.penguinsmagic.data.Subscription
import me.riverfrozer.penguinsmagic.data.optionalString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * 只做拦截需要的两个调用：`/health` 取请求头名称，`/getBlockInfo` 判断号码。
 * 所有失败都带 [FailureKind]，由上层决定退避还是降级放行。
 */
class BlockApi(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_MS,
) {

    fun health(baseUrl: String): ApiResult<Health> =
        request("$baseUrl/health", emptyMap()).map { data ->
            Health(
                name = data.optionalString("name"),
                version = data.optionalString("version"),
                database = data.optionalString("database"),
                tokenHeader = data.optionalString("tokenHeader").ifBlank { Subscription.DEFAULT_HEADER },
            )
        }

    fun blockInfo(
        baseUrl: String,
        token: String,
        headerName: String,
        number: String,
    ): ApiResult<BlockInfo> {
        // Uri.encode 会把 + 转成 %2B；裸写的 + 会被服务端解码成空格
        val url = "$baseUrl/getBlockInfo?number=" + Uri.encode(number)
        return request(url, mapOf(headerName to token)).map { parseBlockInfo(it, number) }
    }

    internal fun parseBlockInfo(data: JSONObject, fallbackNumber: String) = BlockInfo(
        number = data.optionalString("number").ifBlank { fallbackNumber },
        hit = data.optBoolean("hit"),
        action = data.optionalString("action"),
        inDatabase = data.optBoolean("inDatabase"),
        reason = data.optionalString("reason"),
        rule = data.optJSONObject("rule")?.let { parseRule(it) },
        quota = data.optJSONObject("quota")?.let { parseQuota(it) },
    )

    internal fun parseRule(obj: JSONObject) = RuleInfo(
        typeId = obj.optInt("typeId"),
        typeName = obj.optionalString("typeName"),
        blockWholeType = obj.optBoolean("blockWholeType"),
        subtagId = obj.optInt("subtagId"),
        subtagName = obj.optionalString("subtagName"),
        subtagKind = obj.optionalString("subtagKind"),
        brand = obj.optionalString("brand"),
        scamCategory = obj.optionalString("scamCategory"),
    )

    internal fun parseQuota(obj: JSONObject): Quota = Quota(
        hourlyLimit = obj.optInt("hourlyLimit"),
        usedInCurrentHour = obj.optInt("usedInCurrentHour"),
        // 不限制时服务端把 remaining 写成 null
        remaining = if (obj.isNull("remaining")) null else obj.optInt("remaining"),
    )

    private fun <T> ApiResult<JSONObject>.map(transform: (JSONObject) -> T?): ApiResult<T> =
        when (this) {
            is ApiResult.Success -> {
                val mapped = transform(value)
                if (mapped == null) {
                    ApiResult.Failed(Failure(FailureKind.PROTOCOL, 200, "响应体缺少预期字段"))
                } else {
                    ApiResult.Success(mapped)
                }
            }
            is ApiResult.Failed -> this
        }

    private fun request(url: String, headers: Map<String, String>): ApiResult<JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = readTimeoutMs
            opened.requestMethod = "GET"
            opened.useCaches = false
            headers.forEach { (name, value) -> opened.setRequestProperty(name, value) }
            val code = opened.responseCode
            val stream = if (code in 200..299) opened.inputStream else opened.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            interpret(code, body)
        } catch (badUrl: MalformedURLException) {
            ApiResult.Failed(Failure(FailureKind.PROTOCOL, 0, "服务地址无法解析"))
        } catch (io: Exception) {
            ApiResult.Failed(Failure(FailureKind.NETWORK, 0, io.message ?: "网络不可达"))
        } finally {
            connection?.disconnect()
        }
    }

    internal fun interpret(code: Int, body: String): ApiResult<JSONObject> {
        if (code !in 200..299) {
            return ApiResult.Failed(failureFor(code, body))
        }
        val root = try {
            JSONObject(body)
        } catch (invalid: Exception) {
            return ApiResult.Failed(Failure(FailureKind.PROTOCOL, code, "返回的不是 JSON"))
        }
        if (!root.optBoolean("success")) {
            val message = root.optionalString("message")
            return ApiResult.Failed(
                Failure(FailureKind.PROTOCOL, code, message.ifBlank { "服务端拒绝本次查询" })
            )
        }
        val data = root.optJSONObject("data")
            ?: return ApiResult.Failed(Failure(FailureKind.PROTOCOL, code, "响应缺少 data"))
        return ApiResult.Success(data)
    }

    private fun failureFor(code: Int, body: String): Failure {
        val message = try {
            JSONObject(body).optionalString("message")
        } catch (invalid: Exception) {
            ""
        }
        return when (code) {
            400 -> Failure(FailureKind.BAD_REQUEST, code, message.ifBlank { "号码格式不被接受" })
            401 -> Failure(FailureKind.AUTH, code, message.ifBlank { "调用令牌无效或已吊销" })
            403 -> Failure(FailureKind.AUTH, code, message.ifBlank { "账户已被停用" })
            404 -> Failure(FailureKind.PROTOCOL, code, "服务地址上没有 getBlockInfo 接口")
            429 -> Failure(FailureKind.RATE_LIMITED, code, message.ifBlank { "已达每小时调用上限" })
            in 500..599 -> Failure(FailureKind.SERVER, code, message.ifBlank { "服务器内部错误" })
            else -> Failure(FailureKind.PROTOCOL, code, message.ifBlank { "未预期的状态码 $code" })
        }
    }

    companion object {
        const val DEFAULT_CONNECT_MS = 4000
        const val DEFAULT_READ_MS = 6000

        /** 实时来电查询用的紧凑超时。 */
        const val LIVE_CONNECT_MS = 1500
        const val LIVE_READ_MS = 3000
    }
}
