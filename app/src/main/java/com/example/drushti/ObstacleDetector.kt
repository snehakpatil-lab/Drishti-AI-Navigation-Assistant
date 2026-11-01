package com.example.drushti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObstacleDetector(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onObstacleDetected: (turnDirection: String) -> Unit,
    private val onDoorAhead: (() -> Unit)? = null,
    private val onPathClear: (() -> Unit)? = null
) {

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastWarningTime: Long = 0L
    private var lastDoorTime: Long = 0L
    private var lastClearTime: Long = 0L
    private var isObstacleActive = false

    var isIndoorMode = false

    @Volatile
    private var latestBitmap: Bitmap? = null

    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(options)
    }

    fun getLatestFrameBitmap(): Bitmap? {
        return latestBitmap
    }

    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("Drishti", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            // Save latest frame bitmap
            val rawBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            latestBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }

            val image = InputImage.fromMediaImage(mediaImage, rotation)
            objectDetector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    val imageWidth = image.width
                    val imageHeight = image.height
                    if (imageWidth <= 0 || imageHeight <= 0) return@addOnSuccessListener

                    val frameArea = (imageWidth * imageHeight).toFloat()
                    var obstacleFoundInFrame = false

                    for (obj in detectedObjects) {
                        val box = obj.boundingBox
                        val boxArea = box.width() * box.height()
                        val areaRatio = boxArea / frameArea

                        val centerX = box.centerX()
                        val centerY = box.centerY()

                        val inMiddleX = centerX >= (imageWidth / 3) && centerX <= ((2 * imageWidth) / 3)
                        val inMiddleY = centerY >= (imageHeight / 4) && centerY <= ((3 * imageHeight) / 4)

                        // Check Door Detection for Indoor Mode
                        if (isIndoorMode && onDoorAhead != null) {
                            val isDoorLabel = obj.labels.any {
                                it.text.contains("door", ignoreCase = true) ||
                                        it.text.contains("entrance", ignoreCase = true)
                            }
                            val isDoorShape = (box.height().toFloat() / box.width().coerceAtLeast(1).toFloat() >= 1.3f) &&
                                    (box.height() >= 0.4f * imageHeight) &&
                                    (centerX >= imageWidth / 4 && centerX <= 3 * imageWidth / 4)

                            if (isDoorLabel || isDoorShape) {
                                val now = System.currentTimeMillis()
                                if (now - lastDoorTime >= 10000) {
                                    lastDoorTime = now
                                    onDoorAhead.invoke()
                                }
                            }
                        }

                        // Big Obstacle in Centre (>25% area and middle third)
                        if (areaRatio >= 0.25f && inMiddleX && inMiddleY) {
                            obstacleFoundInFrame = true
                            isObstacleActive = true

                            val now = System.currentTimeMillis()
                            if (now - lastWarningTime >= 6000) {
                                lastWarningTime = now
                                val turnDirection = if (centerX > imageWidth / 2) "left" else "right"
                                Log.d("Drishti", "Obstacle detected. Suggesting turn $turnDirection")
                                onObstacleDetected(turnDirection)
                            }
                            break
                        }
                    }

                    if (isIndoorMode && !obstacleFoundInFrame && isObstacleActive) {
                        isObstacleActive = false
                        val now = System.currentTimeMillis()
                        if (now - lastClearTime >= 8000) {
                            lastClearTime = now
                            onPathClear?.invoke()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Drishti", "Object detection error", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e("Drishti", "Frame analysis exception", e)
            imageProxy.close()
        }
    }

    fun stop() {
        try {
            cameraExecutor.shutdown()
            objectDetector.close()
        } catch (e: Exception) {
            Log.e("Drishti", "Shutdown error", e)
        }
    }
}
