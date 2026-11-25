package com.nosmai.quickstart.webrtc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * WebRTC needs a "signaling" channel to exchange the offer, answer and ICE
 * candidates before the peers can talk directly. WebRTC does not provide one.
 *
 * To keep this sample runnable without a server, signaling goes over the local
 * network: the host opens a TCP port and advertises it with Android's network
 * service discovery (NSD); viewers find the service and connect to it. Messages are
 * JSON objects, one per line. Both phones must be on the same Wi-Fi.
 *
 * In a real app, replace this class with your own signaling (for example a
 * WebSocket to your backend). Nothing else in the sample depends on how the
 * messages travel. All callbacks are delivered on the main thread.
 */
class LocalSignaling(context: Context, private val room: String, private val isHost: Boolean) {

    companion object {
        private const val SERVICE_TYPE = "_nosmailive._tcp"
        /** Separates the room from a random suffix in the advertised service name. */
        private const val NAME_SEPARATOR = '#'
        private const val RETRY_DELAY_MS = 1500L
    }

    /** A peer is the other end of one signaling connection, identified by a local ID. */
    var onError: ((String) -> Unit)? = null
    var onPeerConnected: ((String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    var onMessage: ((JSONObject, String) -> Unit)? = null

    private class Peer(val socket: Socket) {
        /** Writes are queued so that messages never interleave and never block the caller. */
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Peer>()
    @Volatile private var stopped = false

    // Host.
    @Volatile private var serverSocket: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var nextViewer = 1

    // Audience (main thread only).
    private var discovery: NsdManager.DiscoveryListener? = null
    /** Hosts of our room currently advertised on the network, by service name. */
    private val hostsFound = LinkedHashMap<String, NsdServiceInfo>()
    private var connecting = false

    /** Host: advertise the room. Audience: look for a host of the same room. */
    fun start() {
        if (isHost) startHost() else startDiscovery()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { serverSocket?.close() }
        peers.keys.toList().forEach { removePeer(it) }
    }

    fun send(message: JSONObject, to: String) {
        val peer = peers[to] ?: return
        val line = (message.toString() + "\n").toByteArray()
        runCatching {
            peer.writer.execute {
                try {
                    peer.socket.getOutputStream().apply { write(line); flush() }
                } catch (e: Exception) {
                    removePeer(to)
                }
            }
        }
    }

    // ---- Host ----

    private fun startHost() {
        thread(name = "signaling-accept") {
            try {
                val server = ServerSocket(0) // any free port
                serverSocket = server
                main.post { advertise(server.localPort) }
                while (!stopped) {
                    val socket = server.accept()
                    val id = synchronized(this) { "viewer-${nextViewer++}" }
                    addPeer(id, socket)
                }
            } catch (e: Exception) {
                if (!stopped) main.post { if (!stopped) onError?.invoke("Local signaling server stopped") }
            }
        }
    }

    private fun advertise(port: Int) {
        if (stopped) return
        val info = NsdServiceInfo().apply {
            serviceName = room + NAME_SEPARATOR + UUID.randomUUID().toString().take(6)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { if (!stopped) onError?.invoke("Local network advertising failed ($errorCode)") }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // ---- Audience ----

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    val name = serviceInfo.serviceName ?: return@post
                    // The room is everything before the last separator.
                    if (name.substringBeforeLast(NAME_SEPARATOR) != room) return@post
                    hostsFound[name] = serviceInfo
                    connectToHost()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post { hostsFound.remove(serviceInfo.serviceName) }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (!stopped) onError?.invoke("Local network discovery failed ($errorCode)") }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Connects to the first advertised host of our room, unless already connected or trying. */
    private fun connectToHost() {
        if (stopped || connecting || peers.isNotEmpty()) return
        val service = hostsFound.values.firstOrNull() ?: return
        connecting = true
        // Discovery only gives a name: resolving it gives the address and port.
        @Suppress("DEPRECATION")
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                thread(name = "signaling-connect") {
                    try {
                        @Suppress("DEPRECATION")
                        val address = InetSocketAddress(resolved.host, resolved.port)
                        val socket = Socket()
                        socket.connect(address, 5000)
                        addPeer("host", socket)
                    } catch (e: Exception) {
                        retryLater()
                    }
                }
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = retryLater()
        })
    }

    private fun retryLater() {
        main.post {
            connecting = false
            if (!stopped) main.postDelayed({ connectToHost() }, RETRY_DELAY_MS)
        }
    }

    // ---- Connections ----

    private fun addPeer(id: String, socket: Socket) {
        if (stopped) {
            runCatching { socket.close() }
            return
        }
        socket.tcpNoDelay = true
        peers[id] = Peer(socket)
        main.post {
            connecting = false
            if (!stopped) onPeerConnected?.invoke(id)
        }
        thread(name = "signaling-read-$id") {
            try {
                val reader = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    main.post { if (!stopped) onMessage?.invoke(message, id) }
                }
            } catch (e: Exception) {
                // The socket was closed; handled below.
            }
            removePeer(id)
        }
    }

    private fun removePeer(id: String) {
        val peer = peers.remove(id) ?: return
        runCatching { peer.socket.close() }
        peer.writer.shutdown()
        if (stopped) return
        main.post {
            if (stopped) return@post
            onPeerDisconnected?.invoke(id)
            // Audience: lost the host. Connect again when it is (still) advertised.
            if (!isHost) main.postDelayed({ connectToHost() }, RETRY_DELAY_MS)
        }
    }
}
