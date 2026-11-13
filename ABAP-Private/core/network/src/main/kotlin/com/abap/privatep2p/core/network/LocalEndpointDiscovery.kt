package com.abap.privatep2p.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LocalEndpointAddress(
    val address: String,
    val networkName: String,
    val scope: Scope
) {
    enum class Scope { GLOBAL_IPV6, PRIVATE_LAN, OTHER }
}

/**
 * Lists addresses actually assigned to active interfaces. It deliberately does not
 * query an external IP-discovery service. A private LAN address is not internet
 * reachable; a global IPv6 address can still be blocked by the device/router firewall.
 */
object LocalEndpointDiscovery {
    fun discover(): List<LocalEndpointAddress> {
        val result = mutableListOf<LocalEndpointAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (runCatching { !network.isUp || network.isLoopback }.getOrDefault(true)) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isMulticastAddress) continue
                val scope = when {
                    address is Inet6Address && isGlobalIpv6(address) ->
                        LocalEndpointAddress.Scope.GLOBAL_IPV6
                    address is Inet4Address && address.isSiteLocalAddress ->
                        LocalEndpointAddress.Scope.PRIVATE_LAN
                    address is Inet6Address && isUniqueLocalIpv6(address) ->
                        LocalEndpointAddress.Scope.PRIVATE_LAN
                    else -> LocalEndpointAddress.Scope.OTHER
                }
                result += LocalEndpointAddress(
                    address.hostAddress.substringBefore('%'),
                    network.displayName ?: network.name,
                    scope
                )
            }
        }
        return result.distinctBy { it.address }.sortedBy {
            when (it.scope) {
                LocalEndpointAddress.Scope.GLOBAL_IPV6 -> 0
                LocalEndpointAddress.Scope.PRIVATE_LAN -> 1
                LocalEndpointAddress.Scope.OTHER -> 2
            }
        }
    }

    private fun isGlobalIpv6(address: Inet6Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val uniqueLocal = (first and 0xfe) == 0xfc
        val documentation = first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d &&
            (bytes[3].toInt() and 0xff) == 0xb8
        return !uniqueLocal && !documentation && !address.isSiteLocalAddress
    }

    private fun isUniqueLocalIpv6(address: Inet6Address): Boolean =
        ((address.address[0].toInt() and 0xff) and 0xfe) == 0xfc
}
