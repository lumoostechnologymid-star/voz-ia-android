package com.lumoos.vozai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputController(
    context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    private fun getRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        return SpeechRecognizer.createSpeechRecognizer(appContext).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListeningChanged(true)
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onListeningChanged(false)
                override fun onError(error: Int) {
                    onListeningChanged(false)
                    val msg = when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No se entendió la frase"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono"
                        else -> "Error de reconocimiento ($error)"
                    }
                    onError(msg)
                }
                override fun onResults(results: Bundle?) {
                    onListeningChanged(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onFinal(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onPartial(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    fun startListening(languageTag: String = "es-MX") {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { getRecognizer().startListening(intent) }
        catch (e: Exception) { onListeningChanged(false); onError(e.message ?: "No se pudo iniciar") }
    }

    fun stopListening() { try { recognizer?.stopListening() } catch (_: Exception) {}; onListeningChanged(false) }
    fun cancel() { try { recognizer?.cancel() } catch (_: Exception) {}; onListeningChanged(false) }
    fun destroy() { try { recognizer?.destroy() } catch (_: Exception) {}; recognizer = null }
}
