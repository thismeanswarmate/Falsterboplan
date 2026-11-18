package com.falsterboplan.lights.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

data class DiscoveredHub(val name: String, val host: String)

/** Finds DIRIGERA hubs on the local network via mDNS. */
class HubDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun hubs(): Flow<List<DiscoveredHub>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredHub>()

        fun resolve(service: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = ipv4Of(info) ?: return
                    found[info.serviceName] = DiscoveredHub(info.serviceName, address)
                    trySend(found.values.toList())
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) = resolve(service)
            override fun onServiceLost(service: NsdServiceInfo) {
                if (found.remove(service.serviceName) != null) trySend(found.values.toList())
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        return candidates.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private companion object {
        const val SERVICE_TYPE = "_ihsp._tcp"
    }
}
