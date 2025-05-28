package com.example.soilmonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    private lateinit var switchNotifications: SwitchCompat
    private lateinit var editRefreshInterval: EditText
    private lateinit var switchGridlines: SwitchCompat
    private lateinit var spinnerTempUnit: Spinner
    private lateinit var switchDebugMode: SwitchCompat
    private lateinit var buttonClearCache: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        // Views binden
        switchNotifications    = root.findViewById(R.id.switch_notifications)
        editRefreshInterval    = root.findViewById(R.id.edit_refresh_interval)
        switchGridlines        = root.findViewById(R.id.switch_gridlines)
        spinnerTempUnit        = root.findViewById(R.id.spinner_temp_unit)
        switchDebugMode        = root.findViewById(R.id.switch_debug_mode)
        buttonClearCache       = root.findViewById(R.id.button_clear_cache)

        // Hier kun je defaults instellen, listeners hangen, etc.
        // Bijvoorbeeld:
        editRefreshInterval.setText("5")
        switchNotifications.isChecked = true

        buttonClearCache.setOnClickListener {
            // TODO: cache-clear logica
        }

        return root
    }
}
