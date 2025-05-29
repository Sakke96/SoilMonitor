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
import android.widget.CheckBox
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class SensorFragment : Fragment() {
    // UI
    private lateinit var lineChart: LineChart
    private lateinit var sensorSpinner: Spinner
    private lateinit var hideNightCheckBox: CheckBox
    private lateinit var hideSeparatorCheckBox: CheckBox
    private lateinit var last24hCheckBox: CheckBox  // new

    // prefs + data
    private lateinit var prefs: SharedPreferences
    private var dataList: List<JSONObject> = emptyList()

    // runtime lists
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

    // auto-refresh
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
        hideNightCheckBox = view.findViewById(R.id.hideNightCheckBox)
        hideSeparatorCheckBox = view.findViewById(R.id.hideSeparatorCheckBox)
        last24hCheckBox = view.findViewById(R.id.last24hCheckBox)  // new

        // Build sensor lists from prefs
        val plantCount = prefs.getInt("plantCount", 4)
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
        sensorLabels = listOf("All Sensors") + List(plantCount) { i -> "Plant ${i+1}" }
        dryValues    = List(plantCount) { i -> prefs.getFloat("plant_${i+1}_dry", 0f) }
        wetValues    = List(plantCount) { i -> prefs.getFloat("plant_${i+1}_wet", 100f) }

        // Spinner setup
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
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                updateChart(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Checkbox listeners
        hideNightCheckBox.setOnCheckedChangeListener { _, _ ->
            updateChart(sensorSpinner.selectedItemPosition)
        }
        hideSeparatorCheckBox.setOnCheckedChangeListener { _, _ ->
            updateChart(sensorSpinner.selectedItemPosition)
        }
        last24hCheckBox.setOnCheckedChangeListener { _, _ ->  // new
            updateChart(sensorSpinner.selectedItemPosition)
        }

        // start auto-refresh
        handler.post(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun fetchSoilData() {
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
                    activity?.runOnUiThread {
                        if (isAdded) updateChart(sensorSpinner.selectedItemPosition)
                    }
                }
            }
        })
    }

    private fun updateChart(selectionIndex: Int) {
        if (dataList.isEmpty()) return

        // 0. CLEAR previous state
        val leftAxis = lineChart.axisLeft
        leftAxis.removeAllLimitLines()
        leftAxis.resetAxisMinimum()
        leftAxis.resetAxisMaximum()
        lineChart.marker = null

        // 1. read checkboxes
        val hideNight      = hideNightCheckBox.isChecked
        val hideSeparators = hideSeparatorCheckBox.isChecked
        val last24h        = last24hCheckBox.isChecked  // new

        // 2. prepare x-axis
        val labels  = mutableListOf<String>()
        val xAxis   = lineChart.xAxis
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val dateFmt = DateTimeFormatter.ofPattern("dd MMM")

        xAxis.removeAllLimitLines()
        xAxis.setDrawLimitLinesBehindData(true)

        // 3. filter & optionally add day lines
        val visibleIdx = mutableListOf<Int>()
        var lastDate: LocalDate? = null

        // compute cutoff
        val now    = OffsetDateTime.now().plusHours(2)
        val cutoff = now.minusHours(24)

        dataList.forEachIndexed { rawIdx, item ->
            val ts = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)

            // new: last 24h filter
            if (last24h && ts.isBefore(cutoff)) return@forEachIndexed

            if (hideNight && ts.hour < 6) return@forEachIndexed

            val curDate = ts.toLocalDate()
            if (!hideSeparators && curDate != lastDate) {
                val ll = LimitLine(visibleIdx.size.toFloat(), curDate.format(dateFmt)).apply {
                    lineWidth = 1f
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                    textSize = 10f
                }
                xAxis.addLimitLine(ll)
                lastDate = curDate
            }

            labels += ts.format(timeFmt)
            visibleIdx += rawIdx
        }

        // 4. build data sets
        val dataSets = mutableListOf<ILineDataSet>()
        if (selectionIndex == 0) {
            // ALL SENSORS
            sensorKeys.forEachIndexed { idx, key ->
                val entries = visibleIdx.mapIndexedNotNull { pos, rawIdx ->
                    dataList[rawIdx].optInt(key, -1)
                        .takeIf { it >= 0 }
                        ?.let { Entry(pos.toFloat(), it.toFloat()) }
                }
                if (entries.isNotEmpty()) {
                    dataSets += LineDataSet(entries, sensorLabels[idx+1]).apply {
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }
                    }
                }
            }
            lineChart.legend.apply {
                isEnabled = true
                form = Legend.LegendForm.LINE
            }
            lineChart.setAutoScaleMinMaxEnabled(true)
        } else {
            // SINGLE SENSOR
            val idx = selectionIndex - 1
            val key = sensorKeys[idx]
            val entries = visibleIdx.mapIndexedNotNull { pos, rawIdx ->
                dataList[rawIdx].optInt(key, -1)
                    .takeIf { it >= 0 }
                    ?.let { Entry(pos.toFloat(), it.toFloat()) }
            }
            dataSets += LineDataSet(entries, sensorLabels[selectionIndex]).apply {
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }
            }
            // wet/dry bands
            leftAxis.apply {
                addLimitLine(LimitLine(dryValues[idx], "Dry"))
                addLimitLine(LimitLine(wetValues[idx], "Wet"))
            }
            // manual Y bounds
            val minRange = min(wetValues[idx], dryValues[idx])
            val maxRange = max(wetValues[idx], dryValues[idx])
            val span     = maxRange - minRange
            var axisMin  = minRange
            var axisMax  = maxRange
            if (entries.isNotEmpty()) {
                val dataMin = entries.minOf { it.y }
                val dataMax = entries.maxOf { it.y }
                if (dataMin < minRange) axisMin = max(dataMin, minRange - span)
                if (dataMax > maxRange) axisMax = min(dataMax, maxRange + span)
            }
            leftAxis.axisMinimum = axisMin
            leftAxis.axisMaximum = axisMax
            lineChart.setAutoScaleMinMaxEnabled(false)

            lineChart.marker = CustomMarkerView(requireContext()).also { it.chartView = lineChart }
            lineChart.legend.isEnabled = false
        }

        // 5. final chart draw
        lineChart.data = LineData(dataSets)
        xAxis.apply {
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
