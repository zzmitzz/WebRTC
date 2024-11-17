package com.example.webrtc

import WebRTCManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var webRTC: WebRTCManager
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var startCallButton: Button
    private lateinit var endCallButton: Button
    private lateinit var signalingClient: SignalingClient

    private val userId = UUID.randomUUID().toString()
    private var roomId: String? = null
    private var isInitiator = false

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isConnectionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        localView = findViewById(R.id.localView)
        remoteView = findViewById(R.id.remoteView)
        startCallButton = findViewById(R.id.startCallButton)
        endCallButton = findViewById(R.id.endCallButton)

        setupButtons()
        initWebRTC()

    }

    private fun setupButtons() {
        startCallButton.setOnClickListener {
            startCall()
        }

        endCallButton.setOnClickListener {
            endCall()
        }
    }

    private fun startCall() {
        isInitiator = true
        roomId = UUID.randomUUID().toString()
        setupSignalingClient()
        startConnection()
    }

    private fun endCall() {
        signalingClient.stopListening()
        webRTC.release()
        initWebRTC()
        isConnectionStarted = false
        isInitiator = false
        roomId = null
    }

    private fun setupSignalingClient() {
        roomId?.let { room ->
            signalingClient = SignalingClient(room, userId, object : SignalingClient.SignalingClientListener {
                override fun onOfferReceived(description: SessionDescription) {
                    Log.d(TAG, "Received offer")
                    if (!isInitiator) {
                        handleRemoteOffer(description)
                    }
                }

                override fun onAnswerReceived(description: SessionDescription) {
                    Log.d(TAG, "Received answer")
                    if (isInitiator) {
                        handleRemoteAnswer(description)
                    }
                }

                override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
                    Log.d(TAG, "Received ICE candidate")
                    handleRemoteIceCandidate(iceCandidate)
                }
            })
            signalingClient.startListening()
        }
    }

    private fun initWebRTC() {
        webRTC = WebRTCManager(applicationContext)
        webRTC.initialize(object : WebRTCManager.WebRTCCallback {
            override fun onLocalSdpGenerated(sdp: SessionDescription) {
                Log.d(TAG, "Local SDP generated: ${sdp.type}")
                when (sdp.type) {
                    SessionDescription.Type.OFFER -> signalingClient.sendOffer(sdp)
                    SessionDescription.Type.ANSWER -> signalingClient.sendAnswer(sdp)
                    else -> Log.w(TAG, "Unhandled SDP type: ${sdp.type}")
                }
            }

            override fun onIceCandidateGenerated(iceCandidate: IceCandidate) {
                Log.d(TAG, "ICE candidate generated")
                signalingClient.sendIceCandidate(iceCandidate, isInitiator)
            }

            override fun onPeerConnectionError(error: String) {
                Log.e(TAG, "Peer connection error: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection error: $error", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onRemoteStreamReceived(stream: MediaStream) {
                Log.d(TAG, "Remote stream received")
                runOnUiThread {
                    if (stream.videoTracks.isNotEmpty()) {
                        stream.videoTracks[0].addSink(remoteView)
                    }
                }
            }
        })

        webRTC.setupViews(localView, remoteView)
        webRTC.startLocalVideo()
        webRTC.createPeerConnection()
    }

    private fun startConnection() {
        lifecycleScope.launch {
            try {
                val offer = webRTC.createOffer()
                isConnectionStarted = true
                processPendingIceCandidates()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating offer", e)
                Toast.makeText(this@MainActivity, "Error creating offer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRemoteOffer(sessionDescription: SessionDescription) {
        lifecycleScope.launch {
            try {
                webRTC.handleRemoteAnswer(sessionDescription)
                val answer = webRTC.createOffer() // This will create an answer
                isConnectionStarted = true
                processPendingIceCandidates()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling remote offer", e)
                Toast.makeText(this@MainActivity, "Error handling remote offer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRemoteAnswer(sessionDescription: SessionDescription) {
        try {
            webRTC.handleRemoteAnswer(sessionDescription)
            isConnectionStarted = true
            processPendingIceCandidates()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling remote answer", e)
            Toast.makeText(this, "Error handling remote answer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleRemoteIceCandidate(iceCandidate: IceCandidate) {
        if (isConnectionStarted) {
            webRTC.addRemoteIceCandidate(iceCandidate)
        } else {
            pendingIceCandidates.add(iceCandidate)
        }
    }

    private fun processPendingIceCandidates() {
        pendingIceCandidates.forEach { iceCandidate ->
            webRTC.addRemoteIceCandidate(iceCandidate)
        }
        pendingIceCandidates.clear()
    }

    override fun onDestroy() {
        signalingClient.stopListening()
        webRTC.release()
        super.onDestroy()
    }
}