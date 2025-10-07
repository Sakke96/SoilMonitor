package com.example.soilmonitor

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class Plant(
    val plantId: Int,
    val plantName: String,
    val valvePin: Int,
    val waterVolumeSeconds: Int,
    val wateringIntervalHours: Int,
    val lastWateringTimestamp: String,
    val nextWateringTimestamp: String?, // NEW: Optional next watering timestamp
    val isActive: Int
)

class SurroundingFragment : Fragment() {

    private lateinit var containerPlants: LinearLayout
    private lateinit var buttonGetPlants: Button
    private lateinit var buttonAddPlant: Button
    private lateinit var prefs: SharedPreferences

    private var plantsList: MutableList<Plant> = mutableListOf()
    private val client = OkHttpClient()

    /* ---- TIMER FOR COUNTDOWN UPDATES ---- */
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateCountdownTimers()
            handler.postDelayed(this, 1000L) // Update every second
        }
    }
    private val plantButtonsMap = mutableMapOf<Int, Button>() // plantId -> Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_surrounding, container, false)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // Bind views
        containerPlants = root.findViewById(R.id.container_plants)
        buttonGetPlants = root.findViewById(R.id.button_get_plants)
        buttonAddPlant = root.findViewById(R.id.button_add_plant)

        // Set up button listeners
        buttonGetPlants.setOnClickListener {
            fetchPlantsFromAPI()
        }

        buttonAddPlant.setOnClickListener {
            showAddPlantDialog()
        }

        // Auto-load plants on start
        fetchPlantsFromAPI()

        // Start countdown timer
        handler.post(timerRunnable)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(timerRunnable)
    }

    /* =============================================================== */
    /* IMPROVED COUNTDOWN CALCULATION WITH NEXT_WATERING_TIMESTAMP */
    /* =============================================================== */
    /* =============================================================== */
    /* FIXED COUNTDOWN CALCULATION WITH PROPER TIMESTAMP PARSING */
    /* =============================================================== */
    private fun calculateTimeToNextWatering(plant: Plant): String {
        try {
            val now = LocalDateTime.now()

            val nextWatering = if (!plant.nextWateringTimestamp.isNullOrEmpty()) {
                // MANUAL: Use stored timestamp
                parseTimestamp(plant.nextWateringTimestamp)
            } else {
                // AUTO: Calculate from last + interval
                val lastWatering = parseTimestamp(plant.lastWateringTimestamp)
                lastWatering.plusHours(plant.wateringIntervalHours.toLong())
            }

            // Calculate time difference
            val minutesUntilNext = ChronoUnit.MINUTES.between(now, nextWatering)

            return when {
                minutesUntilNext <= 0 -> "⚠️ DUE NOW"
                minutesUntilNext < 60 -> "${minutesUntilNext}m"
                minutesUntilNext < 1440 -> { // Less than 24 hours
                    val hours = minutesUntilNext / 60
                    val mins = minutesUntilNext % 60
                    "${hours}h ${mins}m"
                }
                else -> { // More than 24 hours
                    val days = minutesUntilNext / 1440
                    val hours = (minutesUntilNext % 1440) / 60
                    "${days}d ${hours}h"
                }
            }
        } catch (e: Exception) {
            Log.e("SurroundingFragment", "Error calculating countdown for ${plant.plantName}", e)
            return "Unknown"
        }
    }

    /* =============================================================== */
    /* HELPER FUNCTION TO PARSE DIFFERENT TIMESTAMP FORMATS */
    /* =============================================================== */
    private fun parseTimestamp(timestamp: String): LocalDateTime {
        // Handle multiple timestamp formats from Oracle DB
        return try {
            when {
                // ISO format with T and Z: "2025-10-07T09:01:05.869334Z"
                timestamp.contains('T') && timestamp.endsWith('Z') -> {
                    val cleanTimestamp = timestamp.removeSuffix("Z")
                    if (cleanTimestamp.contains('.')) {
                        // With milliseconds/microseconds - truncate to milliseconds
                        val parts = cleanTimestamp.split('.')
                        val mainPart = parts[0]
                        val fractionalPart = parts[1].take(6).padEnd(6, '0') // Take up to 6 digits, pad if needed
                        LocalDateTime.parse("${mainPart}.${fractionalPart}", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"))
                    } else {
                        // Without milliseconds: "2025-10-07T09:01:05Z"
                        LocalDateTime.parse(cleanTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    }
                }
                // Standard format: "2025-10-07 09:01:05"
                timestamp.contains(' ') -> {
                    LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                }
                // ISO format without Z: "2025-10-07T09:01:05"
                timestamp.contains('T') -> {
                    if (timestamp.contains('.')) {
                        // With milliseconds
                        val parts = timestamp.split('.')
                        val mainPart = parts[0]
                        val fractionalPart = parts[1].take(6).padEnd(6, '0')
                        LocalDateTime.parse("${mainPart}.${fractionalPart}", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"))
                    } else {
                        LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    }
                }
                else -> {
                    // Try default format as last resort
                    LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                }
            }
        } catch (e: Exception) {
            Log.e("SurroundingFragment", "Failed to parse timestamp: $timestamp", e)
            // Return current time as fallback
            LocalDateTime.now()
        }
    }


    private fun updateCountdownTimers() {
        plantButtonsMap.forEach { (plantId, button) ->
            val plant = plantsList.find { it.plantId == plantId }
            if (plant != null) {
                val countdown = calculateTimeToNextWatering(plant)
                val statusText = if (plant.isActive == 1) "Active" else "Inactive"
                val timestampSource = if (!plant.nextWateringTimestamp.isNullOrEmpty()) "📅" else "🔄"
                button.text = "${plant.plantName} ($statusText) $timestampSource $countdown"

                // Update button color based on urgency
                val backgroundColor = when {
                    countdown.contains("DUE NOW") -> android.graphics.Color.parseColor("#FF5722") // Red
                    countdown.contains("m") && !countdown.contains("h") -> android.graphics.Color.parseColor("#FF9800") // Orange
                    plant.isActive == 1 -> android.graphics.Color.parseColor("#4CAF50") // Green
                    else -> android.graphics.Color.parseColor("#9E9E9E") // Gray for inactive
                }
                button.setBackgroundColor(backgroundColor)
            }
        }
    }

    private fun populatePlantButtons() {
        containerPlants.removeAllViews()
        plantButtonsMap.clear()

        // Sort plants by name using natural ordering
        val sortedPlants = plantsList.sortedWith(compareBy {
            val nameMatch = Regex("([a-zA-Z]+)(\\d+)").find(it.plantName)
            if (nameMatch != null) {
                val prefix = nameMatch.groupValues[1]
                val number = nameMatch.groupValues[2].toIntOrNull() ?: 0
                "$prefix${String.format("%05d", number)}"
            } else {
                it.plantName
            }
        })

        for (plant in sortedPlants) {
            val countdown = calculateTimeToNextWatering(plant)
            val statusText = if (plant.isActive == 1) "Active" else "Inactive"
            val timestampSource = if (!plant.nextWateringTimestamp.isNullOrEmpty()) "📅" else "🔄"

            val plantButton = Button(requireContext()).apply {
                text = "${plant.plantName} ($statusText) $timestampSource $countdown"
                setOnClickListener {
                    showPlantDetailsDialog(plant)
                }
                setPadding(32, 24, 32, 24)

                val backgroundColor = when {
                    countdown.contains("DUE NOW") -> android.graphics.Color.parseColor("#FF5722")
                    countdown.contains("m") && !countdown.contains("h") -> android.graphics.Color.parseColor("#FF9800")
                    plant.isActive == 1 -> android.graphics.Color.parseColor("#4CAF50")
                    else -> android.graphics.Color.parseColor("#9E9E9E")
                }
                setBackgroundColor(backgroundColor)
                setTextColor(android.graphics.Color.WHITE)

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 16, 0, 0)
                layoutParams = params
            }

            containerPlants.addView(plantButton)
            plantButtonsMap[plant.plantId] = plantButton
        }

        if (plantsList.isEmpty()) {
            val noPlantsText = TextView(requireContext()).apply {
                text = "No plants found. Click 'Get Plants' to refresh or 'Add Plant' to create new plants."
                setPadding(16, 16, 16, 16)
                textSize = 16f
                setTextColor(android.graphics.Color.GRAY)
            }
            containerPlants.addView(noPlantsText)
        }
    }

    private fun showPlantDetailsDialog(plant: Plant) {
        val dialogLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val plantNameInput = EditText(requireContext()).apply {
            hint = "Plant Name"
            setText(plant.plantName)
        }

        val valvePinInput = EditText(requireContext()).apply {
            hint = "Valve Pin"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(plant.valvePin.toString())
        }

        val waterVolumeInput = EditText(requireContext()).apply {
            hint = "Water Volume (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(plant.waterVolumeSeconds.toString())
        }

        val wateringIntervalInput = EditText(requireContext()).apply {
            hint = "Watering Interval (hours)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(plant.wateringIntervalHours.toString())
        }

        val isActiveSwitch = SwitchMaterial(requireContext()).apply {
            text = "Is Active"
            isChecked = plant.isActive == 1
        }

        val lastWateringText = TextView(requireContext()).apply {
            text = "Last Watering: ${plant.lastWateringTimestamp}"
            setPadding(0, 16, 0, 8)
        }

        val nextWateringText = TextView(requireContext()).apply {
            val countdown = calculateTimeToNextWatering(plant)
            val source = if (!plant.nextWateringTimestamp.isNullOrEmpty()) {
                "Next Watering (Manual): ${plant.nextWateringTimestamp} ($countdown)"
            } else {
                "Next Watering (Auto): $countdown"
            }
            text = source
            setPadding(0, 0, 0, 16)
            setTextColor(if (countdown.contains("DUE NOW")) android.graphics.Color.RED else android.graphics.Color.BLACK)
        }

        // Manual watering button
        val manualWaterButton = Button(requireContext()).apply {
            text = "💧 Manual Water Now"
            setOnClickListener {
                setManualWatering(plant)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 16, 32, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 16, 0, 16)
            layoutParams = params
        }

        // Reset to auto schedule button
        val resetScheduleButton = Button(requireContext()).apply {
            text = "🔄 Reset to Auto Schedule"
            isEnabled = !plant.nextWateringTimestamp.isNullOrEmpty()
            setOnClickListener {
                resetToAutoSchedule(plant)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 16, 32, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        // Add views to dialog
        dialogLayout.addView(TextView(requireContext()).apply {
            text = "Plant Details - ${plant.plantName}"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        })
        dialogLayout.addView(plantNameInput)
        dialogLayout.addView(valvePinInput)
        dialogLayout.addView(waterVolumeInput)
        dialogLayout.addView(wateringIntervalInput)
        dialogLayout.addView(isActiveSwitch)
        dialogLayout.addView(lastWateringText)
        dialogLayout.addView(nextWateringText)
        dialogLayout.addView(manualWaterButton)
        dialogLayout.addView(resetScheduleButton)

        AlertDialog.Builder(requireContext())
            .setView(dialogLayout)
            .setPositiveButton("Update") { _, _ ->
                val updatedPlant = plant.copy(
                    plantName = plantNameInput.text.toString().trim(),
                    valvePin = valvePinInput.text.toString().toIntOrNull() ?: plant.valvePin,
                    waterVolumeSeconds = waterVolumeInput.text.toString().toIntOrNull() ?: plant.waterVolumeSeconds,
                    wateringIntervalHours = wateringIntervalInput.text.toString().toIntOrNull() ?: plant.wateringIntervalHours,
                    isActive = if (isActiveSwitch.isChecked) 1 else 0
                )
                updatePlantInAPI(updatedPlant)
            }
            .setNegativeButton("Delete") { _, _ ->
                showDeleteConfirmation(plant)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /* =============================================================== */
    /* MANUAL WATERING FUNCTIONS */
    /* =============================================================== */
    private fun setManualWatering(plant: Plant) {
        // Set next watering to NOW
        val now = LocalDateTime.now()

        // Try ISO format first (what Oracle might expect)
        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        val updatedPlant = plant.copy(
            nextWateringTimestamp = now.format(isoFormatter)
        )

        Log.d("SurroundingFragment", "Setting manual watering for ${plant.plantName} to: ${updatedPlant.nextWateringTimestamp}")
        updatePlantInAPI(updatedPlant, isManualWater = true)
    }

    private fun resetToAutoSchedule(plant: Plant) {
        val updatedPlant = plant.copy(
            nextWateringTimestamp = null // This will clear the manual timestamp
        )

        updatePlantInAPI(updatedPlant, clearNextWatering = true)
    }

    private fun showDeleteConfirmation(plant: Plant) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Plant")
            .setMessage("Are you sure you want to delete '${plant.plantName}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deletePlantFromAPI(plant.plantId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePlantInAPI(plant: Plant, isManualWater: Boolean = false, clearNextWatering: Boolean = false) {
        val jsonBody = JSONObject().apply {
            put("plant_name", plant.plantName)
            put("valve_pin", plant.valvePin)
            put("water_volume_seconds", plant.waterVolumeSeconds)
            put("watering_interval_hours", plant.wateringIntervalHours)
            put("is_active", plant.isActive)

            // Handle next_watering_timestamp - FIXED VERSION
            when {
                clearNextWatering -> {
                    // Send empty string to clear (Oracle might not handle null properly)
                    put("next_watering_timestamp", "")
                }
                plant.nextWateringTimestamp != null -> {
                    // Send the timestamp in ISO format that Oracle expects
                    put("next_watering_timestamp", plant.nextWateringTimestamp)
                }
                // If null and not clearing, don't include the field
            }
        }

        Log.d("SurroundingFragment", "Sending JSON for plant ${plant.plantId}: ${jsonBody.toString()}")

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-plantwateringdb.adb.eu-paris-1.oraclecloudapps.com/ords/admin/api/plants/${plant.plantId}")
            .put(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SurroundingFragment", "Failed to update plant", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Failed to update plant: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    Log.d("SurroundingFragment", "API Response Code: ${response.code}")
                    Log.d("SurroundingFragment", "API Response Body: $responseBody")

                    if (response.isSuccessful) {
                        Log.d("SurroundingFragment", "Plant updated successfully")
                        activity?.runOnUiThread {
                            val message = when {
                                isManualWater -> "Manual watering set for '${plant.plantName}'!"
                                clearNextWatering -> "Reset '${plant.plantName}' to auto schedule!"
                                else -> "Plant '${plant.plantName}' updated successfully!"
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

                            // Delay refresh to allow database to update
                            handler.postDelayed({
                                fetchPlantsFromAPI()
                            }, 500)
                        }
                    } else {
                        Log.e("SurroundingFragment", "Failed to update plant: HTTP ${response.code}")
                        Log.e("SurroundingFragment", "Error response: $responseBody")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to update plant: HTTP ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SurroundingFragment", "Error processing update response", e)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error updating plant: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    response.close()
                }
            }
        })
    }
    private fun deletePlantFromAPI(plantId: Int) {
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-plantwateringdb.adb.eu-paris-1.oraclecloudapps.com/ords/admin/api/plants/$plantId")
            .delete()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SurroundingFragment", "Failed to delete plant", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Failed to delete plant: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        Log.d("SurroundingFragment", "Plant deleted successfully")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Plant deleted successfully!", Toast.LENGTH_SHORT).show()
                            fetchPlantsFromAPI()
                        }
                    } else {
                        Log.e("SurroundingFragment", "Failed to delete plant: ${response.code}")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to delete plant: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SurroundingFragment", "Error processing delete response", e)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error deleting plant: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun showAddPlantDialog() {
        val dialogLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val plantNameInput = EditText(requireContext()).apply {
            hint = "Plant Name"
            setText("Plant${System.currentTimeMillis() % 1000}")
        }

        val valvePinInput = EditText(requireContext()).apply {
            hint = "Valve Pin (1-8)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }

        val waterVolumeInput = EditText(requireContext()).apply {
            hint = "Water Volume (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("10")
        }

        val wateringIntervalInput = EditText(requireContext()).apply {
            hint = "Watering Interval (hours)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("24")
        }

        val isActiveSwitch = SwitchMaterial(requireContext()).apply {
            text = "Is Active"
            isChecked = true
        }

        dialogLayout.addView(TextView(requireContext()).apply {
            text = "Add New Plant"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        })
        dialogLayout.addView(plantNameInput)
        dialogLayout.addView(valvePinInput)
        dialogLayout.addView(waterVolumeInput)
        dialogLayout.addView(wateringIntervalInput)
        dialogLayout.addView(isActiveSwitch)

        AlertDialog.Builder(requireContext())
            .setView(dialogLayout)
            .setPositiveButton("Add Plant") { _, _ ->
                val plantName = plantNameInput.text.toString().trim()
                val valvePin = valvePinInput.text.toString().toIntOrNull() ?: 1
                val waterVolume = waterVolumeInput.text.toString().toIntOrNull() ?: 10
                val wateringInterval = wateringIntervalInput.text.toString().toIntOrNull() ?: 24
                val isActive = if (isActiveSwitch.isChecked) 1 else 0

                if (plantName.isNotEmpty()) {
                    addPlantToAPI(plantName, valvePin, waterVolume, wateringInterval, isActive)
                } else {
                    Toast.makeText(requireContext(), "Plant name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPlantToAPI(plantName: String, valvePin: Int, waterVolume: Int, wateringInterval: Int, isActive: Int) {
        val jsonBody = JSONObject().apply {
            put("plant_name", plantName)
            put("valve_pin", valvePin)
            put("water_volume_seconds", waterVolume)
            put("watering_interval_hours", wateringInterval)
            put("is_active", isActive)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-plantwateringdb.adb.eu-paris-1.oraclecloudapps.com/ords/admin/api/plants")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SurroundingFragment", "Failed to add plant", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Failed to add plant: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        Log.d("SurroundingFragment", "Plant added successfully")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Plant '$plantName' added successfully!", Toast.LENGTH_SHORT).show()
                            fetchPlantsFromAPI()
                        }
                    } else {
                        Log.e("SurroundingFragment", "Failed to add plant: ${response.code}")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to add plant: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SurroundingFragment", "Error processing add plant response", e)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error adding plant: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun fetchPlantsFromAPI() {
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-plantwateringdb.adb.eu-paris-1.oraclecloudapps.com/ords/admin/api/plants")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SurroundingFragment", "Failed to fetch plants", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Failed to fetch plants: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        val items = json.getJSONArray("items")
                        plantsList.clear()

                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val plant = Plant(
                                plantId = item.getInt("plant_id"),
                                plantName = item.getString("plant_name"),
                                valvePin = item.getInt("valve_pin"),
                                waterVolumeSeconds = item.getInt("water_volume_seconds"),
                                wateringIntervalHours = item.getInt("watering_interval_hours"),
                                lastWateringTimestamp = item.getString("last_watering_timestamp"),
                                nextWateringTimestamp = if (item.isNull("next_watering_timestamp")) null else item.getString("next_watering_timestamp"),
                                isActive = item.getInt("is_active")
                            )
                            plantsList.add(plant)
                        }

                        Log.d("SurroundingFragment", "Retrieved ${plantsList.size} plants from API")
                        activity?.runOnUiThread {
                            populatePlantButtons()
                            Toast.makeText(requireContext(), "Found ${plantsList.size} plants", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e("SurroundingFragment", "API request failed: ${response.code}")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "API request failed: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SurroundingFragment", "Error parsing plants response", e)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Error parsing response: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    response.close()
                }
            }
        })
    }
}
