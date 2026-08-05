package com.omoai.simpleuvcstreamer.stream

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerate IPv4 addresses and classify for UI priority.
 *
 * Highlight: typical LAN peers can reach (192.168 / 172.16–31).
 * Soft: 10.x private ranges (often USB / carrier / less useful).
 * Local: loopback only on this device.
 */
object NetworkAddresses {

    enum class AccessTier {
        LAN,
        OTHER,
        LOCAL,
    }

    data class Endpoint(
        val interfaceName: String,
        val host: String,
        val isLoopback: Boolean,
        val tier: AccessTier,
    )

    fun ipv4Endpoints(): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return listOf(localEndpoint())

        for (iface in ifaces) {
            if (!iface.isUp || iface.isPointToPoint) continue
            val name = iface.name ?: continue
            for (addr in iface.inetAddresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLinkLocalAddress) continue
                val host = addr.hostAddress ?: continue
                val loopback = addr.isLoopbackAddress || iface.isLoopback
                out += Endpoint(
                    interfaceName = name,
                    host = host,
                    isLoopback = loopback,
                    tier = classify(host, loopback),
                )
            }
        }

        if (out.none { it.isLoopback }) {
            out.add(localEndpoint())
        }

        return out.sortedWith(
            compareBy<Endpoint> { it.tier.ordinal }
                .thenBy { it.host }
        )
    }

    fun classify(host: String, isLoopback: Boolean): AccessTier {
        if (isLoopback || host.startsWith("127.")) return AccessTier.LOCAL
        val parts = host.split('.')
        if (parts.size != 4) return AccessTier.OTHER
        val a = parts[0].toIntOrNull() ?: return AccessTier.OTHER
        val b = parts[1].toIntOrNull() ?: return AccessTier.OTHER
        // Classic home/office Wi‑Fi LAN — recommend & highlight.
        if (a == 192 && b == 168) return AccessTier.LAN
        if (a == 172 && b in 16..31) return AccessTier.LAN
        // 10.x often USB tethering / carrier / less peer-friendly — de-emphasize.
        if (a == 10) return AccessTier.OTHER
        return AccessTier.OTHER
    }

    private fun localEndpoint() = Endpoint(
        interfaceName = "lo",
        host = "127.0.0.1",
        isLoopback = true,
        tier = AccessTier.LOCAL,
    )
}
