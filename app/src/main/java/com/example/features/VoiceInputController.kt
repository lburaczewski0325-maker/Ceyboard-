package com.example.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data class Listening(val partialText: String = "", val rmsDb: Float = 0f) : VoiceInputState
    data class Error(val message: String) : VoiceInputState
}

class VoiceInputController(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(
        languageTag: String = "en-US",
        onFinalResult: (String) -> Unit
    ) {
        if (!isRecognitionAvailable()) {
            _state.value = VoiceInputState.Error("Speech recognition service unavailable on this device.")
            return
        }

        stopListening()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = VoiceInputState.Listening()
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                val current = _state.value
                if (current is VoiceInputState.Listening) {
                    _state.value = current.copy(rmsDb = rmsdB)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network speech error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                    else -> "Voice input error ($error)"
                }
                _state.value = VoiceInputState.Error(msg)
                destroyRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim().orEmpty()
                _state.value = VoiceInputState.Idle
                destroyRecognizer()
                if (transcript.isNotEmpty()) {
                    onFinalResult(transcript)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    val current = _state.value
                    val rms = if (current is VoiceInputState.Listening) current.rmsDb else 0f
                    _state.value = VoiceInputState.Listening(partialText = partial, rmsDb = rms)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = VoiceInputState.Listening()
        recognizer.startListening(intent)
    }

    fun stopListening() {
        runCatching { speechRecognizer?.stopListening() }
        destroyRecognizer()
        _state.value = VoiceInputState.Idle
    }

    fun clearError() {
        if (_state.value is VoiceInputState.Error) {
            _state.value = VoiceInputState.Idle
        }
    }

    private fun destroyRecognizer() {
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }
}
