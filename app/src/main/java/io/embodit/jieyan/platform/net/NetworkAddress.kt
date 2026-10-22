package io.embodit.jieyan.platform.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** The headset's LAN address for the lobby card (`http://<ip>:8765`); null without a usable interface. */
object NetworkAddress {
    fun lanIp(): String? =
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // wlan first (the headset is on Wi-Fi), then anything else that is up and not loopback
            val ordered = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            for (nif in ordered) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) return addr.hostAddress
                }
            }
            null
        }.getOrNull()
}
