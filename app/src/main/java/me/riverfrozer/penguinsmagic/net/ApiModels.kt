package me.riverfrozer.penguinsmagic.net

enum class FailureKind {
    /** 连不上、DNS 失败、超时——降级放行，不把它当成「号码干净」。 */
    NETWORK,

    /** 400，调用方缺陷，重试无意义。 */
    BAD_REQUEST,

    /** 401/403，必须用户重新配置或联系管理员。 */
    AUTH,

    /** 429，退避到下一个 UTC 整点。 */
    RATE_LIMITED,

    /** 5xx，短暂退避后沿用缓存。 */
    SERVER,

    /** 响应不是预期信封。 */
    PROTOCOL,
}

data class Failure(val kind: FailureKind, val httpCode: Int, val message: String)

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failed(val failure: Failure) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value
}

data class Health(
    val name: String,
    val version: String,
    val database: String,
    val tokenHeader: String,
)
