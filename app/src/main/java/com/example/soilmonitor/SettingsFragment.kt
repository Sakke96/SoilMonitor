package com.example.soilmonitor

import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.*
import java.io.File
import java.io.IOException

class SettingsFragment : Fragment() {

    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var editRefreshInterval: EditText
    private lateinit var numberPickerPlants: NumberPicker
    private lateinit var containerThresholds: LinearLayout
    private lateinit var switchGridlines: SwitchMaterial
    private lateinit var spinnerTempUnit: Spinner
    private lateinit var switchDebugMode: SwitchMaterial
    private lateinit var buttonClearCache: Button
    private lateinit var buttonUploadThresholds: Button
    private lateinit var buttonDecimate: Button
    private lateinit var prefs: SharedPreferences

    // OkHttp client shared:
    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // Bind views
        switchNotifications    = root.findViewById(R.id.switch_notifications)
        editRefreshInterval    = root.findViewById(R.id.edit_refresh_interval)
        numberPickerPlants     = root.findViewById(R.id.number_picker_plants)
        containerThresholds    = root.findViewById(R.id.container_thresholds)
        switchGridlines        = root.findViewById(R.id.switch_gridlines)
        spinnerTempUnit        = root.findViewById(R.id.spinner_temp_unit)
        switchDebugMode        = root.findViewById(R.id.switch_debug_mode)
        buttonClearCache       = root.findViewById(R.id.button_clear_cache)
        buttonUploadThresholds = root.findViewById(R.id.button_upload_thresholds)
        buttonDecimate         = root.findViewById(R.id.button_decimate)

        // Load defaults
        editRefreshInterval.setText(prefs.getInt("refreshInterval", 5).toString())
        switchNotifications.isChecked = prefs.getBoolean("notifications", true)

        // Listen for toggle changes
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications", isChecked).apply()
            if (isChecked) {
                // reset all last-alert timestamps
                val plantCount = prefs.getInt("plantCount", 4)
                prefs.edit().apply {
                    for (i in 1..plantCount) {
                        putLong("plant_${i}_last_alert", 0L)
                    }
                }.apply()
            }
        }

        // NumberPicker setup…
        numberPickerPlants.minValue = 1
        numberPickerPlants.maxValue = 9
        numberPickerPlants.value = prefs.getInt("plantCount", 4)
        prefs.edit().putInt("plantCount", numberPickerPlants.value).apply()
        numberPickerPlants.setOnValueChangedListener { _, _, newVal ->
            prefs.edit().putInt("plantCount", newVal).apply()
            populateThresholdFields(newVal)
        }
        populateThresholdFields(numberPickerPlants.value)

        // NEW: Clear only the saved photos (filesDir/photos/**)
        buttonClearCache.setOnClickListener {
            // Point at /data/data/com.example.soilmonitor/files/photos
            val photosRoot = File(requireContext().filesDir, "photos")

            if (photosRoot.exists()) {
                val deleted = photosRoot.deleteRecursively()
                if (deleted) {
                    Toast.makeText(requireContext(), "Photo cache cleared", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to clear photo cache", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "No cached photos to delete", Toast.LENGTH_SHORT).show()
            }
        }

        // Upload thresholds button listener
        buttonUploadThresholds.setOnClickListener {
            val plantCount = prefs.getInt("plantCount", 4)
            val json = org.json.JSONObject().apply {
                for (i in 1..plantCount) {
                    put("plant_${'$'}i_dry", prefs.getFloat("plant_${'$'}i_dry", 0f))
                    put("plant_${'$'}i_wet", prefs.getFloat("plant_${'$'}i_wet", 100f))
                }
            }
            val body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                json.toString()
            )
            val request = Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/thresholds/")
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Upload mislukt", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Grenzen geüpload", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // Decimate button listener
        buttonDecimate.setOnClickListener {
            val request = Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/decimate")
                .get()
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // log if needed
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        }

        return root
    }

    private fun populateThresholdFields(count: Int) {
        containerThresholds.removeAllViews()
        for (i in 1..count) {
            val label = TextView(requireContext()).apply {
                text = "Plantage $i droog/nat waarden"
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }
            val defaultDry = when (i) {
                1 -> 372f; 2 -> 318f; 3 -> 359f; 4 -> 421f
                else -> 0f
            }
            val defaultWet = when (i) {
                1 -> 329f; 2 -> 367f; 3 -> 385f; 4 -> 408f
                else -> 100f
            }
            val dryInput = EditText(requireContext()).apply {
                hint = "Droog waarde plant $i"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat("plant_${i}_dry", defaultDry).toString())
            }
            val wetInput = EditText(requireContext()).apply {
                hint = "Nat waarde plant $i"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat("plant_${i}_wet", defaultWet).toString())
            }
            dryInput.setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    val value = (v as EditText).text.toString().toFloatOrNull() ?: defaultDry
                    prefs.edit().putFloat("plant_${i}_dry", value).apply()
                }
            }
            wetInput.setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    val value = (v as EditText).text.toString().toFloatOrNull() ?: defaultWet
                    prefs.edit().putFloat("plant_${i}_wet", value).apply()
                }
            }

            containerThresholds.addView(label)
            containerThresholds.addView(dryInput)
            containerThresholds.addView(wetInput)
        }
    }
}
