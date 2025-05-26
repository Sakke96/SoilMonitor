package com.example.soilmonitor

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Show SensorFragment by default
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SensorFragment())
            .commit()

        // Bottom nav buttons
        findViewById<ImageButton>(R.id.btnGraph).setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SensorFragment())
                .commit()
        }

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MoistureFragment())
                .commit()
        }

        // These buttons are placeholders for now
        findViewById<ImageButton>(R.id.btnOther1).setOnClickListener {
            // TODO: Add feature or leave blank
        }

        findViewById<ImageButton>(R.id.btnOther2).setOnClickListener {
            // TODO: Add feature or leave blank
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            // TODO: Add feature or leave blank
        }
    }
}
