package com.nosmai.quickstart.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host:     Camera2 -> Nosmai (effects + preview) -> I420 frames -> WebRTC video track.
 * Audience: a WebRTC renderer showing the host's video track.
 *
 * On Android the app owns the camera: [Camera2Helper] opens it and hands every
 * frame to Nosmai, which renders the preview and returns the processed frame.
 */
class LiveActivity : AppCompatActivity() {
    private val config by lazy { DemoConfig(applicationContext) }


    companion object {
        const val EXTRA_IS_HOST = "isHost"
        private const val PERMISSION_REQUEST = 100
        /** Value of [NosmaiSDK.FrameData.format] for planar I420. */
        private const val NOSMAI_FORMAT_I420 = 1
        /** WebRTC's native library is loaded once per process. */
        private var webRtcInitialized = false
    }

    private val isHost by lazy { intent.getBooleanExtra(EXTRA_IS_HOST, false) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var videoContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var fpsText: TextView
    private var effectsPanel: EffectsPanel? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var signaling: LocalSignaling? = null
    /** One peer connection per signaling peer: every viewer for the host, the host for a viewer. */
    private val connections = HashMap<String, PeerConnection>()
    /** ICE candidates that arrived before the remote description was set, per peer. */
    private val pendingCandidates = HashMap<String, MutableList<IceCandidate>>()
    private val remoteDescriptionSet = HashSet<String>()

    // Host: the tracks offered to every viewer.
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // Audience: where the host's video is drawn.
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    /** Counts rendered frames so the audience can show an FPS figure. */
    private val frameCounter = VideoSink { framesSinceTick.incrementAndGet() }

    // Host: Nosmai preview and the camera feeding it.
    private var previewView: NosmaiPreviewView? = null
    private var camera: Camera2Helper? = null
    private var nosmaiStarted = false
    private var isFrontCamera = true

    /** Host: true while frames go to WebRTC. Guarded by [frameLock] together with [videoSource]. */
    private var pushing = false
    private val frameLock = Any()
    private val framesSinceTick = AtomicInteger(0)

    private var liveStatus = "Preparing…"
    private var toastToken = 0
    private var stopped = false

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoContainer = findViewById(R.id.videoContainer)
        statusText = findViewById(R.id.statusText)
        fpsText = findViewById(R.id.fpsText)
        buildControls()

        setUpWebRTC()
        main.postDelayed(fpsTick, 1000)
        if (isHost) {
            if (hasPermissions()) startNosmaiCamera() else requestPermissions()
        } else {
            startSignaling()
        }
    }

    /** Release the camera and pause the preview while in the background. */
    override fun onPause() {
        // onDestroy can run after the next screen has opened, and Nosmai is a single
        // shared instance, so end the session as soon as this screen is closing.
        if (isFinishing) stopSession()
        if (isHost && nosmaiStarted) {
            runCatching { camera?.stopCamera() }
            camera = null
            runCatching { previewView?.onPause() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isHost && nosmaiStarted && !stopped) {
            runCatching { previewView?.onResume() }
            if (camera == null) startCamera()
        }
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    // ---- UI ----

    private fun buildControls() {
        val closeButton = findViewById<MaterialButton>(R.id.closeButton)
        closeButton.text = if (isHost) "End Live" else "Leave"
        closeButton.setOnClickListener { finish() }
        if (!isHost) return

        val panelView = findViewById<View>(R.id.effectsPanel)
        panelView.visibility = View.VISIBLE
        effectsPanel = EffectsPanel(panelView) { showToast(it) }

        findViewById<MaterialButton>(R.id.flipButton).apply {
            visibility = View.VISIBLE
            setOnClickListener { flipCamera() }
        }
        findViewById<MaterialButton>(R.id.effectsButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                panelView.visibility = if (panelView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setStatus(text: String) {
        if (text == liveStatus) return
        liveStatus = text
        statusText.text = text
    }

    /** Briefly shows effect feedback in the status pill, then restores the live status. */
    private fun showToast(text: String) {
        val token = ++toastToken
        statusText.text = text
        main.postDelayed({ if (toastToken == token) statusText.text = liveStatus }, 2500)
    }

    /**
     * Host FPS = filtered frames handed to WebRTC each second.
     * Audience FPS = frames of the host's video rendered each second.
     */
    private val fpsTick = object : Runnable {
        override fun run() {
            val frames = framesSinceTick.getAndSet(0)
            val active = if (isHost) pushing else remoteVideoTrack != null
            if (active) fpsText.text = "$frames FPS"
            main.postDelayed(this, 1000)
        }
    }

    // ---- WebRTC ----

    private fun setUpWebRTC() {
        if (!webRtcInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            webRtcInitialized = true
        }
        // Play the stream through the loudspeaker, as a live stream should.
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            isSpeakerphoneOn = true
        }

        val egl = EglBase.create()
        eglBase = egl
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        this.factory = factory

        if (isHost) {
            // Nosmai owns the camera: instead of a camera capturer we create a plain
            // video source and hand it Nosmai's frames ourselves (see sendFrame).
            val source = factory.createVideoSource(false)
            source.capturerObserver.onCapturerStarted(true)
            videoSource = source
            videoTrack = factory.createVideoTrack("video0", source)
            audioSource = factory.createAudioSource(MediaConstraints())
            audioTrack = factory.createAudioTrack("audio0", audioSource)
        } else {
            val renderer = SurfaceViewRenderer(this)
            renderer.init(egl.eglBaseContext, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.setEnableHardwareScaler(true)
            remoteRenderer = renderer
            videoContainer.addView(renderer)
        }
    }

    /** Start finding the other phone. The host advertises the room; viewers look for it. */
    private fun startSignaling() {
        if (stopped) return
        val signaling = LocalSignaling(this, config.roomName, isHost)
        signaling.onError = { message -> onMain { setStatus(message) } }
        signaling.onPeerConnected = { peer -> peerConnected(peer) }
        signaling.onPeerDisconnected = { peer -> peerDisconnected(peer) }
        signaling.onMessage = { message, peer -> handle(message, peer) }
        signaling.start()
        this.signaling = signaling
        if (isHost) {
            updateHostStatus()
            startPushingFrames()
        } else {
            setStatus("Looking for host on this Wi-Fi…")
        }
    }

    private fun makeConnection(peer: String): PeerConnection? {
        // A public STUN server is enough on the same network. Add your own TURN
        // server here to connect phones on different networks.
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = factory?.createPeerConnection(config, PeerEvents(peer)) ?: return null
        closeConnection(peer)
        connections[peer] = connection
        pendingCandidates[peer] = mutableListOf()
        return connection
    }

    private fun closeConnection(peer: String) {
        val connection = connections.remove(peer) ?: return
        pendingCandidates.remove(peer)
        remoteDescriptionSet.remove(peer)
        // The remote track belongs to the connection, so let go of it first.
        if (!isHost) detachRemoteTrack()
        connection.dispose()
    }

    private fun detachRemoteTrack() {
        val track = remoteVideoTrack ?: return
        remoteVideoTrack = null
        runCatching {
            remoteRenderer?.let { track.removeSink(it) }
            track.removeSink(frameCounter)
        }
    }

    /** The signaling channel to [peer] is up. The host starts the WebRTC handshake. */
    private fun peerConnected(peer: String) {
        if (stopped) return
        if (!isHost) {
            setStatus("Found host — connecting…")
            return
        }
        val connection = makeConnection(peer) ?: return
        connection.addTrack(videoTrack, listOf("stream0"))
        connection.addTrack(audioTrack, listOf("stream0"))
        connection.createOffer(Sdp(onCreated = { offer ->
            connection.setLocalDescription(Sdp(onSet = {
                signaling?.send(JSONObject().put("type", "offer").put("sdp", offer.description), peer)
            }), offer)
        }), MediaConstraints())
    }

    private fun peerDisconnected(peer: String) {
        if (stopped) return
        closeConnection(peer)
        if (isHost) {
            updateHostStatus()
        } else {
            fpsText.text = "-- FPS"
            setStatus("Host left the stream")
        }
    }

    /** Offer / answer / ICE candidates from the other phone. */
    private fun handle(message: JSONObject, peer: String) {
        if (stopped) return
        when (message.optString("type")) {
            "offer" -> { // audience
                val connection = makeConnection(peer) ?: return
                val offer = SessionDescription(SessionDescription.Type.OFFER, message.optString("sdp"))
                connection.setRemoteDescription(Sdp(onSet = {
                    flushCandidates(peer)
                    connection.createAnswer(Sdp(onCreated = { answer ->
                        connection.setLocalDescription(Sdp(onSet = {
                            signaling?.send(JSONObject().put("type", "answer").put("sdp", answer.description), peer)
                        }), answer)
                    }), MediaConstraints())
                }), offer)
            }
            "answer" -> { // host
                val connection = connections[peer] ?: return
                val answer = SessionDescription(SessionDescription.Type.ANSWER, message.optString("sdp"))
                connection.setRemoteDescription(Sdp(onSet = { flushCandidates(peer) }), answer)
            }
            "candidate" -> {
                val connection = connections[peer] ?: return
                val candidate = IceCandidate(message.optString("sdpMid"), message.optInt("sdpMLineIndex"),
                    message.optString("candidate"))
                // A candidate cannot be added before the remote description is set.
                if (peer in remoteDescriptionSet) connection.addIceCandidate(candidate)
                else pendingCandidates[peer]?.add(candidate)
            }
        }
    }

    private fun flushCandidates(peer: String) {
        val connection = connections[peer] ?: return
        remoteDescriptionSet.add(peer)
        pendingCandidates[peer]?.forEach { connection.addIceCandidate(it) }
        pendingCandidates[peer]?.clear()
    }

    private fun updateHostStatus() {
        val viewers = connections.values.count {
            it.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
        }
        setStatus("LIVE in ${config.roomName} — " + when (viewers) {
            0 -> "waiting for viewers"
            1 -> "1 viewer"
            else -> "$viewers viewers"
        })
    }

    /**
     * Runs the SDP callbacks on the main thread, and only while [LiveActivity] is still live.
     * WebRTC calls its observers on its own thread.
     */
    private inner class Sdp(
        private val onCreated: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onMain { onCreated(description) }
        override fun onSetSuccess() = onMain { onSet() }
        override fun onCreateFailure(error: String?) { onMain { setStatus("Could not create the session description") } }
        override fun onSetFailure(error: String?) { onMain { setStatus("Could not apply the session description") } }
    }

    /** Events of the peer connection to one [peer], hopped to the main thread. */
    private inner class PeerEvents(private val peer: String) : PeerConnection.Observer {
        /** Send our ICE candidates to the other phone. */
        override fun onIceCandidate(candidate: IceCandidate) = onMain {
            signaling?.send(JSONObject()
                .put("type", "candidate")
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex), peer)
        }

        /** Audience: the host's video track arrived. */
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            onMain {
                if (isHost || !connections.containsKey(peer)) return@onMain
                detachRemoteTrack()
                remoteRenderer?.let { track.addSink(it) }
                track.addSink(frameCounter)
                remoteVideoTrack = track
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = onMain {
            if (!connections.containsKey(peer)) return@onMain
            if (isHost) {
                updateHostStatus()
                return@onMain
            }
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> setStatus("Watching host")
                PeerConnection.PeerConnectionState.DISCONNECTED -> setStatus("Reconnecting…")
                PeerConnection.PeerConnectionState.FAILED ->
                    setStatus("Connection failed — are both phones on the same Wi-Fi?")
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun onMain(action: () -> Unit) {
        main.post { if (!stopped) action() }
    }

    // ---- Nosmai (host only) ----

    private fun startNosmaiCamera() {
        if (stopped) return
        setStatus("Starting Nosmai…")
        NosmaiSDK.initialize(applicationContext, config.nosmaiLicenseKey)

        // Mount the preview and start the effects pipeline.
        val preview = NosmaiPreviewView(this)
        previewView = preview
        videoContainer.addView(preview)
        preview.initializePipeline()
        NosmaiSDK.startProcessing(preview)

        // startProcessing() does not open a camera: the app does, and feeds Nosmai.
        startCamera()

        nosmaiStarted = true
        effectsPanel?.nosmaiReady()
        startSignaling()
    }

    private fun startCamera() {
        val helper = Camera2Helper(this, isFrontCamera)
        camera = helper
        helper.setInputMode(Camera2Helper.InputMode.YUV)

        NosmaiSDK.setCameraFacing(isFrontCamera)
        previewView?.setCameraOrientation(isFrontCamera, helper.sensorOrientation)

        helper.setFrameCallback { y, u, v, width, height, yStride, uStride, vStride, uPixelStride, vPixelStride ->
            previewView?.processYuvFrame(y, u, v, width, height, yStride, uStride, vStride,
                uPixelStride, vPixelStride, frameRotation(helper))
        }
        helper.setCameraErrorCallback { reason -> main.post { if (!stopped) setStatus("Camera error: $reason") } }
        helper.startCamera()
    }

    /**
     * Rotation Nosmai should apply to the incoming camera frame. The values are the
     * SDK's rotation modes (1 = rotate left, 2 = rotate right), not degrees.
     */
    private fun frameRotation(helper: Camera2Helper): Int {
        val sensor = helper.sensorOrientation
        return if (isFrontCamera) {
            when (sensor) {
                90 -> 2
                270 -> 1
                else -> 0
            }
        } else {
            if (sensor == 90) 2 else 1
        }
    }

    private fun flipCamera() {
        if (!nosmaiStarted) return
        isFrontCamera = !isFrontCamera
        runCatching { camera?.stopCamera() }
        startCamera()
    }

    /** Forward every filtered frame from Nosmai to the WebRTC video track. */
    private fun startPushingFrames() {
        if (!nosmaiStarted || stopped) return
        synchronized(frameLock) {
            if (pushing) return
            pushing = true
        }
        framesSinceTick.set(0)
        // DUAL_OUTPUT keeps the on-screen preview and also delivers each processed frame.
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
        NosmaiSDK.setFrameCallback { frame -> sendFrame(frame) }
    }

    /** Nosmai's frame thread, once per processed frame. */
    private fun sendFrame(frame: NosmaiSDK.FrameData?) {
        val data = frame?.pixelBuffer ?: return
        val width = frame.width
        val height = frame.height
        val lumaSize = width * height
        val chromaSize = (width / 2) * (height / 2)
        if (frame.format != NOSMAI_FORMAT_I420 || width <= 0 || height <= 0 ||
            width % 2 != 0 || height % 2 != 0 || data.size < lumaSize + 2 * chromaSize) return

        // Nosmai reuses its byte array, so copy the three planes into a WebRTC buffer.
        val buffer = JavaI420Buffer.allocate(width, height)
        buffer.dataY.put(data, 0, lumaSize)
        buffer.dataU.put(data, lumaSize, chromaSize)
        buffer.dataV.put(data, lumaSize + chromaSize, chromaSize)

        // Frames are sent exactly as Nosmai produces them: upright, so no rotation.
        val videoFrame = VideoFrame(buffer, 0, System.nanoTime())
        try {
            synchronized(frameLock) {
                if (pushing) {
                    videoSource?.capturerObserver?.onFrameCaptured(videoFrame)
                    framesSinceTick.incrementAndGet()
                }
            }
        } finally {
            videoFrame.release()
        }
    }

    // ---- Teardown ----

    private fun stopSession() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        // After this block no frame is in flight, so the video source can be disposed.
        synchronized(frameLock) { pushing = false }
        if (isHost) {
            runCatching { NosmaiSDK.setFrameCallback(null) }
            runCatching { camera?.stopCamera() }
            camera = null
        }
        signaling?.stop()
        signaling = null

        connections.keys.toList().forEach { closeConnection(it) }
        videoSource?.capturerObserver?.onCapturerStopped()
        videoTrack?.dispose()
        audioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        videoTrack = null
        audioTrack = null
        videoSource = null
        audioSource = null
        remoteRenderer?.release()
        remoteRenderer = null
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            @Suppress("DEPRECATION")
            isSpeakerphoneOn = false
            mode = AudioManager.MODE_NORMAL
        }

        effectsPanel?.release()
        if (nosmaiStarted) {
            nosmaiStarted = false
            // cleanup() is the full teardown: it clears the effects and stops processing.
            runCatching { NosmaiSDK.cleanup() }
        }
    }

    // ---- Permissions ----

    private fun hasPermissions() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() = ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (hasPermissions()) startNosmaiCamera() else setStatus("Camera and microphone permission are required")
    }
}
