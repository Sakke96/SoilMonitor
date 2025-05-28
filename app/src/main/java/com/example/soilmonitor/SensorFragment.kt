package com.example.soilmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class SensorFragment : Fragment() {
    private lateinit var lineChart: LineChart
    private lateinit var sensorSpinner: Spinner
    private var dataList: List<JSONObject> = emptyList()

    // Internal sensor keys
    private val sensorKeys = listOf("sensor_u0", "sensor_u1", "sensor_u2", "sensor_u3")
    // User-facing labels for spinner and legend
    private val sensorLabels = listOf(
        "All Sensors",
        "Plant 1 (su0)",
        "Plant 2 (su1)",
        "Plant 3 (su2)",
        "Plant 4 (su3)"
    )

    // Calibration values for boundary lines
    private val dryValues = listOf(372f, 318f, 359f, 421f)
    private val wetValues = listOf(329f, 367f, 385f, 408f)

    private val sensorColors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.MAGENTA
    )

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
        lineChart = view.findViewById(R.id.lineChart)
        sensorSpinner = view.findViewById(R.id.sensorSpinner)

        // Use user-facing labels in spinner
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sensorLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sensorSpinner.adapter = adapter
        sensorSpinner.setSelection(0, false)

        sensorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
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

    private fun fetchSoilData() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
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

        val labels = mutableListOf<String>()
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        // Clear axis and marker
        val leftAxis = lineChart.axisLeft
        leftAxis.removeAllLimitLines()
        lineChart.marker = null

        val dataSets = mutableListOf<ILineDataSet>()

        if (selectionIndex == 0) {
            // All sensors
            sensorKeys.forEachIndexed { idx, key ->
                val entries = mutableListOf<Entry>()
                dataList.forEachIndexed { i, item ->
                    val v = item.optInt(key, -1)
                    if (v >= 0) {
                        entries.add(Entry(i.toFloat(), v.toFloat()))
                        if (labels.size <= i) {
                            val t = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                            labels.add(t.format(timeFormatter))
                        }
                    }
                }
                LineDataSet(entries, sensorLabels[idx+1]).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    color = sensorColors[idx]
                }.also { dataSets.add(it) }
            }
            lineChart.legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                form = Legend.LegendForm.LINE
            }
        } else {
            // Single sensor
            val idx = selectionIndex - 1
            val key = sensorKeys[idx]

            val entries = mutableListOf<Entry>()
            dataList.forEachIndexed { i, item ->
                val v = item.optInt(key, -1)
                if (v >= 0) {
                    entries.add(Entry(i.toFloat(), v.toFloat()))
                    val t = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                    labels.add(t.format(timeFormatter))
                }
            }
            LineDataSet(entries, sensorLabels[selectionIndex]).apply {
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                color = sensorColors[idx]
            }.also { dataSets.add(it) }

            // Boundary lines
            leftAxis.addLimitLine(LimitLine(dryValues[idx], "Dry").apply {
                lineWidth = 2f; lineColor = android.graphics.Color.RED; textColor = android.graphics.Color.RED; textSize = 12f
            })
            leftAxis.addLimitLine(LimitLine(wetValues[idx], "Wet").apply {
                lineWidth = 2f; lineColor = android.graphics.Color.RED; textColor = android.graphics.Color.RED; textSize = 12f
            })

            lineChart.legend.isEnabled = false

            // Marker
            lineChart.marker = CustomMarkerView(requireContext()).also { it.chartView = lineChart }
        }

        lineChart.data = LineData(dataSets)
        lineChart.apply {
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
            }
            axisRight.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            description.isEnabled = false
            animateX(1000)
            invalidate()
        }
    }
}