package com.example.soilmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnGraph: TextView
    private lateinit var btnOther1: TextView
    private lateinit var btnHome: TextView
    private lateinit var btnOther2: TextView
    private lateinit var btnSettings: TextView

    companion object {
        /** Same channel ID is used in MoistureFragment + MoistureCheckWorker */
        const val CHANNEL_ID_SOIL = "soil_alerts"
        private const val NOTIFICATION_PERMISSION_REQ = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /* ---------- notifications ---------- */
        createNotificationChannel()
        requestNotificationPermissionIfNeed()

        /* ---------- schedule background worker (runs every 15 min) ---------- */
        scheduleBackgroundCheck()

        /* ---------- bottom-nav buttons ---------- */
        btnGraph    = findViewById(R.id.btnGraph)
        btnOther1   = findViewById(R.id.btnOther1)
        btnHome     = findViewById(R.id.btnHome)
        btnOther2   = findViewById(R.id.btnOther2)
        btnSettings = findViewById(R.id.btnSettings)

        /* default fragment */
        selectButton(btnHome)
        loadFragment(MoistureFragment())

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
            loadFragment(PhotoFragment())
        }
        btnOther2.setOnClickListener {
            selectButton(btnOther2)
            loadFragment(SurroundingFragment())  // updated
        }
        btnSettings.setOnClickListener {
            selectButton(btnSettings)
            loadFragment(SettingsFragment())
        }
    }

    /* --------------------------------------------------------------------- */
    /*  NOTIFICATION CHANNEL & (API-33+) RUNTIME PERMISSION                  */
    /* --------------------------------------------------------------------- */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SOIL,
                "Soil moisture alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Get notified when a plant needs water"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQ
                )
            }
        }
    }

    /* --------------------------------------------------------------------- */
    /*  PERIODIC BACKGROUND WORK (WorkManager)                               */
    /* --------------------------------------------------------------------- */

    private fun scheduleBackgroundCheck() {
        val work = PeriodicWorkRequestBuilder<MoistureCheckWorker>(
            15, TimeUnit.MINUTES          // Android batches intelligently
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "SoilMoistureWorker",         // unique name
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    /* --------------------------------------------------------------------- */
    /*  NAVIGATION HELPERS                                                   */
    /* --------------------------------------------------------------------- */

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun selectButton(selected: TextView) {
        listOf(btnGraph, btnOther1, btnHome, btnOther2, btnSettings)
            .forEach { it.isSelected = (it == selected) }
    }
}
