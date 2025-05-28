package com.example.soilmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.soilmonitor.WaveView
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class MoistureFragment : Fragment() {
    private lateinit var waveViews: List<WaveView>
    private lateinit var valueTexts: List<TextView>
    private val sensorKeys = listOf("sensor_u0", "sensor_u1", "sensor_u2", "sensor_u3")
    private val maxValue = 1023f

    // Handler voor periodieke updates
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchLatestMoisture()
            handler.postDelayed(this, 60_000L) // na 60 sec opnieuw
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_moisture, container, false)

        // Initialiseer WaveViews en bijbehorende TextViews
        waveViews = listOf(
            root.findViewById(R.id.plant1),
            root.findViewById(R.id.plant2),
            root.findViewById(R.id.plant3),
            root.findViewById(R.id.plant4)
        )
        valueTexts = listOf(
            root.findViewById(R.id.plant1Value),
            root.findViewById(R.id.plant2Value),
            root.findViewById(R.id.plant3Value),
            root.findViewById(R.id.plant4Value)
        )

        // Start direct én periodiek elke minuut
        handler.post(refreshRunnable)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop de automatische updates
        handler.removeCallbacks(refreshRunnable)
    }

    private fun fetchLatestMoisture() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Optioneel: log of toon foutmelding
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val items = JSONObject(body).getJSONArray("items")
                    if (items.length() == 0) return

                    // Pak het meest recente datapunt
                    val latest = items.getJSONObject(items.length() - 1)

                    // UI-update
                    activity?.runOnUiThread {
                        sensorKeys.forEachIndexed { index, key ->
                            val rawValue = latest.optDouble(key, -1.0)
                            if (rawValue >= 0) {
                                // Bereken ratio (0..1) en clamp
                                val ratio = (rawValue.toFloat() / maxValue).coerceIn(0f, 1f)
                                waveViews.getOrNull(index)?.progress = ratio
                                val percent = (ratio * 100).toInt()
                                valueTexts.getOrNull(index)?.text = "$percent%"
                            }
                        }
                    }
                }
            }
        })
    }
}
