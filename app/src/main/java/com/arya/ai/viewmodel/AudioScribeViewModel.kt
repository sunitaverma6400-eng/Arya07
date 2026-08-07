package com.arya.ai.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Audio Scribe" here is implemented with Android's built-in [SpeechRecognizer] for the
 * actual audio→text step, then routes the transcript through Arya's free online model
 * ([generateOnline]) for translation/cleanup.
 */
class AudioScribeViewModel(
    app: Application,
    private val generateOnline: suspend (String) -> String
) : AndroidViewModel(app) {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    fun startListening() {
        val context = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _error.value = "Is device par speech recognition available nahi hai."
            return
        }
        _transcript.value = ""
        _translation.value = ""
        _error.value = null

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _isListening.value = false }
                override fun onError(error: Int) {
                    _isListening.value = false
                    _error.value = "Recognition error (code $error)"
                }
                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    _transcript.value = matches?.firstOrNull() ?: ""
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { _transcript.value = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            startListening(intent)
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }

    fun translateTranscript(targetLanguage: String) {
        val text = _transcript.value
        if (text.isBlank()) return
        _isTranslating.value = true
        _translation.value = ""
        viewModelScope.launch {
            try {
                val prompt = "Translate the following text to $targetLanguage. Only output the translation:\n\n$text"
                _translation.value = generateOnline(prompt)
            } catch (e: Exception) {
                _translation.value = "⚠️ Error: ${e.message}"
            } finally {
                _isTranslating.value = false
            }
        }
    }

    override fun onCleared() {
        recognizer?.destroy()
        super.onCleared()
    }
}
