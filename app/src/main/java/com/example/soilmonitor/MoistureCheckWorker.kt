// MoistureCheckWorker.kt
package com.example.soilmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class MoistureCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val ALERT_INTERVAL_MS = 2 * 60 * 60 * 1_000L   // 2 hours

    override fun doWork(): Result {
        val ctx   = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // only send alerts if user enabled notifications
        if (!prefs.getBoolean("notifications", false)) {
            return Result.success()
        }

        val plantCount = prefs.getInt("plantCount", 4).coerceIn(1, 9)
        val sensorKeys = List(plantCount) { i -> "sensor_u$i" }
        val dryVals    = FloatArray(plantCount) { i -> prefs.getFloat("plant_${i + 1}_dry", 0f) }
        val wetVals    = FloatArray(plantCount) { i -> prefs.getFloat("plant_${i + 1}_wet", 100f) }

        /* ----- fetch latest row ----- */
        val body = try {
            OkHttpClient().newCall(
                Request.Builder()
                    .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                    .build()
            ).execute().use { it.body?.string() }
        } catch (e: IOException) {
            return Result.retry()
        } ?: return Result.retry()

        val items = JSONObject(body).getJSONArray("items")
        if (items.length() == 0) return Result.success()
        val latest = items.getJSONObject(items.length() - 1)

        /* ----- per-plant evaluation ----- */
        val now = System.currentTimeMillis()
        sensorKeys.forEachIndexed { idx, key ->
            val raw = latest.optDouble(key, -1.0).toFloat()
            if (raw < 0) return@forEachIndexed

            val ratio = ((raw - dryVals[idx]) /
                    (wetVals[idx] - dryVals[idx])).coerceIn(0f, 1f)
            val percent = (ratio * 100).toInt()

            if (percent >= 20) return@forEachIndexed

            val timeKey   = "plant_${idx + 1}_last_alert"
            val lastAlert = prefs.getLong(timeKey, 0L)
            if (now - lastAlert < ALERT_INTERVAL_MS) return@forEachIndexed

            sendLowNotification(idx, percent, ctx)
            prefs.edit().putLong(timeKey, now).apply()
        }
        return Result.success()
    }

    /* ----- build + show notification ----- */
    private fun sendLowNotification(index: Int, percent: Int, ctx: Context) {

        /* ensure channel exists */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        MainActivity.CHANNEL_ID_SOIL,
                        "Soil moisture alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Alerts when a plant falls below 20 % moisture"
                    }
                )
        }

        val notification = NotificationCompat.Builder(ctx, MainActivity.CHANNEL_ID_SOIL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Plant ${index + 1} needs water 🌱")
            .setContentText("Moisture dropped to $percent % – time to water!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(ctx).notify(300 + index, notification)
        }
    }
}
