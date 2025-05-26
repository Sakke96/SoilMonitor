package com.example.soilmonitor

import android.os.Bundle
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
import com.github.mikephil.charting.data.*
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_sensor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lineChart = view.findViewById(R.id.lineChart)
        sensorSpinner = view.findViewById(R.id.sensorSpinner)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, allOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sensorSpinner.adapter = adapter

        sensorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = allOptions[position]
                updateChart(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        fetchSoilData()
    }

    private fun fetchSoilData() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // You can log or show a toast here if needed
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jsonArray = JSONObject(body).getJSONArray("items")
                    dataList = List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }

                    // ✅ FIX: Safely check if Fragment is still active
                    activity?.runOnUiThread {
                        if (isAdded) {
                            updateChart(sensorKeys.first())
                        }
                    }
                }
            }
        })
    }

    private fun updateChart(selectedKey: String) {
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
                            val adjustedTime = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                            labels.add(adjustedTime.format(timeFormatter))
                        }
                    }
                }

                val dataSet = LineDataSet(entries, sensorKey).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    color = sensorColors[index % sensorColors.size]
                    setCircleColor(sensorColors[index % sensorColors.size])
                }

                dataSets.add(dataSet)
            }

            lineChart.data = LineData(dataSets)
            lineChart.legend.isEnabled = true
            lineChart.legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            lineChart.legend.form = Legend.LegendForm.LINE
        } else {
            val entries = mutableListOf<Entry>()
            dataList.forEachIndexed { index, item ->
                val value = item.optInt(selectedKey, -1)
                if (value >= 0) {
                    entries.add(Entry(index.toFloat(), value.toFloat()))
                    val adjustedTime = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                    labels.add(adjustedTime.format(timeFormatter))
                }
            }

            val dataSet = LineDataSet(entries, selectedKey).apply {
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                color = sensorColors[sensorKeys.indexOf(selectedKey) % sensorColors.size]
                setCircleColor(sensorColors[sensorKeys.indexOf(selectedKey) % sensorColors.size])
            }

            lineChart.data = LineData(dataSet)
            lineChart.legend.isEnabled = false
        }

        lineChart.apply {
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            description.isEnabled = false
            animateX(1000)

            val marker = CustomMarkerView(requireContext())
            marker.chartView = this
            this.marker = marker

            invalidate()
        }
    }
}
