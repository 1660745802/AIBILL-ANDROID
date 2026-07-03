package com.aibill.android.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.response.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AiChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val inputText: String = "",
    val sessionId: String = UUID.randomUUID().toString(),
)

sealed class AiChatEvent {
    data class Error(val message: String) : AiChatEvent()
}

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiApi: AiApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _events = Channel<AiChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(role = "user", content = text.trim())
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                inputText = "",
                isLoading = true,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val request = mapOf(
                    "sessionId" to _uiState.value.sessionId,
                    "message" to text.trim(),
                )
                val response: ApiResponse<Map<String, String>> = aiApi.chat(request)
                if (response.code == 0) {
                    response.data?.get("reply") ?: "AI 未返回有效内容"
                } else {
                    throw RuntimeException(response.message)
                }
            }.onSuccess { reply ->
                val assistantMessage = ChatMessage(
                    role = "assistant",
                    content = reply,
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "AI chat 请求失败")
                _uiState.update { it.copy(isLoading = false) }
                _events.send(AiChatEvent.Error("发送失败: ${e.message}"))
            }
        }
    }

    fun onNewSession() {
        _uiState.update {
            AiChatUiState(sessionId = UUID.randomUUID().toString())
        }
    }
}
