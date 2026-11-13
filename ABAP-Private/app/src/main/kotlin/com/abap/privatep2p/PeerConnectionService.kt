package com.abap.privatep2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.core.network.DirectPeerListener
import com.abap.privatep2p.core.network.DirectSecureChannel
import com.abap.privatep2p.feature.pairing.EncryptedPairingStore
import com.abap.privatep2p.feature.pairing.StoredChatMessage
import com.abap.privatep2p.service.audio.AudioCallSession
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Owns the peer socket, encrypted receive loop and voice session independently
 * of the Activity. The service never contacts a rendezvous or relay.
 */
class PeerConnectionService : Service() {
    interface Listener {
        fun onStatus(status: String, connected: Boolean) {}
        fun onMessage(message: StoredChatMessage) {}
        fun onHistory(messages: List<StoredChatMessage>) {}
        fun onIncomingCall() {}
        fun onCallStatus(status: String, active: Boolean, incoming: Boolean) {}
        fun onError(message: String) {}
        fun onEndpointDiscovered(name: String, host: String, port: Int) {}
        fun onEndpointLost(name: String) {}
    }

    inner class LocalBinder : Binder() {
        fun setListener(listener: Listener?) {
            this@PeerConnectionService.listener = listener
            listener?.onStatus(connectionStatus, channel != null)
            listener?.onHistory(runCatching { pairingStore.loadMessages() }.getOrDefault(emptyList()))
            listener?.onCallStatus(callStatus, audioSession != null, incomingCall)
        }

        fun sendMessage(body: String) = this@PeerConnectionService.sendMessage(body)
        fun requestCall() = this@PeerConnectionService.requestCall()
        fun acceptCall() = this@PeerConnectionService.acceptCall()
        fun rejectCall() = this@PeerConnectionService.rejectCall()
        fun endCall() = this@PeerConnectionService.endCall()
        fun stopConnection() = this@PeerConnectionService.stopConnection()
        fun discoverPeers() = this@PeerConnectionService.startDiscovery()
        fun stopPeerDiscovery() = this@PeerConnectionService.stopDiscovery()
        fun connectTo(host: String) = this@PeerConnectionService.connect(host)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val identityStore by lazy { AndroidDeviceIdentityStore() }
    private val pairingStore by lazy { EncryptedPairingStore(this) }
    private val nsdManager by lazy { getSystemService(NsdManager::class.java) }
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    @Volatile private var advertisedServiceName: String? = null

    @Volatile private var listener: Listener? = null
    @Volatile private var connectionStatus = "Not connected"
    @Volatile private var callStatus = "No active call"
    @Volatile private var channel: DirectSecureChannel? = null
    @Volatile private var peerListener: DirectPeerListener? = null
    @Volatile private var audioSession: AudioCallSession? = null
    @Volatile private var incomingCall = false
    @Volatile private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_LISTEN
        if (action == ACTION_STOP) {
            stopConnection()
            stopSelf()
            return START_NOT_STICKY
        }
        promoteForeground(false)
        when (action) {
            ACTION_LISTEN -> startListening()
            ACTION_CONNECT -> {
                val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
                if (host.isBlank()) reportError("Peer address is missing")
                else connect(host)
            }
        }
        return START_STICKY
    }

    private fun promoteForeground(forCall: Boolean) {
        val notification = buildNotification(
            if (forCall) "Encrypted voice call active" else "ABAP Private peer service active"
        )
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        if (forCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            check(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required for voice calls"
            }
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startListening() {
        if (peerListener != null) {
            updateStatus("Listening on TCP port $TCP_PORT", channel != null)
            return
        }
        val peer = runCatching { pairingStore.loadPeer() }.getOrNull()
        if (peer == null) {
            reportError("Pair a device before starting the peer listener")
            return
        }
        try {
            peerListener = DirectPeerListener(
                TCP_PORT,
                identityStore.getOrCreate(),
                peer.publicKey,
                onConnected = ::attachChannel,
                onError = { error -> reportError(error.message ?: "Incoming connection failed") }
            ).also { it.start() }
            startAdvertisement()
            updateStatus("Listening on TCP port $TCP_PORT. LAN discovery is available; internet peers need a reachable endpoint.", false)
        } catch (error: Exception) {
            reportError(error.message ?: "Could not start the peer listener")
        }
    }

    private fun startAdvertisement() {
        if (nsdRegistration != null) return
        val fingerprint = runCatching { identityStore.getOrCreate().fingerprint.take(8) }.getOrDefault("device")
        val info = NsdServiceInfo().apply {
            serviceName = "ABAPPrivate-$fingerprint"
            serviceType = NSD_SERVICE_TYPE
            port = TCP_PORT
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                advertisedServiceName = serviceInfo.serviceName
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                nsdRegistration = null
                reportError("Local network advertisement failed (NSD $errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                advertisedServiceName = null
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                reportError("Could not stop local network advertisement (NSD $errorCode)")
            }
        }
        nsdRegistration = registration
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure {
            nsdRegistration = null
            reportError("Local network advertisement unavailable: ${it.message}")
        }
    }

    private fun startDiscovery() {
        if (nsdDiscovery != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                updateStatus("Searching for paired ABAP Private devices on this local network…", channel != null)
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == advertisedServiceName ||
                    !serviceInfo.serviceType.startsWith(NSD_SERVICE_PREFIX)) return
                runCatching {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            reportError("Could not resolve local peer ${info.serviceName} (NSD $errorCode)")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress?.substringBefore('%') ?: return
                            if (info.port == TCP_PORT) {
                                mainHandler.post {
                                    listener?.onEndpointDiscovered(info.serviceName, host, info.port)
                                }
                            }
                        }
                    })
                }.onFailure { reportError("Local peer resolution failed: ${it.message}") }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mainHandler.post { listener?.onEndpointLost(serviceInfo.serviceName) }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
                nsdDiscovery = null
                reportError("Local peer discovery failed (NSD $errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                reportError("Could not stop local peer discovery (NSD $errorCode)")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                nsdDiscovery = null
            }
        }
        nsdDiscovery = discovery
        runCatching {
            nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure {
            nsdDiscovery = null
            reportError("Local peer discovery unavailable: ${it.message}")
        }
    }

    private fun stopDiscovery() {
        nsdDiscovery?.let { discovery ->
            runCatching { nsdManager.stopServiceDiscovery(discovery) }
        }
        nsdDiscovery = null
    }

    private fun stopAdvertisement() {
        nsdRegistration?.let { registration ->
            runCatching { nsdManager.unregisterService(registration) }
        }
        nsdRegistration = null
        advertisedServiceName = null
    }

    private fun connect(host: String) {
        val peer = runCatching { pairingStore.loadPeer() }.getOrNull()
        if (peer == null) {
            reportError("Pair a device before connecting")
            return
        }
        thread(name = "abap-private-connect", isDaemon = true) {
            try {
                updateStatus("Connecting directly to $host:$TCP_PORT…", false)
                val secure = DirectSecureChannel.connect(
                    host,
                    TCP_PORT,
                    identityStore.getOrCreate(),
                    peer.publicKey
                )
                attachChannel(secure)
            } catch (error: Exception) {
                reportError(error.message ?: "Direct connection failed")
            }
        }
    }

    @Synchronized
    private fun attachChannel(secure: DirectSecureChannel) {
        channel?.close()
        audioSession?.close()
        audioSession = null
        incomingCall = false
        channel = secure
        updateStatus("Secure connection established; pinned peer authenticated", true)
        callStatus = "No active call"
        notifyCallStatus()
        thread(name = "abap-private-receive", isDaemon = true) {
            try {
                while (!shuttingDown && channel === secure) {
                    val frame = secure.receive()
                    when (frame.type) {
                        DirectSecureChannel.TYPE_TEXT -> {
                            val body = String(frame.payload, Charsets.UTF_8)
                            if (body.isNotBlank() && body.length <= MAX_MESSAGE_CHARS) {
                                val message = StoredChatMessage(
                                    UUID.randomUUID().toString(), false, body, System.currentTimeMillis()
                                )
                                pairingStore.appendMessage(message)
                                mainHandler.post { listener?.onMessage(message) }
                            }
                        }
                        DirectSecureChannel.TYPE_AUDIO -> audioSession?.playIncoming(frame.payload)
                        DirectSecureChannel.TYPE_CALL_REQUEST -> {
                            incomingCall = true
                            callStatus = "Incoming voice call"
                            mainHandler.post {
                                listener?.onIncomingCall()
                                listener?.onCallStatus(callStatus, false, true)
                            }
                        }
                        DirectSecureChannel.TYPE_CALL_ACCEPT -> {
                            incomingCall = false
                            startAudioSession()
                        }
                        DirectSecureChannel.TYPE_CALL_REJECT -> {
                            incomingCall = false
                            callStatus = "Peer declined the call"
                            runCatching { promoteForeground(false) }
                            notifyCallStatus()
                        }
                        DirectSecureChannel.TYPE_CALL_END -> {
                            incomingCall = false
                            stopAudioSession()
                            callStatus = "Call ended by peer"
                            notifyCallStatus()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!shuttingDown && channel === secure) {
                    channel = null
                    stopAudioSession()
                    updateStatus("Disconnected: ${error.message ?: "connection closed"}", false)
                    callStatus = "No active call"
                    notifyCallStatus()
                }
            }
        }
    }

    private fun sendMessage(body: String) {
        val clean = body.trim()
        if (clean.isEmpty() || clean.length > MAX_MESSAGE_CHARS) return
        val secure = channel ?: run { reportError("Connect to your peer first"); return }
        thread(name = "abap-private-send-message", isDaemon = true) {
            try {
                secure.send(DirectSecureChannel.TYPE_TEXT, clean.toByteArray(Charsets.UTF_8))
                val message = StoredChatMessage(
                    UUID.randomUUID().toString(), true, clean, System.currentTimeMillis()
                )
                pairingStore.appendMessage(message)
                mainHandler.post { listener?.onMessage(message) }
            } catch (error: Exception) {
                reportError(error.message ?: "Message could not be sent")
            }
        }
    }

    private fun requestCall() {
        val secure = channel ?: run { reportError("Connect to your peer first"); return }
        runCatching {
            // Promote while the Activity is visible so an accepted call can continue
            // using the microphone after the user backgrounds the app.
            promoteForeground(true)
            secure.send(DirectSecureChannel.TYPE_CALL_REQUEST, byteArrayOf())
        }.onSuccess {
            callStatus = "Calling peer…"
            notifyCallStatus()
        }.onFailure {
            runCatching { promoteForeground(false) }
            reportError(it.message ?: "Could not request a call")
        }
    }

    private fun acceptCall() {
        val secure = channel ?: run { reportError("Peer is no longer connected"); return }
        try {
            promoteForeground(true)
            secure.send(DirectSecureChannel.TYPE_CALL_ACCEPT, byteArrayOf())
            incomingCall = false
            startAudioSession()
        } catch (error: Exception) {
            reportError(error.message ?: "Could not accept the call")
        }
    }

    private fun rejectCall() {
        val secure = channel ?: return
        runCatching { secure.send(DirectSecureChannel.TYPE_CALL_REJECT, byteArrayOf()) }
        incomingCall = false
        callStatus = "Call declined"
        notifyCallStatus()
    }

    private fun endCall() {
        val secure = channel
        runCatching { secure?.send(DirectSecureChannel.TYPE_CALL_END, byteArrayOf()) }
        incomingCall = false
        stopAudioSession()
        callStatus = "Call ended"
        notifyCallStatus()
        if (channel != null) promoteForeground(false)
    }

    private fun startAudioSession() {
        val secure = channel ?: return
        try {
            promoteForeground(true)
            audioSession?.close()
            audioSession = AudioCallSession(secure) { error ->
                reportError(error.message ?: "Voice transport failed")
                endCall()
            }.also { it.start() }
            callStatus = "Voice call active — Opus"
            notifyCallStatus()
        } catch (error: Exception) {
            stopAudioSession()
            callStatus = "Could not start voice: ${error.message}"
            notifyCallStatus()
            reportError(callStatus)
            promoteForeground(false)
        }
    }

    private fun stopAudioSession() {
        audioSession?.close()
        audioSession = null
        if (channel != null) runCatching { promoteForeground(false) }
    }

    private fun stopConnection() {
        stopDiscovery()
        stopAdvertisement()
        peerListener?.close()
        peerListener = null
        channel?.close()
        channel = null
        stopAudioSession()
        incomingCall = false
        updateStatus("Connection stopped", false)
        callStatus = "No active call"
        notifyCallStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(status: String, connected: Boolean) {
        connectionStatus = status
        mainHandler.post { listener?.onStatus(status, connected) }
    }

    private fun notifyCallStatus() {
        val status = callStatus
        val active = audioSession != null
        val incoming = incomingCall
        mainHandler.post { listener?.onCallStatus(status, active, incoming) }
    }

    private fun reportError(message: String) {
        mainHandler.post { listener?.onError(message) }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, PeerConnectionService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("ABAP Private")
            .setContentText(content)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Private peer connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        stopConnection()
        listener = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LISTEN = "com.abap.privatep2p.action.LISTEN"
        const val ACTION_CONNECT = "com.abap.privatep2p.action.CONNECT"
        const val ACTION_STOP = "com.abap.privatep2p.action.STOP"
        const val EXTRA_HOST = "peer_host"
        const val TCP_PORT = 47831
        private const val MAX_MESSAGE_CHARS = 16_384
        private const val CHANNEL_ID = "abap_private_peer"
        private const val NSD_SERVICE_TYPE = "_abap-private._tcp."
        private const val NSD_SERVICE_PREFIX = "_abap-private._tcp"
        private const val NOTIFICATION_ID = 47831
    }
}
