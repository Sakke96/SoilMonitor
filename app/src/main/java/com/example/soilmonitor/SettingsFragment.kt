package com.example.soilmonitor

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
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.SharedPreferences

class SettingsFragment : Fragment() {

    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var editRefreshInterval: EditText
    private lateinit var numberPickerPlants: NumberPicker
    private lateinit var containerThresholds: LinearLayout
    private lateinit var switchGridlines: SwitchMaterial
    private lateinit var spinnerTempUnit: Spinner
    private lateinit var switchDebugMode: SwitchMaterial
    private lateinit var buttonClearCache: Button
    private lateinit var prefs: SharedPreferences

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
        containerThresholds     = root.findViewById(R.id.container_thresholds)
        switchGridlines        = root.findViewById(R.id.switch_gridlines)
        spinnerTempUnit        = root.findViewById(R.id.spinner_temp_unit)
        switchDebugMode        = root.findViewById(R.id.switch_debug_mode)
        buttonClearCache       = root.findViewById(R.id.button_clear_cache)

        // Load defaults
        editRefreshInterval.setText(prefs.getInt("refreshInterval", 5).toString())
        switchNotifications.isChecked = prefs.getBoolean("notifications", true)

        // Setup NumberPicker for plant count (default 4)
        numberPickerPlants.minValue = 1
        numberPickerPlants.maxValue = 9
        numberPickerPlants.value = prefs.getInt("plantCount", 4)
        prefs.edit().putInt("plantCount", numberPickerPlants.value).apply()
        numberPickerPlants.setOnValueChangedListener { _, _, newVal ->
            prefs.edit().putInt("plantCount", newVal).apply()
            populateThresholdFields(newVal)
        }

        // Initial thresholds for default plant count
        populateThresholdFields(numberPickerPlants.value)

        buttonClearCache.setOnClickListener {
            // TODO: cache-clear logic
        }

        return root
    }

    private fun populateThresholdFields(count: Int) {
        containerThresholds.removeAllViews()

        for (i in 1..count) {
            // Section label
            val label = TextView(requireContext()).apply {
                text = "Plantage $i droog/nat waarden"
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }

            // Determine defaults per plant
            val defaultDry = when(i) {
                1 -> 372f
                2 -> 318f
                3 -> 359f
                4 -> 421f
                else -> 0f
            }
            val defaultWet = when(i) {
                1 -> 329f
                2 -> 367f
                3 -> 385f
                4 -> 408f
                else -> 100f
            }

            // Dry value input
            val dryInput = EditText(requireContext()).apply {
                hint = "Droog waarde plant $i"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat("plant_${i}_dry", defaultDry).toString())
            }

            // Wet value input
            val wetInput = EditText(requireContext()).apply {
                hint = "Nat waarde plant $i"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(prefs.getFloat("plant_${i}_wet", defaultWet).toString())
            }

            // Persist on focus loss
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

            // Add to container
            containerThresholds.addView(label)
            containerThresholds.addView(dryInput)
            containerThresholds.addView(wetInput)
        }
    }
}