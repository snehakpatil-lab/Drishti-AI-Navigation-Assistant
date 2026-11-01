package com.example.drushti

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class NavigationManager(
    private val context: Context,
    private val onInstruction: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    data class NavStep(
        val instructionText: String,
        val targetLat: Double,
        val targetLon: Double
    )

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var currentLocation: Location? = null
    private var destinationLocation: Location? = null
    private var currentSteps = mutableListOf<NavStep>()
    private var currentStepIndex = 0
    private var isNavigating = false
    private var locationCallback: LocationCallback? = null

    var currentLanguage: AppLanguage = AppLanguage.ENGLISH
    var isIndoorActive = false
        private set

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(2000L)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    currentLocation = loc
                    checkNavigationProgress(loc)
                }
            }

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback as LocationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("Drishti", "Location update error", e)
        }
    }

    fun isLocationServicesEnabled(): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e("Drishti", "Location manager check error", e)
            false
        }
    }

    fun promptTurnOnLocation() {
        val strings = getStringsForLanguage(currentLanguage)
        onInstruction(strings.turnOnLocationText)
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Drishti", "Open location settings error", e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getBestCurrentLocation(): Location? {
        Log.d("Drishti", "Starting 3-tier location fetch...")

        // Tier 1: getCurrentLocation with 10s timeout
        val loc1 = try {
            withTimeoutOrNull(10000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cts = CancellationTokenSource()
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { loc ->
                            if (continuation.isActive) continuation.resume(loc)
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                }
            }
        } catch (e: Exception) {
            Log.e("Drishti", "getCurrentLocation exception", e)
            null
        }

        if (loc1 != null) {
            Log.d("Drishti", "Tier 1 success: lat=${loc1.latitude}, lon=${loc1.longitude}")
            currentLocation = loc1
            return loc1
        }

        Log.d("Drishti", "Tier 1 returned null. Trying Tier 2 (lastLocation)...")

        // Tier 2: lastLocation fallback
        val loc2 = try {
            suspendCancellableCoroutine<Location?> { continuation ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { loc ->
                        if (continuation.isActive) continuation.resume(loc)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e("Drishti", "lastLocation exception", e)
            null
        }

        if (loc2 != null) {
            Log.d("Drishti", "Tier 2 success: lat=${loc2.latitude}, lon=${loc2.longitude}")
            currentLocation = loc2
            return loc2
        }

        Log.d("Drishti", "Tier 2 returned null. Trying Tier 3 (requestLocationUpdates)...")

        // Tier 3: LocationRequest update fallback (15s timeout)
        val loc3 = try {
            withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                        .setMaxUpdates(1)
                        .build()
                    val cb = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val loc = result.lastLocation
                            if (continuation.isActive) continuation.resume(loc)
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
                }
            }
        } catch (e: Exception) {
            Log.e("Drishti", "Tier 3 location update exception", e)
            null
        }

        if (loc3 != null) {
            Log.d("Drishti", "Tier 3 success: lat=${loc3.latitude}, lon=${loc3.longitude}")
            currentLocation = loc3
            return loc3
        }

        Log.d("Drishti", "All 3 location tiers failed.")
        return null
    }

    fun stopNavigation() {
        isNavigating = false
        isIndoorActive = false
        currentSteps.clear()
        currentStepIndex = 0
        destinationLocation = null
    }

    fun repeatCurrentInstruction() {
        if (currentSteps.isNotEmpty() && currentStepIndex in currentSteps.indices) {
            onInstruction(currentSteps[currentStepIndex].instructionText)
        } else {
            val strings = getStringsForLanguage(currentLanguage)
            onInstruction(strings.readyText)
        }
    }

    fun speakCurrentLocationName() {
        val strings = getStringsForLanguage(currentLanguage)
        if (!isLocationServicesEnabled()) {
            promptTurnOnLocation()
            return
        }

        scope.launch(Dispatchers.IO) {
            val loc = getBestCurrentLocation()
            if (loc == null) {
                withContext(Dispatchers.Main) {
                    onInstruction(strings.cannotFindLocationText)
                }
                return@launch
            }

            try {
                val geocoder = Geocoder(context, currentLanguage.locale)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val addressName = if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    addr.thoroughfare ?: addr.subLocality ?: addr.locality ?: addr.getAddressLine(0) ?: ""
                } else ""

                withContext(Dispatchers.Main) {
                    if (addressName.isNotBlank()) {
                        onInstruction("${strings.locationPrefix}$addressName.")
                    } else {
                        onInstruction(strings.cannotFindLocationText)
                    }
                }
            } catch (e: Exception) {
                Log.e("Drishti", "Geocoder reverse error", e)
                withContext(Dispatchers.Main) {
                    onInstruction(strings.cannotFindLocationText)
                }
            }
        }
    }

    fun isIndoorDestination(query: String): Boolean {
        val lower = query.lowercase()
        val indoorKeywords = listOf(
            "door", "room", "bed", "kitchen", "bathroom", "toilet",
            "exit", "stairs", "lift", "hall", "table",
            "दरवाजा", "कमरा", "रसोई", "बाथरूम", "टॉयलेट", "सीढ़ियां", "लिफ्ट", "हॉल", "टेबल",
            "ಬಾಗಿಲು", "ಕೊಠಡಿ", "ಮಂಚ", "ಅಡುಗೆಮನೆ", "ಶೌಚಾಲಯ", "ಮೆಟ್ಟಿಲು", "ಲಿಫ್ಟ್", "ಹಾಲ್"
        )
        return indoorKeywords.any { lower.contains(it) }
    }

    fun startNavigationTo(destinationQuery: String) {
        val strings = getStringsForLanguage(currentLanguage)

        if (isIndoorDestination(destinationQuery)) {
            Log.d("Drishti", "Destination '$destinationQuery' is indoor. Starting Indoor Guidance.")
            stopNavigation()
            isIndoorActive = true
            onInstruction(strings.holdPhoneText)
            return
        }

        Log.d("Drishti", "Destination '$destinationQuery' is outdoor. Starting GPS Navigation.")
        isIndoorActive = false

        if (!isLocationServicesEnabled()) {
            promptTurnOnLocation()
            return
        }

        onInstruction(strings.findingLocationText)

        scope.launch(Dispatchers.IO) {
            val currLoc = getBestCurrentLocation()
            if (currLoc == null) {
                withContext(Dispatchers.Main) {
                    onError(strings.cannotFindLocationText)
                }
                return@launch
            }

            try {
                val geocoder = Geocoder(context, currentLanguage.locale)
                val minLat = currLoc.latitude - 0.5
                val maxLat = currLoc.latitude + 0.5
                val minLon = currLoc.longitude - 0.5
                val maxLon = currLoc.longitude + 0.5

                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(
                    destinationQuery, 1, minLat, minLon, maxLat, maxLon
                )

                val destAddress = if (!addresses.isNullOrEmpty()) addresses[0] else null

                if (destAddress == null) {
                    withContext(Dispatchers.Main) {
                        onError(strings.placeNotFoundText)
                    }
                    return@launch
                }

                val destLoc = Location("destination").apply {
                    latitude = destAddress.latitude
                    longitude = destAddress.longitude
                }
                destinationLocation = destLoc

                val steps = fetchOsrmRoute(currLoc, destLoc)
                withContext(Dispatchers.Main) {
                    currentSteps.clear()
                    currentSteps.addAll(steps)
                    currentStepIndex = 0
                    isNavigating = true

                    if (currentSteps.isNotEmpty()) {
                        onInstruction(currentSteps[0].instructionText)
                    } else {
                        onError(strings.placeNotFoundText)
                    }
                }
            } catch (e: Exception) {
                Log.e("Drishti", "Navigation start error", e)
                withContext(Dispatchers.Main) {
                    onError(strings.placeNotFoundText)
                }
            }
        }
    }

    private fun fetchOsrmRoute(start: Location, dest: Location): List<NavStep> {
        val url = "https://router.project-osrm.org/route/v1/foot/" +
                "${start.longitude},${start.latitude};" +
                "${dest.longitude},${dest.latitude}?" +
                "steps=true&overview=full&geometries=geojson"

        val strings = getStringsForLanguage(currentLanguage)

        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                val json = JSONObject(responseBody)
                val routes = json.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val legs = routes.getJSONObject(0).optJSONArray("legs")
                    if (legs != null && legs.length() > 0) {
                        val stepsJson = legs.getJSONObject(0).optJSONArray("steps")
                        if (stepsJson != null && stepsJson.length() > 0) {
                            val navSteps = mutableListOf<NavStep>()
                            for (i in 0 until stepsJson.length()) {
                                val stepObj = stepsJson.getJSONObject(i)
                                val distance = stepObj.optDouble("distance", 0.0)
                                val name = stepObj.optString("name", "")
                                val maneuver = stepObj.optJSONObject("maneuver")
                                val modifier = maneuver?.optString("modifier", "") ?: ""
                                val type = maneuver?.optString("type", "") ?: ""

                                var targetLat = dest.latitude
                                var targetLon = dest.longitude

                                val geometry = stepObj.optJSONObject("geometry")
                                val coords = geometry?.optJSONArray("coordinates")
                                if (coords != null && coords.length() > 0) {
                                    val lastCoord = coords.getJSONArray(coords.length() - 1)
                                    targetLon = lastCoord.getDouble(0)
                                    targetLat = lastCoord.getDouble(1)
                                }

                                val text = buildShortInstructionText(type, modifier, name, distance, strings)
                                navSteps.add(NavStep(text, targetLat, targetLon))
                            }
                            if (navSteps.isNotEmpty()) {
                                return navSteps
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Drishti", "OSRM API error, using fallback straight-line", e)
        }

        val distanceMeters = start.distanceTo(dest).toInt()
        val text = "${strings.destinationHeadingText} $distanceMeters meters."
        return listOf(NavStep(text, dest.latitude, dest.longitude))
    }

    private fun buildShortInstructionText(
        type: String,
        modifier: String,
        streetName: String,
        distanceMeters: Double,
        strings: LanguageStrings
    ): String {
        val distInt = distanceMeters.toInt()

        if (type.lowercase() == "arrive") {
            return strings.arrivedText
        }

        val actionText = when {
            modifier.contains("sharp left") || modifier.contains("left") -> strings.turnLeftText
            modifier.contains("sharp right") || modifier.contains("right") -> strings.turnRightText
            modifier.contains("slight left") -> strings.keepLeftText
            modifier.contains("slight right") -> strings.keepRightText
            modifier.contains("u turn") -> strings.uTurnText
            else -> strings.straightText
        }

        return if (distInt > 10) {
            "$actionText Walk $distInt meters."
        } else {
            actionText
        }
    }

    private fun checkNavigationProgress(currentLoc: Location) {
        if (!isNavigating || currentSteps.isEmpty()) return

        val dest = destinationLocation
        if (dest != null && currentLoc.distanceTo(dest) <= 15f) {
            isNavigating = false
            val strings = getStringsForLanguage(currentLanguage)
            onInstruction(strings.arrivedText)
            currentSteps.clear()
            return
        }

        if (currentStepIndex in currentSteps.indices) {
            val step = currentSteps[currentStepIndex]
            val stepTargetLoc = Location("target").apply {
                latitude = step.targetLat
                longitude = step.targetLon
            }

            if (currentLoc.distanceTo(stepTargetLoc) <= 12f) {
                currentStepIndex++
                if (currentStepIndex < currentSteps.size) {
                    onInstruction(currentSteps[currentStepIndex].instructionText)
                } else {
                    isNavigating = false
                    val strings = getStringsForLanguage(currentLanguage)
                    onInstruction(strings.arrivedText)
                    currentSteps.clear()
                }
            }
        }
    }

    fun destroy() {
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (e: Exception) {
            Log.e("Drishti", "Destroy error", e)
        }
    }
}
