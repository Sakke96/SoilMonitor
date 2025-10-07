package com.example.soilmonitor

import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.*
import java.io.File
import java.io.IOException

class SettingsFragment : Fragment() {

    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var numberPickerReservoirs: NumberPicker
    private lateinit var switchDebugMode: SwitchMaterial
    private lateinit var buttonClearCache: Button
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
        switchNotifications = root.findViewById(R.id.switch_notifications)
        numberPickerReservoirs = root.findViewById(R.id.number_picker_reservoirs)
        switchDebugMode = root.findViewById(R.id.switch_debug_mode)
        buttonClearCache = root.findViewById(R.id.button_clear_cache)
        buttonDecimate = root.findViewById(R.id.button_decimate)

        // Load defaults
        switchNotifications.isChecked = prefs.getBoolean("notifications", true)
        switchDebugMode.isChecked = prefs.getBoolean("debug_mode", false)

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

        switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("debug_mode", isChecked).apply()
        }

        // NumberPicker setup for Reservoirs
        numberPickerReservoirs.minValue = 1
        numberPickerReservoirs.maxValue = 9
        numberPickerReservoirs.value = prefs.getInt("plantCount", 4)
        prefs.edit().putInt("plantCount", numberPickerReservoirs.value).apply()
        numberPickerReservoirs.setOnValueChangedListener { _, _, newVal ->
            prefs.edit().putInt("plantCount", newVal).apply()
        }

        // Clear Cache button
        buttonClearCache.setOnClickListener {
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
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Data decimation completed", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        return root
    }
}
