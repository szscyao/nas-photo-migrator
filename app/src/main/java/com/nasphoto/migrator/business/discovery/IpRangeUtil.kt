package com.nasphoto.migrator.business.discovery

/**
 * IPv4 网段计算工具（**纯 JVM，无 Android 依赖**，便于单元测试）。
 *
 * 职责：
 *  - IPv4 字符串 ↔ Int 互转（严格校验，拒绝 "1.2.3", "1.2.3.256", "01.2.3.4" 等）；
 *  - 由本机 IP 推导 /24 的可用主机范围（.1 ~ .254，自动排除网络地址与广播地址）；
 *  - 生成待扫描 IP 列表（排除本机 IP、限制最大数量防止误操作）；
 *  - 手动网段的合法性校验（返回中文错误文案，UI 直接展示）。
 */
object IpRangeUtil {

    /** 单次扫描最多地址数（手动填写 0.0.0.0~255.255.255.255 这种范围会被拦下） */
    const val MAX_SCAN_HOSTS = 1024

    /**
     * 解析 IPv4 字符串为无符号 32 位整数（以 Int 承载）。
     * @return 合法返回 0..Int.MAX_VALUE 的位模式；非法返回 null
     */
    fun parseIpv4(ip: String?): Int? {
        val text = ip?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it in '0'..'9' }) return null
            // 拒绝前导零（"01"），避免与八进制/区域网段语义混淆
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    /** 32 位位模式 → 点分十进制 */
    fun formatIpv4(value: Int): String {
        val v = value
        return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }

    /** IP 字符串是否合法 */
    fun isValidIpv4(ip: String?): Boolean = parseIpv4(ip) != null

    /**
     * 由本机 IP 推导所在 /24 的可用主机范围：
     * 如 `192.168.1.48` → `192.168.1.1` ~ `192.168.1.254`。
     *
     * @return 本机 IP 非法时返回 null
     */
    fun fromLocalCidr24(localIp: String?): IpRange? {
        val local = parseIpv4(localIp) ?: return null
        // 清掉主机位：保留前 24 位
        val network = local and 0xFFFFFF00.toInt()
        val first = network or 1
        val last = network or 0xFE
        return IpRange(startIp = formatIpv4(first), endIp = formatIpv4(last))
    }

    /** 网段包含的地址数量（含起止；非法范围返回 0） */
    fun count(range: IpRange): Int {
        val start = parseIpv4(range.startIp) ?: return 0
        val end = parseIpv4(range.endIp) ?: return 0
        if (end < start) return 0
        val diff = (end.toLong() and 0xFFFFFFFFL) - (start.toLong() and 0xFFFFFFFFL)
        return if (diff + 1 > Int.MAX_VALUE) Int.MAX_VALUE else (diff + 1).toInt()
    }

    /**
     * 展开待探测 IP 列表。
     *
     * @param excludeIps 需要排除的地址（通常是本机 IP）
     * @param maxCount 最大返回数量（防止手动输入超大网段）
     */
    fun expand(
        range: IpRange,
        excludeIps: Set<String> = emptySet(),
        maxCount: Int = MAX_SCAN_HOSTS,
    ): List<String> {
        val start = parseIpv4(range.startIp) ?: return emptyList()
        val end = parseIpv4(range.endIp) ?: return emptyList()
        if (end < start) return emptyList()
        val excluded = excludeIps.mapNotNull { parseIpv4(it) }.toHashSet()
        val result = ArrayList<String>(minOf(maxCount, 256))
        var current = start
        val limit = maxCount.coerceAtLeast(1)
        while (true) {
            if (current !in excluded) {
                result.add(formatIpv4(current))
                if (result.size >= limit) break
            }
            if (current == end) break
            // 0x7FFFFFFF 之后进位会变成负数，但 end 必然 <= -1（255.255.255.255），
            // 这里用无符号比较语义：只在 current != end 时 +1，因此不会溢出。
            if (current == Int.MAX_VALUE) break
            current += 1
        }
        return result
    }

    /**
     * 校验手动填写的起止 IP。
     * @return null 表示合法；否则返回可直接展示给用户的中文错误文案
     */
    fun validateManualRange(startIp: String, endIp: String): String? {
        if (!isValidIpv4(startIp)) return "起始 IP 格式不正确，请填写类似 192.168.1.1 的地址"
        if (!isValidIpv4(endIp)) return "结束 IP 格式不正确，请填写类似 192.168.1.254 的地址"
        val start = parseIpv4(startIp)!!
        val end = parseIpv4(endIp)!!
        if (end < start) return "起始 IP 不能大于结束 IP"
        val total = count(IpRange(startIp.trim(), endIp.trim()))
        if (total > MAX_SCAN_HOSTS) return "扫描范围过大（最多 $MAX_SCAN_HOSTS 个地址），请缩小网段"
        return null
    }
}
