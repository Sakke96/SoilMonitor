package com.example.soilmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class SurroundingFragment : Fragment() {
    private lateinit var surroundingChart: LineChart
    private lateinit var switchTemp: Switch
    private lateinit var switchHumidity: Switch
    private lateinit var switchCO2: Switch
    private lateinit var switchPH: Switch
    private lateinit var switchPPM: Switch
    private lateinit var switchTC: Switch

    private var dataList: List<JSONObject> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchSurroundingData()
            handler.postDelayed(this, 60_000L)
        }
    }

    // keys, labels, and colors for each metric
    private val sensorKeys = listOf(
        "sensor_temp", "sensor_hu", "sensor_co2",
        "sensor_ph", "sensor_ppm", "sensor_tc"
    )
    private val sensorLabels = listOf(
        "Temp", "Humidity", "CO₂", "pH", "PPM", "TC"
    )
    private val sensorColors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.CYAN,
        android.graphics.Color.YELLOW
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_surrounding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        surroundingChart = view.findViewById(R.id.surroundingChart)
        switchTemp     = view.findViewById(R.id.switchTemp)
        switchHumidity = view.findViewById(R.id.switchHumidity)
        switchCO2      = view.findViewById(R.id.switchCO2)
        switchPH       = view.findViewById(R.id.switchPH)
        switchPPM      = view.findViewById(R.id.switchPPM)
        switchTC       = view.findViewById(R.id.switchTC)

        listOf(switchTemp, switchHumidity, switchCO2, switchPH, switchPPM, switchTC)
            .forEach { sw ->
                sw.setOnCheckedChangeListener { _, _ -> updateChart() }
            }

        handler.post(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun fetchSurroundingData() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* ignore */ }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jsonArray = JSONObject(body).getJSONArray("items")
                    dataList = List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }
                    activity?.runOnUiThread { if (isAdded) updateChart() }
                }
            }
        })
    }

    private fun updateChart() {
        if (dataList.isEmpty()) return

        // clear previous
        val xAxis    = surroundingChart.xAxis
        xAxis.removeAllLimitLines()
        xAxis.setDrawLimitLinesBehindData(true)
        surroundingChart.axisLeft.removeAllLimitLines()

        // prepare data containers
        val labels   = mutableListOf<String>()
        val dataSets: MutableList<ILineDataSet> = mutableListOf()  // updated to ILineDataSet
        val timeFmt  = DateTimeFormatter.ofPattern("HH:mm")

        // which sensors to include
        val toggles = listOf(
            switchTemp.isChecked,
            switchHumidity.isChecked,
            switchCO2.isChecked,
            switchPH.isChecked,
            switchPPM.isChecked,
            switchTC.isChecked
        )

        // build entries per sensor
        sensorKeys.forEachIndexed { idx, key ->
            if (!toggles[idx]) return@forEachIndexed
            val entries = mutableListOf<Entry>()
            dataList.forEachIndexed { pos, item ->
                val ts = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                if (idx == 0) labels += ts.format(timeFmt)
                item.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
                    ?.let { Entry(labels.size - 1f, it.toFloat()) }
                    ?.let { entries += it }
            }
            if (entries.isNotEmpty()) {
                dataSets += LineDataSet(entries, sensorLabels[idx]).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }
                }
            }
        }

        // render
        surroundingChart.data = LineData(dataSets)
        xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
        }
        surroundingChart.axisRight.isEnabled = false
        surroundingChart.setTouchEnabled(true)
        surroundingChart.setPinchZoom(true)
        surroundingChart.description.isEnabled = false
        surroundingChart.animateX(1_000)
        surroundingChart.invalidate()
    }
}