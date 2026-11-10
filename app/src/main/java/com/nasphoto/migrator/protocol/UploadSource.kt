package com.nasphoto.migrator.protocol

import java.io.InputStream

/**
 * 待上传的本地数据源。
 *
 * 为什么用「流工厂」而不是直接传 InputStream：
 *  - 上传失败重试（最多 3 次）时必须能重新打开流（MediaStore 的 InputStream 不可 reset）；
 *  - 开启 MD5 校验时需要独立再读一遍本地文件；
 *  - 协议层只负责「读多少、写多少」，不关心数据来自 MediaStore 还是 File。
 *
 * 约束：每次调用 [openStream] 都返回一个全新的、位于起点的流，调用方负责 close。
 */
class UploadSource(
    val displayName: String,
    /** 本地文件字节数（来自 MediaStore SIZE 列），用于进度计算与校验基准 */
    val size: Long,
    private val streamProvider: () -> InputStream,
) {
    fun openStream(): InputStream = streamProvider()
}
