package com.example.soilmonitor

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {
    private lateinit var lineChart: LineChart
    private lateinit var sensorSpinner: Spinner
    private var dataList: List<JSONObject> = emptyList()
    private val sensorKeys = listOf("sensor_u0", "sensor_u1", "sensor_u2", "sensor_u3")
    private val allOptions = listOf("All Sensors") + sensorKeys

    // Custom colors for each sensor line
    private val sensorColors = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.MAGENTA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lineChart = findViewById(R.id.lineChart)
        sensorSpinner = findViewById(R.id.sensorSpinner)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allOptions)
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
                runOnUiThread {
                    // handle failure
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jsonArray = JSONObject(body).getJSONArray("items")
                    dataList = List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }

                    runOnUiThread {
                        updateChart(sensorKeys.first())
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

                val dataSet = LineDataSet(entries, sensorKey)
                dataSet.lineWidth = 2f
                dataSet.setDrawCircles(false)
                dataSet.setDrawValues(false)
                dataSet.color = sensorColors[index % sensorColors.size]
                dataSet.setCircleColor(sensorColors[index % sensorColors.size])
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

            val dataSet = LineDataSet(entries, selectedKey)
            dataSet.lineWidth = 2f
            dataSet.setDrawCircles(false)
            dataSet.setDrawValues(false)
            dataSet.color = sensorColors[sensorKeys.indexOf(selectedKey) % sensorColors.size]
            dataSet.setCircleColor(sensorColors[sensorKeys.indexOf(selectedKey) % sensorColors.size])

            lineChart.data = LineData(dataSet)
            lineChart.legend.isEnabled = false
        }

        lineChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        lineChart.xAxis.setDrawGridLines(false)
        lineChart.axisRight.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.description.isEnabled = false
        lineChart.animateX(1000)
        lineChart.invalidate()

        val marker = CustomMarkerView(this)
        marker.chartView = lineChart
        lineChart.marker = marker
    }
}
