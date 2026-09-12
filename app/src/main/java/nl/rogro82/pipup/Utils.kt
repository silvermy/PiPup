package nl.rogro82.pipup

import java.net.Inet4Address
import java.net.NetworkInterface

object Utils {
    /**
     * The original returned the first non-loopback IPv4 address of the first
     * interface it saw, which on boxes with both ethernet and wifi (or a VPN, or
     * a docker-style bridge) regularly reported an address nothing could reach.
     * Prefer an up, non-virtual interface with a site-local address.
     */
    fun getIpAddress(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()

        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue

                for (address in iface.inetAddresses) {
                    if (address.isLoopbackAddress || address !is Inet4Address) continue
                    if (address.isLinkLocalAddress) continue

                    val host = address.hostAddress ?: continue
                    val score = when {
                        iface.name.startsWith("eth") -> 0
                        iface.name.startsWith("wlan") -> 1
                        address.isSiteLocalAddress -> 2
                        else -> 3
                    }
                    candidates += score to host
                }
            }
        } catch (_: Throwable) {
        }

        return candidates.minByOrNull { it.first }?.second
    }
}
