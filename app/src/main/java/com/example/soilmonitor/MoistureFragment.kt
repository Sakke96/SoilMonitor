package com.example.soilmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt

class MoistureFragment : Fragment() {

    /* ---- CONFIG --------------------------------------------------------- */
    private val ALERT_INTERVAL_MS = 2 * 60 * 60 * 1_000L      // 2 hours

    /* ---- UI + prefs ------------------------------------------------------ */
    private lateinit var waveViews: List<WaveView>
    private lateinit var valueTexts: List<TextView>
    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    /* ---- per-plant constants -------------------------------------------- */
    private lateinit var sensorKeys: List<String>
    private lateinit var dryValues : List<Float>
    private lateinit var wetValues : List<Float>

    private val defaultWaveColor = Color.parseColor("#0097A7")
    private val alertWaveColor   = Color.RED

    /* ---- repeat-every-minute updater ------------------------------------ */
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchLatestMoisture()
            handler.postDelayed(this, 60_000L)
        }
    }

    /* --------------------------------------------------------------------- */
    /*  Fragment lifecycle                                                   */
    /* --------------------------------------------------------------------- */

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_moisture, container, false)

        val plantCount = prefs.getInt("plantCount", 4).coerceIn(1, 9)

        /* dynamic grid sizing */
        val cols = if (plantCount <= 6) 2 else 3
        val rows = ceil(plantCount / cols.toDouble()).toInt()
        root.findViewById<GridLayout>(R.id.moistureGrid).apply {
            columnCount = cols
            rowCount    = rows
        }

        val containerIds = listOf(
            R.id.plant1Container, R.id.plant2Container, R.id.plant3Container,
            R.id.plant4Container, R.id.plant5Container, R.id.plant6Container,
            R.id.plant7Container, R.id.plant8Container, R.id.plant9Container
        )
        containerIds.map { root.findViewById<FrameLayout>(it) }
            .forEachIndexed { idx, frame -> frame.isGone = idx >= plantCount }

        waveViews = listOf(
            root.findViewById(R.id.plant1), root.findViewById(R.id.plant2),
            root.findViewById(R.id.plant3), root.findViewById(R.id.plant4),
            root.findViewById(R.id.plant5), root.findViewById(R.id.plant6),
            root.findViewById(R.id.plant7), root.findViewById(R.id.plant8),
            root.findViewById(R.id.plant9)
        )
        valueTexts = listOf(
            root.findViewById(R.id.plant1Value), root.findViewById(R.id.plant2Value),
            root.findViewById(R.id.plant3Value), root.findViewById(R.id.plant4Value),
            root.findViewById(R.id.plant5Value), root.findViewById(R.id.plant6Value),
            root.findViewById(R.id.plant7Value), root.findViewById(R.id.plant8Value),
            root.findViewById(R.id.plant9Value)
        )

        sensorKeys = List(plantCount) { i -> "sensor_u$i" }
        dryValues  = List(plantCount) { i -> prefs.getFloat("plant_${i + 1}_dry", 0f) }
        wetValues  = List(plantCount) { i -> prefs.getFloat("plant_${i + 1}_wet", 100f) }

        handler.post(refreshRunnable)
        return root
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroyView()
    }

    /* --------------------------------------------------------------------- */
    /*  Data fetch + UI update                                               */
    /* --------------------------------------------------------------------- */

    private fun fetchLatestMoisture() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* ignore */ }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val items = JSONObject(body).getJSONArray("items")
                    if (items.length() == 0) return
                    val latest = items.getJSONObject(items.length() - 1)

                    activity?.runOnUiThread {
                        sensorKeys.forEachIndexed { i, key ->
                            val raw = latest.optDouble(key, -1.0).toFloat()
                            if (raw < 0) return@forEachIndexed

                            /* convert raw value → percentage */
                            val ratio = ((raw - dryValues[i]) /
                                    (wetValues[i] - dryValues[i])).coerceIn(0f, 1f)
                            val percent = (ratio * 100).roundToInt()

                            waveViews[i].apply {
                                progress = ratio
                                setWaveColor(
                                    if (percent < 20) alertWaveColor else defaultWaveColor
                                )
                            }
                            valueTexts[i].text = "$percent%"

                            maybeNotify(i, percent)        // <-- throttle logic inside
                        }
                    }
                }
            }
        })
    }

    /* --------------------------------------------------------------------- */
    /*  Notification logic (2-hour throttle)                                 */
    /* --------------------------------------------------------------------- */

    private fun maybeNotify(index: Int, percent: Int) {
        if (!prefs.getBoolean("notifications", true)) return
        if (percent >= 20) return                       // only when low

        val now  = System.currentTimeMillis()
        val key  = "plant_${index + 1}_last_alert"
        val last = prefs.getLong(key, 0L)

        if (now - last < ALERT_INTERVAL_MS) return     // already warned recently

        sendLowNotification(index, percent)
        prefs.edit().putLong(key, now).apply()         // remember time
    }

    private fun sendLowNotification(index: Int, percent: Int) {
        val builder = NotificationCompat.Builder(
            requireContext(), MainActivity.CHANNEL_ID_SOIL
        )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Plant ${index + 1} needs water 🌱")
            .setContentText("Moisture dropped to $percent % – time to water!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        /* check permission on Android 13+ */
        if (Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(requireContext())
                .notify(200 + index, builder.build())
        }
    }
}
