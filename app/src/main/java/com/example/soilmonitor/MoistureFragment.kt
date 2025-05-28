package com.example.soilmonitor

import android.content.SharedPreferences
import android.graphics.Color
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
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import kotlin.math.ceil

class MoistureFragment : Fragment() {
    private lateinit var waveViews: List<WaveView>
    private lateinit var valueTexts: List<TextView>
    private lateinit var prefs: SharedPreferences

    // built at runtime for only the visible sensors:
    private lateinit var sensorKeys: List<String>
    private lateinit var dryValues: List<Float>
    private lateinit var wetValues: List<Float>

    private val defaultWaveColor = Color.parseColor("#0097A7")
    private val alertWaveColor   = Color.RED

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchLatestMoisture()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_moisture, container, false)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // 1) Read how many plants (1–9)
        val plantCount = prefs.getInt("plantCount", 4).coerceIn(1, 9)

        // 2) Compute columns & rows
        val grid = root.findViewById<GridLayout>(R.id.moistureGrid)
        val cols = if (plantCount <= 6) 2 else 3
        val rows = ceil(plantCount / cols.toDouble()).toInt()
        grid.columnCount = cols
        grid.rowCount    = rows

        // 3) Hide any containers beyond plantCount
        val containerIds = listOf(
            R.id.plant1Container, R.id.plant2Container, R.id.plant3Container,
            R.id.plant4Container, R.id.plant5Container, R.id.plant6Container,
            R.id.plant7Container, R.id.plant8Container, R.id.plant9Container
        )
        val containers = containerIds.map { root.findViewById<FrameLayout>(it) }
        containers.forEachIndexed { idx, frame ->
            frame.isGone = idx >= plantCount
        }

        // 4) Grab the WaveViews & TextViews for _all_ 9 slots
        waveViews = listOf(
            root.findViewById(R.id.plant1),
            root.findViewById(R.id.plant2),
            root.findViewById(R.id.plant3),
            root.findViewById(R.id.plant4),
            root.findViewById(R.id.plant5),
            root.findViewById(R.id.plant6),
            root.findViewById(R.id.plant7),
            root.findViewById(R.id.plant8),
            root.findViewById(R.id.plant9)
        )
        valueTexts = listOf(
            root.findViewById(R.id.plant1Value),
            root.findViewById(R.id.plant2Value),
            root.findViewById(R.id.plant3Value),
            root.findViewById(R.id.plant4Value),
            root.findViewById(R.id.plant5Value),
            root.findViewById(R.id.plant6Value),
            root.findViewById(R.id.plant7Value),
            root.findViewById(R.id.plant8Value),
            root.findViewById(R.id.plant9Value)
        )

        // 5) Build sensorKeys & calibration arrays for exactly plantCount sensors
        sensorKeys = List(plantCount) { i -> "sensor_u$i" }
        dryValues   = List(plantCount) { i -> prefs.getFloat("plant_${i+1}_dry", 0f) }
        wetValues   = List(plantCount) { i -> prefs.getFloat("plant_${i+1}_wet", 100f) }

        handler.post(refreshRunnable)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun fetchLatestMoisture() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* handle error */ }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val items = JSONObject(body).getJSONArray("items")
                    if (items.length() == 0) return
                    val latest = items.getJSONObject(items.length() - 1)

                    activity?.runOnUiThread {
                        sensorKeys.forEachIndexed { i, key ->
                            val raw = latest.optDouble(key, -1.0).toFloat()
                            if (raw >= 0f) {
                                val ratio = ((raw - dryValues[i]) /
                                        (wetValues[i] - dryValues[i]))
                                    .coerceIn(0f, 1f)
                                val percent = (ratio * 100).toInt()

                                waveViews[i].apply {
                                    setWaveColor(
                                        if (percent < 20) alertWaveColor
                                        else defaultWaveColor
                                    )
                                    progress = ratio
                                }
                                valueTexts[i].text = "$percent%"
                            }
                        }
                    }
                }
            }
        })
    }
}
