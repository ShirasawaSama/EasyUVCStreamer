package com.omoai.simpleuvcstreamer.stream

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerate IPv4 addresses that peers can use to reach this device.
 */
object NetworkAddresses {

    data class Endpoint(
        val interfaceName: String,
        val host: String,
        val isLoopback: Boolean,
    )

    fun ipv4Endpoints(): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return listOf(Endpoint("lo", "127.0.0.1", true))

        for (iface in ifaces) {
            if (!iface.isUp || iface.isPointToPoint) continue
            val name = iface.name ?: continue
            for (addr in iface.inetAddresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLinkLocalAddress) continue
                out += Endpoint(
                    interfaceName = name,
                    host = addr.hostAddress ?: continue,
                    isLoopback = addr.isLoopbackAddress || iface.isLoopback,
                )
            }
        }

        if (out.none { it.isLoopback }) {
            out.add(0, Endpoint("lo", "127.0.0.1", true))
        }
        return out.sortedWith(
            compareBy<Endpoint> { it.isLoopback }
                .thenBy { it.interfaceName }
                .thenBy { it.host }
        )
    }

    fun streamUrls(port: Int, path: String = "/stream.mjpg"): List<String> {
        if (port !in 1..65535) return emptyList()
        return ipv4Endpoints().map { "http://${it.host}:$port$path" }
    }

    fun indexUrls(port: Int): List<String> {
        if (port !in 1..65535) return emptyList()
        return ipv4Endpoints().map { "http://${it.host}:$port/" }
    }
}
