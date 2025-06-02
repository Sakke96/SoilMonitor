package com.example.soilmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView

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

        /* ---------- bottom‐nav setup ---------- */
        bottomNavigation = findViewById(R.id.bottomNavigation)

        // When the fragment first loads, default to “Home”
        bottomNavigation.selectedItemId = R.id.nav_home
        loadFragment(MoistureFragment())

        bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_graph -> {
                    loadFragment(SensorFragment())
                    true
                }
                R.id.nav_surrounding -> {
                    loadFragment(SurroundingFragment())
                    true
                }
                R.id.nav_home -> {
                    loadFragment(MoistureFragment())
                    true
                }
                R.id.nav_photo -> {
                    loadFragment(PhotoFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
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
}
