package com.example.drushti

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ObjectIdentifier {

    private val imageLabeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.1f)
            .build()
        ImageLabeling.getClient(options)
    }

    suspend fun identifyObjectInFrame(
        bitmap: Bitmap,
        isBesideQuery: Boolean,
        language: AppLanguage
    ): String = withContext(Dispatchers.IO) {
        val strings = getStringsForLanguage(language)
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) {
                Log.d("Drishti", "Identify failed: Invalid bitmap dimensions ($width x $height)")
                return@withContext strings.cameraNotReadyText
            }

            if (isBesideQuery) {
                return@withContext processBesideQuery(bitmap, language)
            } else {
                return@withContext processWholeFrameQuery(bitmap, language)
            }
        } catch (e: Exception) {
            Log.e("Drishti", "Identify object exception", e)
            return@withContext strings.moveCloserText
        }
    }

    private suspend fun processWholeFrameQuery(bitmap: Bitmap, language: AppLanguage): String {
        val strings = getStringsForLanguage(language)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val rawLabels = runImageLabeler(inputImage)

        // Log ALL raw labels and confidence values BEFORE filtering
        Log.d("Drishti", "--- Whole Frame ML Kit Labels (Before Filtering) ---")
        for (lbl in rawLabels) {
            Log.d("Drishti", "Raw Label: '${lbl.text}', Confidence: ${lbl.confidence}")
        }

        val validLabels = rawLabels.mapNotNull { lbl ->
            val simple = simplifyLabel(lbl.text)
            if (simple != null) Pair(simple, lbl.confidence) else null
        }

        if (validLabels.isEmpty()) {
            Log.d("Drishti", "No valid non-vague labels found for whole frame.")
            return strings.moveCloserText
        }

        val bestLabel = validLabels.maxByOrNull { it.second } ?: return strings.moveCloserText
        val name = bestLabel.first
        val confidence = bestLabel.second

        Log.d("Drishti", "Best filtered label: '$name', confidence: $confidence")

        val isVeh = isVehicle(name)
        val prefix = if (isVeh) strings.carefulPrefix else ""

        val mainNameText = if (confidence >= 0.5f) {
            name.replaceFirstChar { it.uppercase() }
        } else {
            "${strings.maybePrefix}$name"
        }

        return "$prefix$mainNameText."
    }

    private suspend fun processBesideQuery(bitmap: Bitmap, language: AppLanguage): String {
        val strings = getStringsForLanguage(language)
        val width = bitmap.width
        val height = bitmap.height

        val leftCropWidth = (width / 3).coerceAtLeast(1)
        val rightCropStart = (2 * width / 3).coerceIn(0, width - 1)
        val cropWidth = (width - rightCropStart).coerceAtLeast(1)

        val leftBitmap = try {
            Bitmap.createBitmap(bitmap, 0, 0, leftCropWidth, height)
        } catch (e: Exception) {
            null
        }

        val rightBitmap = try {
            Bitmap.createBitmap(bitmap, rightCropStart, 0, cropWidth, height)
        } catch (e: Exception) {
            null
        }

        val leftLabels = if (leftBitmap != null) runImageLabeler(InputImage.fromBitmap(leftBitmap, 0)) else emptyList()
        val rightLabels = if (rightBitmap != null) runImageLabeler(InputImage.fromBitmap(rightBitmap, 0)) else emptyList()

        Log.d("Drishti", "--- Left Crop Labels ---")
        for (lbl in leftLabels) {
            Log.d("Drishti", "Left Raw Label: '${lbl.text}', Confidence: ${lbl.confidence}")
        }

        Log.d("Drishti", "--- Right Crop Labels ---")
        for (lbl in rightLabels) {
            Log.d("Drishti", "Right Raw Label: '${lbl.text}', Confidence: ${lbl.confidence}")
        }

        val bestLeft = leftLabels.mapNotNull { lbl ->
            val simple = simplifyLabel(lbl.text)
            if (simple != null) Pair(simple, lbl.confidence) else null
        }.maxByOrNull { it.second }

        val bestRight = rightLabels.mapNotNull { lbl ->
            val simple = simplifyLabel(lbl.text)
            if (simple != null) Pair(simple, lbl.confidence) else null
        }.maxByOrNull { it.second }

        val selected = when {
            bestLeft != null && bestRight != null -> {
                if (bestLeft.second >= bestRight.second) {
                    Triple(bestLeft.first, bestLeft.second, strings.onYourLeftText)
                } else {
                    Triple(bestRight.first, bestRight.second, strings.onYourRightText)
                }
            }
            bestLeft != null -> Triple(bestLeft.first, bestLeft.second, strings.onYourLeftText)
            bestRight != null -> Triple(bestRight.first, bestRight.second, strings.onYourRightText)
            else -> null
        }

        if (selected == null) {
            Log.d("Drishti", "No beside labels found. Falling back to whole frame.")
            return processWholeFrameQuery(bitmap, language)
        }

        val name = selected.first
        val confidence = selected.second
        val side = selected.third

        val isVeh = isVehicle(name)
        val prefix = if (isVeh) strings.carefulPrefix else ""

        val nameText = if (confidence >= 0.5f) {
            name.replaceFirstChar { it.uppercase() }
        } else {
            "${strings.maybePrefix}$name"
        }

        return "$prefix$nameText, $side."
    }

    private suspend fun runImageLabeler(inputImage: InputImage): List<ImageLabel> {
        return try {
            suspendCancellableCoroutine { continuation ->
                imageLabeler.process(inputImage)
                    .addOnSuccessListener { labels ->
                        if (continuation.isActive) continuation.resume(labels)
                    }
                    .addOnFailureListener { e ->
                        Log.e("Drishti", "ImageLabeler process error", e)
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            }
        } catch (e: Exception) {
            Log.e("Drishti", "runImageLabeler exception", e)
            emptyList()
        }
    }

    private fun simplifyLabel(rawLabel: String): String? {
        val lower = rawLabel.lowercase().trim()

        val vagueTerms = listOf(
            "material", "pattern", "technology", "gadget", "font", "electronic device",
            "component", "design", "graphics", "indoor", "outdoor", "object", "software",
            "parallel", "circle", "rectangle", "square", "line", "white", "black", "blue",
            "red", "plastic", "metal", "wood", "text", "symbol", "display device", "multimedia",
            "fashion", "product", "room", "floor"
        )

        if (vagueTerms.any { lower == it || lower.contains(it) }) {
            return null
        }

        return when {
            lower.contains("computer keyboard") || lower == "keyboard" -> "keyboard"
            lower.contains("laptop") || lower.contains("netbook") -> "laptop"
            lower.contains("bicycle") -> "bike"
            lower.contains("motorcycle") -> "motorcycle"
            lower.contains("bus") -> "bus"
            lower.contains("car") || lower.contains("automobile") -> "car"
            lower.contains("truck") -> "truck"
            lower.contains("vehicle") -> "vehicle"
            lower.contains("rock") || lower.contains("stone") || lower.contains("boulder") -> "stone"
            lower.contains("mobile phone") || lower.contains("cellular telephone") || lower.contains("smartphone") -> "phone"
            lower.contains("person") || lower.contains("human") || lower.contains("man") || lower.contains("woman") -> "person"
            lower.contains("dog") -> "dog"
            lower.contains("cat") -> "cat"
            lower.contains("chair") || lower.contains("seat") -> "chair"
            lower.contains("table") || lower.contains("desk") -> "table"
            lower.contains("door") -> "door"
            lower.contains("stair") || lower.contains("steps") -> "stairs"
            lower.contains("footwear") || lower.contains("shoe") -> "shoe"
            lower.contains("bottle") -> "bottle"
            lower.contains("cup") || lower.contains("mug") -> "cup"
            lower.contains("book") -> "book"
            lower.contains("television") || lower.contains("screen") || lower.contains("monitor") -> "TV"
            lower.contains("backpack") || lower.contains("handbag") || lower.contains("bag") -> "bag"
            lower.contains("window") -> "window"
            lower.contains("clock") || lower.contains("watch") -> "clock"
            else -> rawLabel.split(" ").firstOrNull()?.lowercase()
        }
    }

    private fun isVehicle(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "car" || lower == "bus" || lower == "truck" || lower == "motorcycle" || lower == "bike" || lower == "vehicle"
    }

    fun destroy() {
        try {
            imageLabeler.close()
        } catch (e: Exception) {
            Log.e("Drishti", "Destroy object identifier error", e)
        }
    }
}
