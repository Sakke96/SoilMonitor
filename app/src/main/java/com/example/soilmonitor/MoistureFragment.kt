package com.example.soilmonitor

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class MoistureFragment : Fragment() {
    private lateinit var waveViews: List<WaveView>
    private lateinit var valueTexts: List<TextView>
    private val sensorKeys = listOf("sensor_u0", "sensor_u1", "sensor_u2", "sensor_u3")

    // Calibration values: [dry, wet] for each sensor
    private val dryValues = listOf(372f, 318f, 359f, 421f)
    private val wetValues = listOf(329f, 367f, 385f, 408f)

    // Wave colors
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
            override fun onFailure(call: Call, e: IOException) { /* handle */ }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val items = JSONObject(body).getJSONArray("items")
                    if (items.length() == 0) return
                    val latest = items.getJSONObject(items.length() - 1)

                    activity?.runOnUiThread {
                        sensorKeys.forEachIndexed { i, key ->
                            val raw = latest.optDouble(key, -1.0).toFloat()
                            if (raw >= 0f) {
                                val ratioUnclamped = (raw - dryValues[i]) /
                                        (wetValues[i] - dryValues[i])
                                val ratio = ratioUnclamped.coerceIn(0f, 1f)
                                val percent = (ratio * 100).toInt()

                                // 1) tint wave
                                waveViews[i].setWaveColor(
                                    if (percent < 20) alertWaveColor
                                    else defaultWaveColor
                                )
                                // 2) update level + text
                                waveViews[i].progress = ratio
                                valueTexts[i].text = "$percent%"
                            }
                        }
                    }
                }
            }
        })
    }
}
