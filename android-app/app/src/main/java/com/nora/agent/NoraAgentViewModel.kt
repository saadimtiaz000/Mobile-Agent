package com.nora.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nora.agent.audio.AudioRouteManager
import com.nora.agent.realtime.NoraRealtimeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class NoraAgentState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isHearingUser: Boolean = false,
    val isResponding: Boolean = false,
    val statusText: String = "Ready when you say Nora.",
    val lastEvent: String = "Connect earbuds, then start a voice session.",
)

class NoraAgentViewModel(application: Application) : AndroidViewModel(application) {
    private val audioRouteManager = AudioRouteManager(application)
    private val realtimeClient = NoraRealtimeClient(
        context = application,
        onEvent = ::handleRealtimeEvent,
    )

    private val mutableState = MutableStateFlow(NoraAgentState())
    val state: StateFlow<NoraAgentState> = mutableState

    fun startSession() {
        if (state.value.isConnecting || state.value.isConnected) return

        mutableState.update {
            it.copy(
                isConnecting = true,
                statusText = "Connecting Nora...",
                lastEvent = "Preparing Bluetooth audio and WebRTC.",
            )
        }

        viewModelScope.launch {
            runCatching {
                audioRouteManager.preferBluetooth()
                realtimeClient.connect()
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        isHearingUser = false,
                        isResponding = false,
                        statusText = "Nora is listening.",
                        lastEvent = "Speak naturally. Nora will reply in your earbuds when connected.",
                    )
                }
            }.onFailure { error ->
                realtimeClient.disconnect()
                audioRouteManager.clearPreferredDevice()
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isHearingUser = false,
                        isResponding = false,
                        statusText = "Nora could not connect.",
                        lastEvent = error.message ?: "Unknown connection error.",
                    )
                }
            }
        }
    }

    fun stopSession() {
        realtimeClient.disconnect()
        audioRouteManager.clearPreferredDevice()
        mutableState.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                isHearingUser = false,
                isResponding = false,
                statusText = "Session ended.",
                lastEvent = "Nora is idle.",
            )
        }
    }

    fun clearEventLog() {
        mutableState.update {
            it.copy(lastEvent = "")
        }
    }

    override fun onCleared() {
        realtimeClient.disconnect()
        audioRouteManager.clearPreferredDevice()
        super.onCleared()
    }

    private fun handleRealtimeEvent(event: String) {
        val update = runCatching {
            val json = JSONObject(event)
            when (json.optString("type")) {
                "session.updated", "session.created" -> RealtimeUiUpdate(
                    statusText = "Nora is listening.",
                    lastEvent = "Nora voice session is ready.",
                    isHearingUser = false,
                    isResponding = false,
                )
                "input_audio_buffer.speech_started" -> RealtimeUiUpdate(
                    statusText = "I hear you.",
                    lastEvent = "Keep speaking naturally.",
                    isHearingUser = true,
                    isResponding = false,
                )
                "input_audio_buffer.speech_stopped",
                "input_audio_buffer.committed",
                "conversation.item.input_audio_transcription.delta" -> RealtimeUiUpdate(
                    statusText = "Thinking...",
                    lastEvent = "Nora is listening closely.",
                    isHearingUser = false,
                    isResponding = true,
                )
                "response.created",
                "response.output_item.added",
                "response.content_part.added",
                "response.function_call_arguments.delta",
                "response.audio.delta",
                "response.output_audio.delta",
                "response.output_audio_transcript.delta",
                "response.audio_transcript.delta" -> RealtimeUiUpdate(
                    statusText = "Nora is responding.",
                    lastEvent = "Nora is speaking.",
                    isHearingUser = false,
                    isResponding = true,
                )
                "response.audio_transcript.done" -> json.optString("transcript")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        RealtimeUiUpdate(
                            statusText = "Nora is listening.",
                            lastEvent = it,
                            isHearingUser = false,
                            isResponding = false,
                        )
                    }
                    ?: RealtimeUiUpdate(
                        statusText = "Nora is listening.",
                        lastEvent = "Nora answered.",
                        isHearingUser = false,
                        isResponding = false,
                    )
                "response.done" -> RealtimeUiUpdate(
                    statusText = "Nora is listening.",
                    lastEvent = "Nora answered.",
                    isHearingUser = false,
                    isResponding = false,
                )
                "nora.tool.started" -> RealtimeUiUpdate(
                    statusText = "Checking live updates...",
                    lastEvent = "Nora is checking ${toolLabel(json.optString("name"))}.",
                    isHearingUser = false,
                    isResponding = true,
                )
                "nora.tool.completed" -> RealtimeUiUpdate(
                    statusText = "Nora is responding.",
                    lastEvent = "Live ${toolLabel(json.optString("name"))} data is ready.",
                    isHearingUser = false,
                    isResponding = true,
                )
                "nora.tool.error" -> RealtimeUiUpdate(
                    statusText = "Live update failed.",
                    lastEvent = json.optString("message")
                        .takeIf { it.isNotBlank() }
                        ?: "Nora could not fetch live updates.",
                    isHearingUser = false,
                    isResponding = false,
                )
                "error" -> RealtimeUiUpdate(
                    statusText = "Nora hit a session error.",
                    lastEvent = json.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Nora received an error from the voice session.",
                    isHearingUser = false,
                    isResponding = false,
                )
                else -> null
            }
        }.getOrNull()

        if (update == null) return

        mutableState.update {
            it.copy(
                statusText = update.statusText ?: it.statusText,
                lastEvent = update.lastEvent.take(220),
                isHearingUser = update.isHearingUser ?: it.isHearingUser,
                isResponding = update.isResponding ?: it.isResponding,
            )
        }
    }
}

private fun toolLabel(name: String): String = when (name) {
    "get_current_weather" -> "weather"
    "get_live_news" -> "news"
    else -> "live data"
}

private data class RealtimeUiUpdate(
    val statusText: String? = null,
    val lastEvent: String,
    val isHearingUser: Boolean? = null,
    val isResponding: Boolean? = null,
)
