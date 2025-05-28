package com.example.soilmonitor

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private lateinit var btnGraph: TextView
    private lateinit var btnOther1: TextView
    private lateinit var btnHome: TextView
    private lateinit var btnOther2: TextView
    private lateinit var btnSettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind de “knoppen” (nu TextViews met emoji’s)
        btnGraph    = findViewById(R.id.btnGraph)
        btnOther1   = findViewById(R.id.btnOther1)
        btnHome     = findViewById(R.id.btnHome)
        btnOther2   = findViewById(R.id.btnOther2)
        btnSettings = findViewById(R.id.btnSettings)

        // Standaard: MoistureFragment (in plaats van SensorFragment)
        selectButton(btnHome)
        loadFragment(MoistureFragment())

        // Click listeners
        btnGraph.setOnClickListener {
            selectButton(btnGraph)
            loadFragment(SensorFragment())
        }

        btnHome.setOnClickListener {
            selectButton(btnHome)
            loadFragment(MoistureFragment())
        }

        btnOther1.setOnClickListener {
            selectButton(btnOther1)
            // TODO: later invullen
        }

        btnOther2.setOnClickListener {
            selectButton(btnOther2)
            // TODO: later invullen
        }

        btnSettings.setOnClickListener {
            selectButton(btnSettings)
            loadFragment(SettingsFragment())
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun selectButton(selected: TextView) {
        val allButtons = listOf(btnGraph, btnOther1, btnHome, btnOther2, btnSettings)
        allButtons.forEach { it.isSelected = (it == selected) }
    }
}
