package com.iptv.tvplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.LinkedHashSet

/**
 * IPv6 优先的 DNS 解析器（给 Media3 的 OkHttpDataSource 用）。
 *
 * 背景：Android 系统 HttpURLConnection 的 DNS 解析顺序由 ROM 决定，绝大多数盒子
 * 默认把 A（IPv4）排在 AAAA（IPv6）前面，即使宽带 / 运营商已支持 IPv6 也不会用。
 * 本解析器把 AAAA 记录排到 A 前面——OkHttp 会按 [lookup] 返回的顺序逐个尝试连接，
 * IPv6 连不通（无路由 / 运营商不支持）会自动回退到列表里的 IPv4 地址，
 * 从而实现「有 IPv6 优先用、没有才用 IPv4」且不牺牲可用性。
 *
 * 另外做一层「本机是否具备可用 IPv6」检测：纯 IPv4 网络下直接返回系统顺序，
 * 避免每次请求都徒劳先连一把 IPv6（省一次超时）；检测结果带 30 秒缓存，
 * 网络切换后可快速收敛。
 */
class Ipv6PreferredDns(private val context: Context) : Dns {

    companion object {
        private const val CACHE_MS = 30_000L
    }

    @Volatile
    private var cachedHasIpv6 = false
    private var cacheAt = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        // IP 字面量（含 [IPv6]）交给系统解析，无需排序
        val all = InetAddress.getAllByName(hostname)
        if (all.size <= 1) return all.asList()

        // 纯 IPv4 网络：保持系统顺序，避免无谓的 IPv6 连接尝试
        if (!hasIpv6Connectivity()) return all.asList()

        // IPv6 优先：AAAA 全部排到 A 前；每组内部保持系统返回顺序
        // LinkedHashSet 去重，防止个别 ROM 的重复记录
        val ordered = LinkedHashSet<InetAddress>(all.size)
        all.filterIsInstance<Inet6Address>().forEach { ordered.add(it) }
        all.filterIsInstance<Inet4Address>().forEach { ordered.add(it) }
        // 兜底：万一有既非 v4 也非 v6 的记录也别丢
        all.forEach { ordered.add(it) }
        return ordered.toList()
    }

    /** 本机当前是否至少有一个可用的非链路本地 IPv6 地址 */
    private fun hasIpv6Connectivity(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cacheAt < CACHE_MS) return cachedHasIpv6
        val result = detect()
        cachedHasIpv6 = result
        cacheAt = now
        return result
    }

    private fun detect(): Boolean {
        // 优先用 ConnectivityManager 的 LinkProperties（API 21+），只统计真正活跃的网络
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val networks = cm.allNetworks
                if (!networks.isNullOrEmpty()) {
                    for (network: Network in networks) {
                        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                            ?: continue
                        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                        // 不检查 NET_CAPABILITY_VALIDATED：国内盒子没有 Google 外网探测，
                        // 该标志常为 false，但网络实际可用、IPv6 地址也真实存在。
                        val links: Collection<LinkAddress> =
                            runCatching { cm.getLinkProperties(network)?.linkAddresses }.getOrNull()
                                ?: continue
                        if (links.any { isGlobalIpv6(it.address) }) return true
                    }
                    // 有活跃网络信息但没有一个带 IPv6 → 判定当前无 IPv6
                    return false
                }
            }
        } catch (_: Throwable) {
            // 权限 / ROM 差异：回退到网卡遍历
        }

        // 回退：遍历网卡，排除 loopback / link-local / 未启用
        return try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return false
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val addresses = ni.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    if (isGlobalIpv6(addresses.nextElement())) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isGlobalIpv6(address: InetAddress?): Boolean =
        address is Inet6Address && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isAnyLocalAddress
}
