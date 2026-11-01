package com.example.drushti

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var speechManager: SpeechManager
    private lateinit var navigationManager: NavigationManager
    private lateinit var indoorManager: IndoorManager
    private var obstacleDetector: ObstacleDetector? = null
    private val objectIdentifier by lazy { ObjectIdentifier() }

    private var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)
    private var isPermissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        isPermissionGranted = allGranted
        if (allGranted) {
            setupServices()
        } else {
            speechManager.speak(getStringsForLanguage(currentLanguage).permissionErrorText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speechManager = SpeechManager(this) { command ->
            handleCommand(command)
        }

        navigationManager = NavigationManager(
            context = this,
            onInstruction = { instruction ->
                speechManager.speak(instruction)
            },
            onError = { errorText ->
                speechManager.speak(errorText)
            }
        )

        indoorManager = IndoorManager(
            context = this,
            onKeepWalking = {
                val strings = getStringsForLanguage(currentLanguage)
                speechManager.speak(strings.keepWalkingText)
            }
        )

        checkAndRequestPermissions()

        setContent {
            DrishtiScreen(
                language = currentLanguage,
                onTap = {
                    onScreenTap()
                }
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            isPermissionGranted = true
            setupServices()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun setupServices() {
        try {
            obstacleDetector = ObstacleDetector(
                context = this,
                lifecycleOwner = this,
                onObstacleDetected = { turnDirection ->
                    val strings = getStringsForLanguage(currentLanguage)
                    if (navigationManager.isIndoorActive || indoorManager.isIndoorActive) {
                        val turnText = if (turnDirection == "left") strings.turnLeftText else strings.turnRightText
                        speechManager.speakObstacleWarning("${strings.stopObstacleText} $turnText")
                    } else {
                        speechManager.speakObstacleWarning(strings.obstacleText)
                    }
                },
                onDoorAhead = {
                    val strings = getStringsForLanguage(currentLanguage)
                    if (navigationManager.isIndoorActive || indoorManager.isIndoorActive) {
                        speechManager.speak(strings.doorAheadText)
                    }
                },
                onPathClear = {
                    val strings = getStringsForLanguage(currentLanguage)
                    if (navigationManager.isIndoorActive || indoorManager.isIndoorActive) {
                        speechManager.speak(strings.walkStraightText)
                    }
                }
            )
            obstacleDetector?.start()
        } catch (e: Exception) {
            Log.e("Drishti", "ObstacleDetector setup error", e)
        }
    }

    private fun onScreenTap() {
        if (!isPermissionGranted) {
            checkAndRequestPermissions()
            return
        }
        speechManager.startListening()
    }

    private fun handleCommand(command: SpeechManager.Command) {
        val strings = getStringsForLanguage(currentLanguage)
        when (command) {
            is SpeechManager.Command.IdentifyObject -> {
                handleIdentifyObject(command.query)
            }
            is SpeechManager.Command.Stop -> {
                navigationManager.stopNavigation()
                indoorManager.stopIndoorGuidance()
                obstacleDetector?.isIndoorMode = false
                speechManager.speak(strings.guidanceStoppedText)
            }
            is SpeechManager.Command.Repeat -> {
                if (indoorManager.isIndoorActive) {
                    speechManager.speak(strings.holdPhoneText)
                } else {
                    navigationManager.repeatCurrentInstruction()
                }
            }
            is SpeechManager.Command.WhereAmI -> {
                navigationManager.speakCurrentLocationName()
            }
            is SpeechManager.Command.Help -> {
                speechManager.speak(strings.helpText)
            }
            is SpeechManager.Command.SwitchLanguage -> {
                speechManager.isAwaitingLanguageChoice = true
                speechManager.speak(strings.askLanguageText)
            }
            is SpeechManager.Command.LanguageChoice -> {
                speechManager.isAwaitingLanguageChoice = false
                currentLanguage = command.language
                speechManager.setLanguage(command.language)
                navigationManager.currentLanguage = command.language
                val newStrings = getStringsForLanguage(command.language)
                speechManager.speak("${newStrings.langChangedText} ${newStrings.readyText}")
            }
            is SpeechManager.Command.MoreObjects -> {
                handleIdentifyObject("what is this")
            }
            is SpeechManager.Command.NavigateTo -> {
                if (navigationManager.isIndoorDestination(command.destination)) {
                    Log.d("Drishti", "Navigating to indoor destination: ${command.destination}")
                    navigationManager.stopNavigation()
                    indoorManager.startIndoorGuidance()
                    obstacleDetector?.isIndoorMode = true
                    speechManager.speak(strings.holdPhoneText)
                } else {
                    indoorManager.stopIndoorGuidance()
                    obstacleDetector?.isIndoorMode = false
                    navigationManager.startNavigationTo(command.destination)
                }
            }
            is SpeechManager.Command.SpeechNotUnderstood -> {
                speechManager.speak(strings.sorryText)
            }
        }
    }

    private fun handleIdentifyObject(query: String) {
        val strings = getStringsForLanguage(currentLanguage)
        val bitmap = obstacleDetector?.getLatestFrameBitmap()

        if (bitmap == null) {
            Log.d("Drishti", "Identify failed: camera frame bitmap is null.")
            speechManager.speak(strings.cameraNotReadyText)
            return
        }

        val lower = query.lowercase()
        val isBesideQuery = lower.contains("beside") || lower.contains("around") ||
                lower.contains("bagal") || lower.contains("aspas") ||
                lower.contains("बगल") || lower.contains("आसपास") ||
                lower.contains("pakkadalli") || lower.contains("suttalu") ||
                lower.contains("ಪಕ್ಕದಲ್ಲಿ") || lower.contains("ಸುತ್ತಲೂ")

        lifecycleScope.launch {
            try {
                val resultText = objectIdentifier.identifyObjectInFrame(
                    bitmap = bitmap,
                    isBesideQuery = isBesideQuery,
                    language = currentLanguage
                )

                Log.d("Drishti", "Speaking identify result: $resultText")
                speechManager.speak(resultText)
            } catch (e: Exception) {
                Log.e("Drishti", "Identify object handling exception", e)
                speechManager.speak(strings.moveCloserText)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        obstacleDetector?.stop()
        indoorManager.stopIndoorGuidance()
        objectIdentifier.destroy()
        navigationManager.destroy()
        speechManager.destroy()
    }
}

@Composable
fun DrishtiScreen(
    language: AppLanguage,
    onTap: () -> Unit
) {
    val strings = getStringsForLanguage(language)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = strings.uiText,
            color = Color(0xFFFFFF00),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
