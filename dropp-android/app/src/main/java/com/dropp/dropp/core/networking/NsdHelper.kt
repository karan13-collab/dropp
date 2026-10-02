package com.dropp.dropp.core.networking

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdHelper(private val context: Context) {
    // Explicitly declare the type to ensure it resolves correctly
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_dropp._tcp."
    private val serviceName = "dropp_android"
    private val TAG = "NsdHelper"

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startBroadcasting(port: Int = 8765) {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@NsdHelper.serviceName
            this.serviceType = this@NsdHelper.serviceType
            this.port = port
        }

        // 1. Create it as a strictly non-null local variable
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Broadcasting dropp_android on port ${info.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        // 2. Save it to the class for later cleanup
        registrationListener = listener

        // 3. Pass the guaranteed safe local variable to the Android API
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun startDiscovering(onIpResolved: (String) -> Unit) {
        // 1. Create it as a strictly non-null local variable
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")

                if (service.serviceType == serviceType && service.serviceName.contains("dropp_windows")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostAddress = serviceInfo.host.hostAddress
                            Log.d(TAG, "Found Windows PC at $hostAddress:${serviceInfo.port}")

                            // --- NEW: Feed the IP to the Command engine ---
                            com.dropp.dropp.core.networking.CommandTransferManager.targetWindowsIp = hostAddress

                            onIpResolved(hostAddress)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        // 2. Save it to the class
        discoveryListener = listener

        // 3. Pass the guaranteed safe local variable
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }
}