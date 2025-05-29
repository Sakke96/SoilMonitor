package com.example.soilmonitor

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
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

class SensorFragment : Fragment() {
    private lateinit var lineChart: LineChart
    private lateinit var sensorSpinner: Spinner
    private lateinit var prefs: SharedPreferences

    // Runtime-built lists
    private lateinit var sensorKeys: List<String>
    private lateinit var sensorLabels: List<String>
    private lateinit var dryValues: List<Float>
    private lateinit var wetValues: List<Float>

    private val sensorColors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.MAGENTA
    )

    private var dataList: List<JSONObject> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchSoilData()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sensor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        lineChart = view.findViewById(R.id.lineChart)
        sensorSpinner = view.findViewById(R.id.sensorSpinner)

        /* ------------ 1.  Build lists from Settings  ----------- */

        val plantCount = prefs.getInt("plantCount", 4)

        // ensure defaults are present
        for (i in 1..plantCount) {
            val dryKey = "plant_${i}_dry"
            val wetKey = "plant_${i}_wet"
            if (!prefs.contains(dryKey)) {
                val defaultDry = when (i) {
                    1 -> 372f; 2 -> 318f; 3 -> 359f; 4 -> 421f; else -> 0f
                }
                prefs.edit().putFloat(dryKey, defaultDry).apply()
            }
            if (!prefs.contains(wetKey)) {
                val defaultWet = when (i) {
                    1 -> 329f; 2 -> 367f; 3 -> 385f; 4 -> 408f; else -> 100f
                }
                prefs.edit().putFloat(wetKey, defaultWet).apply()
            }
        }

        sensorKeys   = List(plantCount) { i -> "sensor_u$i" }
        sensorLabels = listOf("All Sensors") +
                List(plantCount) { i -> "Plant ${i + 1}" }
        dryValues    = List(plantCount) { i -> prefs.getFloat("plant_${i + 1}_dry", 0f) }
        wetValues    = List(plantCount) { i -> prefs.getFloat("plant_${i + 1}_wet", 100f) }

        /* ------------ 2.  Spinner  ------------------------------ */

        ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            sensorLabels
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sensorSpinner.adapter = adapter
        }
        sensorSpinner.setSelection(0, false)
        sensorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                updateChart(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        handler.post(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    /* ------------ 3.  REST fetch  ------------------------------ */

    private fun fetchSoilData() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {/* ignore */}
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jsonArray = JSONObject(body).getJSONArray("items")
                    dataList = List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }
                    activity?.runOnUiThread {
                        if (isAdded) updateChart(sensorSpinner.selectedItemPosition)
                    }
                }
            }
        })
    }

    /* ------------ 4.  Chart logic  ----------------------------- */

    private fun updateChart(selectionIndex: Int) {
        if (dataList.isEmpty()) return

        val labels = mutableListOf<String>()
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val leftAxis = lineChart.axisLeft
        leftAxis.removeAllLimitLines()
        lineChart.marker = null

        val dataSets = mutableListOf<ILineDataSet>()

        if (selectionIndex == 0) {
            /* ---- 4A.  ALL sensors – keep MPAndroidChart’s auto-scaling ---- */

            sensorKeys.forEachIndexed { idx, key ->
                val entries = mutableListOf<Entry>()
                dataList.forEachIndexed { i, item ->
                    item.optInt(key, -1).takeIf { it >= 0 }?.let { v ->
                        entries.add(Entry(i.toFloat(), v.toFloat()))
                        if (labels.size <= i) {
                            labels.add(
                                OffsetDateTime.parse(item.getString("created_at"))
                                    .plusHours(2)
                                    .format(timeFmt)
                            )
                        }
                    }
                }
                dataSets += LineDataSet(entries, sensorLabels[idx + 1]).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }
                }
            }
            lineChart.legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                form = Legend.LegendForm.LINE
            }
            lineChart.setAutoScaleMinMaxEnabled(true) // <-- AUTO again
        } else {
            /* ---- 4B.  Single sensor with controlled zoom ---- */

            val idx = selectionIndex - 1
            val key = sensorKeys[idx]
            val entries = mutableListOf<Entry>()

            dataList.forEachIndexed { i, item ->
                item.optInt(key, -1).takeIf { it >= 0 }?.let { v ->
                    entries.add(Entry(i.toFloat(), v.toFloat()))
                    labels.add(
                        OffsetDateTime.parse(item.getString("created_at"))
                            .plusHours(2)
                            .format(timeFmt)
                    )
                }
            }

            dataSets += LineDataSet(entries, sensorLabels[selectionIndex]).apply {
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }
            }

            /* 4B-1. Limit lines */
            val wet = wetValues[idx]
            val dry = dryValues[idx]
            leftAxis.addLimitLine(LimitLine(dry, "Dry"))
            leftAxis.addLimitLine(LimitLine(wet, "Wet"))

            /* 4B-2. Axis bounds */
            val minRange = min(wet, dry)
            val maxRange = max(wet, dry)
            val span     = maxRange - minRange        // full “normal” range

            var axisMin = minRange
            var axisMax = maxRange

            if (entries.isNotEmpty()) {
                val dataMin = entries.minOf { it.y }
                val dataMax = entries.maxOf { it.y }

                if (dataMin < minRange) {
                    axisMin = max(dataMin, minRange - span)   // ≤ 100 % below
                }
                if (dataMax > maxRange) {
                    axisMax = min(dataMax, maxRange + span)   // ≤ 100 % above
                }
            }

            leftAxis.axisMinimum = axisMin
            leftAxis.axisMaximum = axisMax
            lineChart.setAutoScaleMinMaxEnabled(false)

            lineChart.legend.isEnabled = false
            lineChart.marker = CustomMarkerView(requireContext())
                .also { it.chartView = lineChart }
        }

        /* ---- 4C.  Chart cosmetics shared by both branches ---- */

        lineChart.data = LineData(dataSets)
        lineChart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
        }
        lineChart.axisRight.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.description.isEnabled = false
        lineChart.animateX(1_000)
        lineChart.invalidate()
    }
}
