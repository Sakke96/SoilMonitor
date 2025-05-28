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
    private val sensorKeys = listOf("sensor_u0", "sensor_u1", "sensor_u2", "sensor_u3")
    private val allOptions = listOf("All Sensors") + sensorKeys

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
            handler.postDelayed(this, 60_000L) // opnieuw na 60 seconden
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

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, allOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sensorSpinner.adapter = adapter
        sensorSpinner.setSelection(0, false)

        sensorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                updateChart(allOptions[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Start direct en herhaal elke minuut
        handler.post(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop de automatische refresh als view weg is
        handler.removeCallbacks(refreshRunnable)
    }

    private fun fetchSoilData() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // optioneel: log of toon foutmelding
            }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jsonArray = JSONObject(body).getJSONArray("items")
                    dataList = List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }

                    activity?.runOnUiThread {
                        if (isAdded) {
                            // Behoud de huidige selectie en update de grafiek
                            val selected = allOptions[sensorSpinner.selectedItemPosition]
                            updateChart(selected)
                        }
                    }
                }
            }
        })
    }

    private fun updateChart(selectedKey: String) {
        // Als we nog geen data hebben, niks doen
        if (dataList.isEmpty()) return

        val labels = mutableListOf<String>()
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        if (selectedKey == "All Sensors") {
            val dataSets = mutableListOf<ILineDataSet>()
            for ((index, sensorKey) in sensorKeys.withIndex()) {
                val entries = mutableListOf<Entry>()
                dataList.forEachIndexed { i, item ->
                    val value = item.optInt(sensorKey, -1)
                    if (value >= 0) {
                        entries.add(Entry(i.toFloat(), value.toFloat()))
                        if (labels.size <= i) {
                            val t = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                            labels.add(t.format(timeFormatter))
                        }
                    }
                }
                val ds = LineDataSet(entries, sensorKey).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    color = sensorColors[index % sensorColors.size]
                }
                dataSets.add(ds)
            }
            lineChart.data = LineData(dataSets)
            lineChart.legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                form = Legend.LegendForm.LINE
            }
        } else {
            val entries = mutableListOf<Entry>()
            dataList.forEachIndexed { i, item ->
                val value = item.optInt(selectedKey, -1)
                if (value >= 0) {
                    entries.add(Entry(i.toFloat(), value.toFloat()))
                    val t = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                    labels.add(t.format(timeFormatter))
                }
            }
            val ds = LineDataSet(entries, selectedKey).apply {
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                color = sensorColors[sensorKeys.indexOf(selectedKey) % sensorColors.size]
            }
            lineChart.data = LineData(ds)
            lineChart.legend.isEnabled = false
        }

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

            // Marker alleen instellen als er data is
            if (data != null && data.dataSetCount > 0) {
                val marker = CustomMarkerView(requireContext())
                marker.chartView = this
                this.marker = marker
            } else {
                this.marker = null
            }

            invalidate()
        }
    }
}
