package com.nora.agent.realtime

import android.content.Context
import com.nora.agent.network.NoraApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NoraRealtimeClient(
    private val context: Context,
    private val api: NoraApi = NoraApi(),
    private val onEvent: (String) -> Unit,
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handledToolCallIds = mutableSetOf<String>()

    suspend fun connect() {
        val factory = ensureFactory()
        val peerConnection = createPeerConnection(factory)
        this.peerConnection = peerConnection

        val audioSource = factory.createAudioSource(professionalAudioConstraints())
        val audioTrack = factory.createAudioTrack("nora-local-audio", audioSource)
        this.audioSource = audioSource
        this.audioTrack = audioTrack
        peerConnection.addTrack(audioTrack)

        val channel = peerConnection.createDataChannel("oai-events", DataChannel.Init())
        this.dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                onEvent("Realtime data channel: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val event = bytes.decodeToString()
                onEvent(event)
                handleToolCalls(event)
            }
        })

        val offer = peerConnection.createOfferSuspend()
        peerConnection.setLocalDescriptionSuspend(offer)

        val answerSdp = api.exchangeSdp(offer.description)
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection.setRemoteDescriptionSuspend(answer)
    }

    fun disconnect() {
        dataChannel?.close()
        peerConnection?.close()
        audioTrack?.dispose()
        audioSource?.dispose()
        toolScope.cancel()
        toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(handledToolCallIds) {
            handledToolCallIds.clear()
        }
        dataChannel = null
        peerConnection = null
        audioTrack = null
        audioSource = null
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )

        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private fun professionalAudioConstraints(): MediaConstraints =
        MediaConstraints().apply {
            listOf(
                "googEchoCancellation" to "true",
                "googAutoGainControl" to "true",
                "googNoiseSuppression" to "true",
                "googHighpassFilter" to "true",
                "googTypingNoiseDetection" to "true",
            ).forEach { (key, value) ->
                mandatory.add(MediaConstraints.KeyValuePair(key, value))
            }
        }

    private fun createPeerConnection(factory: PeerConnectionFactory): PeerConnection {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    onEvent("Signaling: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    onEvent("ICE: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) {
                    onEvent("Remote data channel: ${channel.label()}")
                }

                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    onEvent("Nora audio track connected.")
                }
            },
        ) ?: error("Unable to create WebRTC peer connection")
    }

    private fun handleToolCalls(event: String) {
        val json = runCatching { JSONObject(event) }.getOrNull() ?: return
        val toolCalls = mutableListOf<JSONObject>()

        when (json.optString("type")) {
            "response.output_item.done" -> {
                json.optJSONObject("item")?.let(toolCalls::add)
            }
            "conversation.item.done",
            "conversation.item.completed" -> {
                json.optJSONObject("item")?.let(toolCalls::add)
            }
            "response.function_call_arguments.done" -> {
                val name = json.optString("name")
                val callId = json.optString("call_id")
                if (name.isNotBlank() && callId.isNotBlank()) {
                    toolCalls.add(
                        JSONObject()
                            .put("type", "function_call")
                            .put("name", name)
                            .put("call_id", callId)
                            .put("arguments", json.optString("arguments").ifBlank { "{}" }),
                    )
                }
            }
            "response.done" -> {
                val output = json.optJSONObject("response")?.optJSONArray("output") ?: return
                for (index in 0 until output.length()) {
                    output.optJSONObject(index)?.let(toolCalls::add)
                }
            }
        }

        toolCalls
            .filter { it.optString("type") == "function_call" }
            .forEach(::executeToolCall)
    }

    private fun executeToolCall(item: JSONObject) {
        val callId = item.optString("call_id").takeIf { it.isNotBlank() } ?: return
        val name = item.optString("name").takeIf { it.isNotBlank() } ?: return
        val argumentsJson = item.optString("arguments").ifBlank { "{}" }

        if (alreadyHandled(callId)) return

        toolScope.launch {
            emitLocalEvent("nora.tool.started", name, null)
            val output = runCatching {
                api.executeToolCall(name, argumentsJson)
            }.getOrElse { error ->
                JSONObject()
                    .put("type", "tool_error")
                    .put("tool", name)
                    .put("error", error.message ?: "Nora tool failed.")
                    .toString()
            }

            if (sendToolResult(callId, output)) {
                sendToolResponseRequest(name)
                emitLocalEvent("nora.tool.completed", name, null)
            }
        }
    }

    private fun alreadyHandled(callId: String): Boolean = synchronized(handledToolCallIds) {
        !handledToolCallIds.add(callId)
    }

    private fun sendToolResult(callId: String, outputJson: String): Boolean {
        val event = JSONObject()
            .put("event_id", "nora_tool_output_${System.nanoTime()}")
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", outputJson),
            )

        return sendClientEvent(event)
    }

    private fun sendToolResponseRequest(name: String): Boolean {
        val event = JSONObject()
            .put("event_id", "nora_tool_response_${System.nanoTime()}")
            .put("type", "response.create")
            .put(
                "response",
                JSONObject()
                    .put("output_modalities", JSONArray().put("audio"))
                    .put(
                        "instructions",
                        "Speak a concise answer using the latest function_call_output from $name. " +
                            "Do not call another tool for this same request. " +
                            "For weather or news, read the spokenBrief first when present. " +
                            "If the tool output contains an error, apologize briefly and ask the user to try again.",
                    ),
            )

        return sendClientEvent(event)
    }

    private fun sendClientEvent(event: JSONObject): Boolean {
        val channel = dataChannel
        if (channel == null) {
            emitLocalEvent(
                type = "nora.tool.error",
                name = null,
                message = "Nora live data channel is not ready.",
            )
            return false
        }
        if (channel.state() != DataChannel.State.OPEN) {
            emitLocalEvent(
                type = "nora.tool.error",
                name = null,
                message = "Nora live data channel is ${channel.state()}.",
            )
            return false
        }

        val bytes = event.toString().toByteArray(Charsets.UTF_8)
        val didSend = channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        if (!didSend) {
            emitLocalEvent(
                type = "nora.tool.error",
                name = null,
                message = "Nora could not send live data back to the voice session.",
            )
        }
        return didSend
    }

    private fun emitLocalEvent(type: String, name: String?, message: String?) {
        val json = JSONObject().put("type", type)
        name?.let { json.put("name", it) }
        message?.let { json.put("message", it) }
        onEvent(json.toString())
    }
}

private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    continuation.resume(description)
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }

                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(description: SessionDescription) =
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }

                override fun onSetFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }

                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            description,
        )
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(description: SessionDescription) =
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }

                override fun onSetFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }

                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            },
            description,
        )
    }
