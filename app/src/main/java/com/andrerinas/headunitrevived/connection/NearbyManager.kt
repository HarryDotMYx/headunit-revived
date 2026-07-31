package com.andrerinas.headunitrevived.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.ToastUtils
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Socket

/**
 * Manages Google Nearby Connections on the Headunit (Tablet).
 * The Tablet acts as a DISCOVERER only.
 */
class NearbyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onNewDeviceNeedsApproval: (endpointId: String, endpointName: String, authDigits: String) -> Unit,
    private val onSocketReady: (Socket) -> Unit
) {

    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.hurev"
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private var isRunning = false
    private var isConnecting = false
    private var activeNearbySocket: NearbySocket? = null
    private var activeEndpointId: String? = null
    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null
    private var upgradeTimeoutJob: kotlinx.coroutines.Job? = null
    private val settings = Settings(context)
    // Endpoint awaiting a user tap on the "new device" notification before we call
    // acceptConnection(). Nearby Connections keeps this session alive (and its own internal
    // timeout will reject it if the user never responds), so there's no need to re-request.
    private var pendingApprovalEndpointId: String? = null
    private var pendingApprovalEndpointName: String? = null

    fun start() {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return
        }
        if (isRunning) {
            AppLog.i("NearbyManager: Already running discovery.")
            return
        }
        AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
        isRunning = true
        _discoveredEndpoints.value = emptyList()
        startDiscovery()
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasAdvertise || !hasScan || !hasConnect) return false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    fun stop() {
        AppLog.i("NearbyManager: Stopping discovery and disconnecting from any active endpoint...")
        isRunning = false
        isConnecting = false
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        connectionsClient.stopDiscovery()
        activeEndpointId?.let {
            connectionsClient.disconnectFromEndpoint(it)
            activeEndpointId = null
        }
        pendingApprovalEndpointId?.let {
            try { connectionsClient.rejectConnection(it) } catch (e: Exception) {}
            pendingApprovalEndpointId = null
            pendingApprovalEndpointName = null
        }
        activeNearbySocket?.close()
        activeNearbySocket = null
        activePipes?.forEach { try { it.close() } catch (e: Exception) {} }
        activePipes = null
        _discoveredEndpoints.value = emptyList()
    }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list.
     */
    fun connectToEndpoint(endpointId: String) {
        if (isConnecting) {
            AppLog.w("NearbyManager: Already connecting, ignoring request for $endpointId")
            return
        }
        AppLog.i("NearbyManager: Requesting connection to endpoint: $endpointId")
        isConnecting = true
        
        connectionsClient.requestConnection(android.os.Build.MODEL, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                AppLog.e("NearbyManager: Failed to request connection: ${e.message}")
                isConnecting = false
            }
    }

    /**
     * Called when the user taps the "New Nearby device" approval notification. Remembers the
     * endpoint name so future connections auto-accept, then accepts this still-pending session.
     */
    fun approvePendingConnection(endpointId: String) {
        if (pendingApprovalEndpointId != endpointId) {
            AppLog.w("NearbyManager: Approval for $endpointId ignored — no longer the pending endpoint (current=$pendingApprovalEndpointId).")
            return
        }
        val name = pendingApprovalEndpointName ?: ""
        AppLog.i("NearbyManager: User approved Nearby device '$name' ($endpointId). Accepting connection.")
        settings.approvedNearbyDeviceNames = settings.approvedNearbyDeviceNames + name
        settings.lastNearbyDeviceName = name
        pendingApprovalEndpointId = null
        pendingApprovalEndpointName = null
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to accept connection: ${e.message}") }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID (Strategy: P2P_POINT_TO_POINT)")
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { AppLog.d("NearbyManager: [OK] Discovery started.") }
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: [ERROR] Discovery failed: ${e.message}") 
                isRunning = false
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
            }

            // Auto-connect logic
            val autoConnectMode = settings.autoConnectLastSession
            AppLog.i("NearbyManager: Auto-connect check: Enabled=$autoConnectMode, isConnecting=$isConnecting, activeEndpointId=$activeEndpointId")
            
            if (autoConnectMode && !isConnecting && activeEndpointId == null) {
                val lastDevice = settings.lastNearbyDeviceName
                AppLog.i("NearbyManager: Comparing found '${info.endpointName}' with last known '$lastDevice'")
                if (lastDevice.isNotEmpty() && lastDevice == info.endpointName) {
                    AppLog.i("NearbyManager: MATCH! Auto-connecting to known device '$lastDevice'...")
                    connectToEndpoint(endpointId)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Auth digits: ${info.authenticationDigits}")

            // Stop discovery as soon as it finds the target.
            isRunning = false
            connectionsClient.stopDiscovery()

            // [FIX] Used to accept unconditionally, discarding the authentication digits Nearby
            // Connections exists to let us verify the peer out-of-band. A rogue endpoint
            // advertising this service ID (or spoofing a remembered name for auto-connect)
            // would previously get an accepted session with zero user awareness. Require a
            // one-time approval per endpoint name; already-approved names behave as before.
            //
            // Note: endpointName is an attacker-controlled display string, not a stable device
            // identity — Nearby Connections deliberately doesn't expose one. authenticationDigits
            // is *per-connection-attempt*, not reusable as a persistent allowlist key, so it
            // can't replace the name as the remembered key either. What it IS good for is exactly
            // what it's designed for: a human comparing the same digits shown on both devices
            // before approving — so it's surfaced in the approval notification instead of being
            // silently discarded, giving the user a real signal even though the persisted
            // allowlist is still name-keyed.
            if (settings.approvedNearbyDeviceNames.contains(info.endpointName)) {
                AppLog.i("NearbyManager: '${info.endpointName}' already approved. Automatically ACCEPTING connection...")
                settings.lastNearbyDeviceName = info.endpointName
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to accept connection: ${e.message}") }
            } else {
                AppLog.w("NearbyManager: New Nearby device '${info.endpointName}' ($endpointId) initiated a connection. Requesting user approval before accepting.")
                pendingApprovalEndpointId = endpointId
                pendingApprovalEndpointName = info.endpointName
                onNewDeviceNeedsApproval(endpointId, info.endpointName, info.authenticationDigits)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            AppLog.i("NearbyManager: Connection RESULT for $endpointId: StatusCode=${status.statusCode} (${status.statusMessage})")
            
            if (status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                isConnecting = false
            }

            when (status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    isConnecting = false
                    activeEndpointId = endpointId
                    AppLog.i("NearbyManager: Connected successfully! Waiting up to 10s for bandwidth upgrade to HIGH quality (Wi-Fi)...")

                    // Start a 10-second timeout for the Wi-Fi bandwidth upgrade
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(10_000)
                        if (activeNearbySocket == null && activeEndpointId == endpointId) {
                            AppLog.e("NearbyManager: Bandwidth upgrade timed out after 10s. Disconnecting to prevent Bluetooth fallback.")
                            scope.launch(Dispatchers.Main) {
                                ToastUtils.showToast(
                                    context, 
                                    "Google Nearby connection failed: Wi-Fi bandwidth upgrade timed out. Please check Wi-Fi & Bluetooth settings.", 
                                    android.widget.Toast.LENGTH_LONG
                                )
                            }
                            stop()
                        }
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> AppLog.w("NearbyManager: Connection REJECTED by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> AppLog.e("NearbyManager: Connection ERROR with $endpointId")
                else -> AppLog.w("NearbyManager: Unknown connection result code: ${status.statusCode}")
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            AppLog.i("NearbyManager: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality}")
            if (bandwidthInfo.quality == BandwidthInfo.Quality.HIGH) {
                if (activeEndpointId == endpointId && activeNearbySocket == null) {
                    AppLog.i("NearbyManager: Wi-Fi Bandwidth Upgrade successful (Quality: HIGH). Initiating stream tunnel...")
                    
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = null

                    val socket = NearbySocket()
                    activeNearbySocket = socket

                    scope.launch(Dispatchers.IO) {
                        val sock = activeNearbySocket ?: return@launch
                        
                        // [CRITICAL] Wait a bit before sending the payload. 
                        // The phone (WirelessHelper) has a ~500ms delay in its connection logic.
                        // If we send too early, the phone won't have its 'activeNearbySocket' 
                        // set yet, and our incoming stream will be dropped/ignored by the phone.
                        AppLog.i("NearbyManager: Waiting 800ms for phone state synchronization...")
                        kotlinx.coroutines.delay(800)

                        // 1. Create outgoing pipe (Tablet -> Phone)
                        val pipes = android.os.ParcelFileDescriptor.createPipe()
                        activePipes = pipes
                        val outputStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                        sock.outputStreamWrapper = outputStream

                        // 2. Initiate stream tunnel
                        AppLog.i("NearbyManager: Initiating stream tunnel to $endpointId...")
                        val tabletToPhonePayload = Payload.fromStream(pipes[0])
                        AppLog.i("NearbyManager: Sending STREAM payload (ID: ${tabletToPhonePayload.id})")
                        
                        connectionsClient.sendPayload(endpointId, tabletToPhonePayload)
                            .addOnSuccessListener { 
                                AppLog.i("NearbyManager: [OK] Tablet->Phone stream payload registered.") 
                            }
                            .addOnFailureListener { e -> 
                                AppLog.e("NearbyManager: [ERROR] Failed to send stream: ${e.message}") 
                            }

                        // [CRITICAL] Start AA handshake immediately. 
                        // NearbySocket.read() will block internally until Phone stream arrives.
                        AppLog.i("NearbyManager: Starting AA handshake now. Input will block until stream arrives.")
                        onSocketReady(sock)
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                isConnecting = false
                upgradeTimeoutJob?.cancel()
                upgradeTimeoutJob = null
                // [FIX] These were never cleared here. If the phone's helper reconnects before
                // the AAP handshake completed (so hasEverConnected/AapService's own stop() never
                // ran), onBandwidthChanged's tunnel-creation guard (activeNearbySocket == null)
                // would stay permanently false against this stale, already-dead socket —
                // silently blocking every future stream tunnel for the rest of the process:
                // connected at the Nearby layer, no projection, no error, forever.
                activeNearbySocket?.close()
                activeNearbySocket = null
                activePipes?.forEach { try { it.close() } catch (e: Exception) {} }
                activePipes = null
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            AppLog.i("NearbyManager: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received incoming STREAM payload. Completing bidirectional tunnel.")
                activeNearbySocket?.let { socket ->
                    socket.inputStreamWrapper = payload.asStream()?.asInputStream()
                    AppLog.i("NearbyManager: InputStream assigned to socket. Handshake should continue.")
                }
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                AppLog.i("NearbyManager: Received BYTES payload: $msg")
                if (msg == "PING") {
                    AppLog.i("NearbyManager: Received PING from Phone. Connections are alive.")
                }
            }
        }


        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                AppLog.d("NearbyManager: Payload transfer SUCCESS for endpoint $endpointId")
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                AppLog.e("NearbyManager: Payload transfer FAILURE for endpoint $endpointId")
            }
        }
    }
}
