package com.nasphoto.migrator.protocol

/**
 * 失败原因分类。所有 NAS 层异常都必须归一到本枚举，便于：
 *  1) 失败列表（异常管理页）按原因分类展示；
 *  2) 引擎判断「可重试 / 不可重试 / 需要暂停任务」；
 *  3) 日志与通知栏文案统一。
 */
enum class NasErrorType(val description: String) {
    CONNECTION_FAILED("连接失败"),
    AUTH_FAILED("认证失败（用户名或密码错误）"),
    HOST_UNREACHABLE("主机不可达（检查 IP/端口/局域网）"),
    TIMEOUT("网络超时"),
    INSUFFICIENT_SPACE("NAS 空间不足"),
    REMOTE_NOT_FOUND("远程路径不存在"),
    REMOTE_PERMISSION_DENIED("远程目录无写入权限"),
    REMOTE_CONFLICT("NAS 端已存在同名文件且大小不一致（冲突，未覆盖未删除本地）"),
    PROTOCOL_UNSUPPORTED("协议不支持该操作"),
    LOCAL_READ_FAILED("读取本地文件失败"),
    VERIFY_SIZE_MISMATCH("校验失败：NAS 端文件大小不一致"),
    VERIFY_MD5_MISMATCH("校验失败：NAS 端 MD5 不一致"),
    LOCAL_DELETE_FAILED("本地原图删除失败"),
    LOCAL_PERMISSION_DENIED("本地存储权限缺失"),
    INTERRUPTED("任务被中断"),
    UNKNOWN("未知错误"),
    ;

    /** 是否值得自动重试（网络类瞬时错误） */
    val retryable: Boolean
        get() = when (this) {
            TIMEOUT, CONNECTION_FAILED, HOST_UNREACHABLE, INTERRUPTED, UNKNOWN -> true
            else -> false
        }

    /** 是否需要暂停整个任务（而非仅跳过当前文件） */
    val shouldPauseTask: Boolean
        get() = when (this) {
            AUTH_FAILED, INSUFFICIENT_SPACE, LOCAL_PERMISSION_DENIED, REMOTE_PERMISSION_DENIED -> true
            else -> false
        }
}

/**
 * 协议层统一异常。SMB / WebDAV 实现必须把各自 SDK 的异常转成本类型，
 * 业务层只依赖 [errorType] 做决策，不感知底层协议。
 */
class NasException(
    val errorType: NasErrorType,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    constructor(errorType: NasErrorType, cause: Throwable) :
        this(errorType, cause.message ?: errorType.description, cause)

    override fun toString(): String = "NasException(type=$errorType, message=${message})"
}

/** 把任意 Throwable 归一化为 [NasErrorType]（协议层实现也可直接抛 [NasException]）。 */
fun Throwable.toNasErrorType(): NasErrorType = when (this) {
    is NasException -> errorType
    is java.net.SocketTimeoutException -> NasErrorType.TIMEOUT
    is java.net.ConnectException -> NasErrorType.CONNECTION_FAILED
    is java.net.UnknownHostException -> NasErrorType.HOST_UNREACHABLE
    is java.net.SocketException -> NasErrorType.CONNECTION_FAILED
    is javax.net.ssl.SSLException -> NasErrorType.CONNECTION_FAILED
    is SecurityException -> NasErrorType.LOCAL_PERMISSION_DENIED
    is java.io.FileNotFoundException -> NasErrorType.REMOTE_NOT_FOUND
    is java.io.IOException -> NasErrorType.CONNECTION_FAILED
    else -> NasErrorType.UNKNOWN
}
