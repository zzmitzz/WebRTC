import android.content.Context
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRTCManager(
    private val context: Context,
    private val stunServer: String = "stun:stun.l.google.com:19302"
) {
    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private val rootEglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var localSurfaceView: SurfaceViewRenderer? = null
    private var remoteSurfaceView: SurfaceViewRenderer? = null

    // Callback interface for WebRTC events
    interface WebRTCCallback {
        fun onLocalSdpGenerated(sdp: SessionDescription)
        fun onIceCandidateGenerated(iceCandidate: IceCandidate)
        fun onPeerConnectionError(error: String)
        fun onRemoteStreamReceived(stream: MediaStream)
    }

    private var callback: WebRTCCallback? = null

    fun initialize(callback: WebRTCCallback) {
        this.callback = callback
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        val encoderFactory = createVideoEncoderFactory()
        val decoderFactory = createVideoDecoderFactory()

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createVideoEncoderFactory(): VideoEncoderFactory {
        return DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            true,
            true
        )
    }

    private fun createVideoDecoderFactory(): VideoDecoderFactory {
        return DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
    }

    fun setupViews(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        localSurfaceView = localView
        remoteSurfaceView = remoteView

        localView.init(rootEglBase.eglBaseContext, null)
        remoteView.init(rootEglBase.eglBaseContext, null)
    }

    fun startLocalVideo() {
        videoCapturer = createVideoCapturer()

        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            rootEglBase.eglBaseContext
        )

        localVideoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(
            surfaceTextureHelper,
            context,
            localVideoSource?.capturerObserver
        )

        localVideoTrack = factory?.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.addSink(localSurfaceView)

        videoCapturer?.startCapture(1280, 720, 30)
    }

    private fun createVideoCapturer(): VideoCapturer {
        return if (Camera2Enumerator.isSupported(context)) {
            createCamera2Capturer()
        } else {
            createCamera1Capturer()
        }
    }

    private fun createCamera2Capturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        deviceNames.forEach { deviceName ->
            if (enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let { return it }
            }
        }

        deviceNames.forEach { deviceName ->
            if (!enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let { return it }
            }
        }

        throw IllegalStateException("No camera found on device")
    }

    private fun createCamera1Capturer(): VideoCapturer {
        val enumerator = Camera1Enumerator(true)
        val deviceNames = enumerator.deviceNames

        deviceNames.forEach { deviceName ->
            if (enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let { return it }
            }
        }

        deviceNames.forEach { deviceName ->
            if (!enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let { return it }
            }
        }

        throw IllegalStateException("No camera found on device")
    }

    fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(stunServer).createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                callback?.onIceCandidateGenerated(iceCandidate)
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}

            override fun onAddStream(mediaStream: MediaStream) {
                callback?.onRemoteStreamReceived(mediaStream)

                mediaStream.videoTracks.firstOrNull()?.addSink(remoteSurfaceView)
            }

            override fun onRemoveStream(mediaStream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, observer)

        // Add local tracks to peer connection
        val streamId = "local_stream"
        val audioTrack = createAudioTrack()
        val videoTrack = localVideoTrack

        if (audioTrack != null && videoTrack != null) {
            peerConnection?.addTrack(audioTrack, listOf(streamId))
            peerConnection?.addTrack(videoTrack, listOf(streamId))
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        val audioConstraints = MediaConstraints()
        localAudioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("audio_track", localAudioSource)
        return localAudioTrack
    }

    suspend fun createOffer(): SessionDescription = suspendCoroutine { continuation ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(sdp)
                        callback?.onLocalSdpGenerated(sdp)
                    }

                    override fun onSetFailure(error: String) {
                        callback?.onPeerConnectionError("Set local SDP error: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {
                callback?.onPeerConnectionError("Create offer error: $error")
            }

            override fun onCreateFailure(error: String) {
                callback?.onPeerConnectionError("Create offer error: $error")
            }
        }, constraints)
    }

    fun handleRemoteAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String) {
                callback?.onPeerConnectionError("Remote SDP error: $error")
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(iceCandidate: IceCandidate) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun release() {
        executor.execute {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            localVideoSource?.dispose()
            localAudioSource?.dispose()
            factory?.dispose()
            peerConnection?.dispose()

            localSurfaceView?.release()
            remoteSurfaceView?.release()
        }
        executor.shutdown()
    }
}