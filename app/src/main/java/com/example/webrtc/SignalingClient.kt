package com.example.webrtc


import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingClient(
    private val roomId: String,
    private val userId: String,
    private val listener: SignalingClientListener
) {
    private val db = FirebaseFirestore.getInstance()
    private var roomDoc = db.collection("rooms").document(roomId)
    private var offerCandidatesCollection = roomDoc.collection("offerCandidates")
    private var answerCandidatesCollection = roomDoc.collection("answerCandidates")

    private var roomListener: ListenerRegistration? = null
    private var offerCandidatesListener: ListenerRegistration? = null
    private var answerCandidatesListener: ListenerRegistration? = null

    interface SignalingClientListener {
        fun onOfferReceived(description: SessionDescription)
        fun onAnswerReceived(description: SessionDescription)
        fun onIceCandidateReceived(iceCandidate: IceCandidate)
    }

    fun sendOffer(sessionDescription: SessionDescription) {
        val offer = hashMapOf(
            "type" to "offer",
            "sdp" to sessionDescription.description,
            "userId" to userId
        )

        roomDoc.set(offer)
            .addOnSuccessListener {
                Log.d(TAG, "Offer sent successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error sending offer: ", e)
            }
    }

    fun sendAnswer(sessionDescription: SessionDescription) {
        val answer = hashMapOf(
            "type" to "answer",
            "sdp" to sessionDescription.description,
            "userId" to userId
        )

        roomDoc.update(answer as Map<String, Any>)
            .addOnSuccessListener {
                Log.d(TAG, "Answer sent successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error sending answer: ", e)
            }
    }

    fun sendIceCandidate(iceCandidate: IceCandidate, isOffer: Boolean) {
        val candidate = hashMapOf(
            "serverUrl" to iceCandidate.serverUrl,
            "sdpMid" to iceCandidate.sdpMid,
            "sdpMLineIndex" to iceCandidate.sdpMLineIndex,
            "sdpCandidate" to iceCandidate.sdp,
            "userId" to userId
        )

        val collection = if (isOffer) offerCandidatesCollection else answerCandidatesCollection

        collection.add(candidate)
            .addOnSuccessListener {
                Log.d(TAG, "ICE candidate sent successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error sending ICE candidate: ", e)
            }
    }

    fun startListening() {
        roomListener = roomDoc.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e(TAG, "Listen failed", e)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val data = snapshot.data
                if (data != null) {
                    val type = data["type"] as String
                    val sdp = data["sdp"] as String
                    val remoteUserId = data["userId"] as String

                    if (remoteUserId != userId) {  // Ignore our own messages
                        when (type) {
                            "offer" -> {
                                listener.onOfferReceived(
                                    SessionDescription(
                                        SessionDescription.Type.OFFER,
                                        sdp
                                    )
                                )
                            }
                            "answer" -> {
                                listener.onAnswerReceived(
                                    SessionDescription(
                                        SessionDescription.Type.ANSWER,
                                        sdp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Listen for remote ICE candidates
        offerCandidatesListener = offerCandidatesCollection.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e(TAG, "Listen failed", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        val remoteUserId = data["userId"] as String
                        if (remoteUserId != userId) {  // Ignore our own candidates
                            handleIceCandidate(data)
                        }
                    }
                }
            }
        }

        answerCandidatesListener = answerCandidatesCollection.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e(TAG, "Listen failed", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        val remoteUserId = data["userId"] as String
                        if (remoteUserId != userId) {  // Ignore our own candidates
                            handleIceCandidate(data)
                        }
                    }
                }
            }
        }
    }

    private fun handleIceCandidate(data: Map<String, Any>) {
        val iceCandidate = IceCandidate(
            data["sdpMid"] as String,
            (data["sdpMLineIndex"] as Long).toInt(),
            data["sdpCandidate"] as String
        )
        listener.onIceCandidateReceived(iceCandidate)
    }

    fun stopListening() {
        roomListener?.remove()
        offerCandidatesListener?.remove()
        answerCandidatesListener?.remove()
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}