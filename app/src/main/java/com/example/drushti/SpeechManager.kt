package com.example.drushti

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class SpeechManager(
    private val context: Context,
    private val onCommandReceived: (Command) -> Unit
) : TextToSpeech.OnInitListener, RecognitionListener {

    sealed class Command {
        data object Stop : Command()
        data object Repeat : Command()
        data object WhereAmI : Command()
        data object Help : Command()
        data object SwitchLanguage : Command()
        data class LanguageChoice(val language: AppLanguage) : Command()
        data class IdentifyObject(val query: String) : Command()
        data object MoreObjects : Command()
        data class NavigateTo(val destination: String) : Command()
        data object SpeechNotUnderstood : Command()
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    var currentLanguage: AppLanguage = AppLanguage.ENGLISH
        private set

    var isAwaitingLanguageChoice: Boolean = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
        setupAudioVolume()
        initSpeechRecognizer()
    }

    private fun setupAudioVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Volume setup failed", e)
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            speechRecognizer?.setRecognitionListener(this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            setTtsLanguage(currentLanguage)
            tts?.setSpeechRate(0.9f)
            speak(getStringsForLanguage(currentLanguage).readyText)
        }
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        setTtsLanguage(language)
    }

    private fun setTtsLanguage(language: AppLanguage) {
        if (isTtsReady) {
            val result = tts?.setLanguage(language.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    fun playBeep() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Beep error", e)
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isTtsReady) return
        try {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(
                text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                System.currentTimeMillis().toString()
            )
        } catch (e: Exception) {
            Log.e("SpeechManager", "TTS error", e)
        }
    }

    fun speakObstacleWarning(obstacleText: String) {
        speak(obstacleText, flush = true)
    }

    fun stopTts() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("SpeechManager", "Stop TTS error", e)
        }
    }

    fun startListening() {
        stopTts()
        playBeep()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.speechLang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("SpeechManager", "Start listening error", e)
            speak(getStringsForLanguage(currentLanguage).sorryText)
        }
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("SpeechManager", "Destroy error", e)
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) {
            onCommandReceived(Command.SpeechNotUnderstood)
            return
        }
        val topMatch = matches[0].trim()
        Log.d("Drishti", "Recognized: $topMatch")

        if (isAwaitingLanguageChoice) {
            val chosenLanguage = parseLanguageChoice(topMatch)
            if (chosenLanguage != null) {
                onCommandReceived(Command.LanguageChoice(chosenLanguage))
            } else {
                onCommandReceived(Command.SpeechNotUnderstood)
            }
            return
        }

        when {
            isStopCommand(topMatch) -> onCommandReceived(Command.Stop)
            isRepeatCommand(topMatch) -> onCommandReceived(Command.Repeat)
            isWhereAmICommand(topMatch) -> onCommandReceived(Command.WhereAmI)
            isHelpCommand(topMatch) -> onCommandReceived(Command.Help)
            isSwitchLanguageCommand(topMatch) -> onCommandReceived(Command.SwitchLanguage)
            isIdentifyCommand(topMatch) -> onCommandReceived(Command.IdentifyObject(topMatch))
            isMoreCommand(topMatch) -> onCommandReceived(Command.MoreObjects)
            else -> {
                val cleanDestination = cleanDestinationQuery(topMatch)
                if (cleanDestination.isNotBlank()) {
                    onCommandReceived(Command.NavigateTo(cleanDestination))
                } else {
                    onCommandReceived(Command.SpeechNotUnderstood)
                }
            }
        }
    }

    override fun onError(error: Int) {
        Log.e("SpeechManager", "Speech error code: $error")
        onCommandReceived(Command.SpeechNotUnderstood)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun isStopCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("stop") || lower.contains("halt") || lower.contains("end") ||
                lower.contains("ruk") || lower.contains("रुक") || lower.contains("रुको") || lower.contains("बंद") ||
                lower.contains("nillisi") || lower.contains("ನಿಲ್ಲಿಸಿ") || lower.contains("ನಿಲ್ಸು")
    }

    private fun isRepeatCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("repeat") || lower.contains("again") ||
                lower.contains("dobara") || lower.contains("दोहरा") || lower.contains("फिर से") ||
                lower.contains("punaravartisi") || lower.contains("ಪುನರಾವರ್ತಿಸಿ") || lower.contains("ಮತ್ತೆ")
    }

    private fun isWhereAmICommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("where am i") || lower.contains("where i am") || lower.contains("location") ||
                lower.contains("kahan") || lower.contains("कहाँ") || lower.contains("स्थान") ||
                lower.contains("elliddene") || lower.contains("ಎಲ್ಲಿದ್ದೇನೆ") || lower.contains("ಸ್ಥಳ")
    }

    private fun isHelpCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("help") || lower.contains("command") ||
                lower.contains("madad") || lower.contains("मदद") || lower.contains("सहायता") ||
                lower.contains("sahaya") || lower.contains("ಸಹಾಯ") || lower.contains("ಆಜ್ಞೆ")
    }

    private fun isSwitchLanguageCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("switch language") || lower.contains("change language") || lower.contains("language") ||
                lower.contains("bhasha") || lower.contains("भाषा") || lower.contains("लैंग्वेज") ||
                lower.contains("bhashe") || lower.contains("ಭಾಷೆ")
    }

    private fun isIdentifyCommand(text: String): Boolean {
        val lower = text.lowercase()
        val triggers = listOf(
            "what is this", "what is it", "what is in front", "what is beside me", "what is around me", "identify", "what object",
            "yeh kya hai", "kya hai", "samne kya hai", "bagal me kya hai", "aspas kya hai", "pehchano",
            "यह क्या है", "क्या है", "सामने क्या है", "बगल में क्या है", "आसपास क्या है", "पहचानो",
            "idu enu", "enide", "munde enide", "pakkadalli enide", "suttalu enide", "gurutisi",
            "ಇದು ಏನು", "ಏನಿದೆ", "ಮುಂದೆ ಏನಿದೆ", "ಪಕ್ಕದಲ್ಲಿ ಏನಿದೆ", "ಸುತ್ತಲೂ ಏನಿದೆ", "ಗುರುತಿಸಿ"
        )
        return triggers.any { lower.contains(it) }
    }

    private fun isMoreCommand(text: String): Boolean {
        val lower = text.lowercase()
        val triggers = listOf(
            "more", "say more", "next", "others",
            "aur", "adhik", "agla", "और", "अधिक", "अगला",
            "innastu", "innondu", "ಇನ್ನಷ್ಟು", "ಇನ್ನೊಂದು"
        )
        return triggers.any { lower.contains(it) }
    }

    private fun parseLanguageChoice(text: String): AppLanguage? {
        val lower = text.lowercase()
        if (lower.contains("hindi") || lower.contains("हिंदी") || lower.contains("हिन") || lower.contains("ಹಿಂದಿ")) {
            return AppLanguage.HINDI
        }
        if (lower.contains("kannada") || lower.contains("कन्नड़") || lower.contains("ಕನ್ನಡ")) {
            return AppLanguage.KANNADA
        }
        if (lower.contains("english") || lower.contains("अंग्रेजी") || lower.contains("इंग्लिश") || lower.contains("ಇಂಗ್ಲಿಷ್")) {
            return AppLanguage.ENGLISH
        }
        return null
    }

    private fun cleanDestinationQuery(text: String): String {
        val lower = text.lowercase()
        val prefixes = listOf(
            "take me to the ", "take me to ", "go to the ", "go to ",
            "navigate to ", "find ", "head to ", "to ",
            "ले चलो ", "जाओ ", "कहा ", "ಕರೆದೊಯ್ಯಿರಿ "
        )
        var result = text
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                result = text.substring(prefix.length).trim()
                break
            }
        }
        return result
    }
}
