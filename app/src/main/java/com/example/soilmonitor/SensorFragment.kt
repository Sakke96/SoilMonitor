package com.example.soilmonitor

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class SensorReading(
    val readingId: Int,
    val temperature: Double?,
    val humidity: Double?,
    val co2: Int?,
    val waterlevel: Int?,
    val timestamp: OffsetDateTime
)

data class SensorRange(
    val sensorIdx: Int,
    val min: Float,
    val max: Float,
    val label: String,
    val unit: String,
    val color: Int
)

class SensorFragment : Fragment() {

    /* ---------- UI ---------- */
    private lateinit var chart: LineChart
    private lateinit var sensorLayout: LinearLayout
    private lateinit var hideNightBox: CheckBox
    private lateinit var hideSepBox: CheckBox
    private lateinit var rangeGroup: RadioGroup
    private lateinit var range24hRadio: RadioButton
    private lateinit var rangeLastDipRadio: RadioButton
    private lateinit var rangeAllRadio: RadioButton
    private lateinit var bridgeBox: CheckBox
    private lateinit var predictionTxt: TextView

    /* ---------- prefs / raw data ---------- */
    private lateinit var prefs: SharedPreferences
    private var dataList: List<SensorReading> = emptyList()

    /* ---------- static meta ---------- */
    private val sensorTypes = listOf("waterlevel", "temperature", "humidity", "co2")
    private val sensorLabels = listOf("Waterlevel", "Temperature", "Humidity", "CO2")
    private val sensorUnits = listOf("%", "°C", "%", "ppm")

    /** Fixed palette for different sensor types */
    private val colours = listOf(
        android.graphics.Color.BLUE,     // Waterlevel - Blue
        android.graphics.Color.RED,      // Temperature - Red
        android.graphics.Color.GREEN,    // Humidity - Green
        android.graphics.Color.MAGENTA   // CO2 - Magenta
    )

    /* ---------- multi-selection state ---------- */
    private val selectedSensors = mutableSetOf<Int>() // Track which sensors are selected
    private val sensorButtons = mutableListOf<Button>()

    /* ---------- sensor ranges for right axis ---------- */
    private val rightSensorRanges = mutableListOf<SensorRange>()

    /* ---------- polling ---------- */
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            fetch()
            handler.postDelayed(this, 60_000L)
        }
    }

    /* ==================== */
    /* Lifecycle */
    /* ==================== */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_sensor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /* ---- bind views ---- */
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        chart = view.findViewById(R.id.lineChart)
        sensorLayout = view.findViewById(R.id.sensorLayout)
        hideNightBox = view.findViewById(R.id.hideNightCheckBox)
        hideSepBox = view.findViewById(R.id.hideSeparatorCheckBox)
        rangeGroup = view.findViewById(R.id.rangeRadioGroup)
        range24hRadio = view.findViewById(R.id.range24hRadio)
        rangeLastDipRadio = view.findViewById(R.id.rangeLastDipRadio)
        rangeAllRadio = view.findViewById(R.id.rangeAllRadio)
        bridgeBox = view.findViewById(R.id.bridgeGapsCheckBox)
        predictionTxt = view.findViewById(R.id.trendPredictionText)

        /* ---- Always include waterlevel by default ---- */
        selectedSensors.add(0) // Waterlevel is always selected

        /* ---- dynamically create sensor type buttons ---- */
        setupSensorButtons()

        /* ---- toggles: on change → redraw() ---- */
        val listener = CompoundButton.OnCheckedChangeListener { _, _ -> redraw() }
        hideNightBox.setOnCheckedChangeListener(listener)
        hideSepBox.setOnCheckedChangeListener(listener)
        rangeGroup.setOnCheckedChangeListener { _, _ -> redraw() }
        bridgeBox.setOnCheckedChangeListener(listener)

        /* initial fetch + start polling */
        fetch()
        handler.post(refresher)
    }

    private fun setupSensorButtons() {
        sensorLayout.removeAllViews()
        sensorButtons.clear()

        val buttonHeightPx = dpToPx(48)

        sensorLabels.forEachIndexed { idx, label ->
            val btn = Button(requireContext()).apply {
                text = label
                tag = idx
                isAllCaps = false
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END

                layoutParams = LinearLayout.LayoutParams(
                    0, buttonHeightPx, 1f
                ).apply {
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }

                gravity = Gravity.CENTER
                updateButtonAppearance(this, idx in selectedSensors)

                setOnClickListener {
                    val clickedIndex = it.tag as Int

                    if (clickedIndex == 0) {
                        // Waterlevel cannot be deselected
                        return@setOnClickListener
                    }

                    // Toggle selection
                    if (clickedIndex in selectedSensors) {
                        selectedSensors.remove(clickedIndex)
                    } else {
                        selectedSensors.add(clickedIndex)
                    }

                    updateButtonAppearance(this, clickedIndex in selectedSensors)
                    redraw()
                }
            }
            sensorButtons.add(btn)
            sensorLayout.addView(btn)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refresher)
    }

    /* ============================ */
    /* Button appearance management */
    /* ============================ */
    private fun updateButtonAppearance(button: Button, isSelected: Boolean) {
        val idx = button.tag as Int
        if (isSelected) {
            button.setBackgroundColor(colours[idx])
            button.setTextColor(android.graphics.Color.WHITE)
        } else {
            button.setBackgroundColor(android.graphics.Color.LTGRAY)
            button.setTextColor(android.graphics.Color.BLACK)
        }
    }

    /* ============================ */
    /* Network: fetch sensor data */
    /* ============================ */
    private fun fetch() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-plantwateringdb.adb.eu-paris-1.oraclecloudapps.com/ords/admin/api/sensors")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* ignore */ }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    try {
                        val json = JSONObject(responseBody)
                        val items = json.getJSONArray("items")

                        dataList = List(items.length()) { i ->
                            val item = items.getJSONObject(i)
                            SensorReading(
                                readingId = item.getInt("reading_id"),
                                temperature = item.optDouble("temperature").takeIf { !item.isNull("temperature") },
                                humidity = item.optDouble("humidity").takeIf { !item.isNull("humidity") },
                                co2 = item.optInt("co2").takeIf { !item.isNull("co2") },
                                waterlevel = item.optInt("waterlevel").takeIf { !item.isNull("waterlevel") },
                                timestamp = OffsetDateTime.parse(item.getString("reading_timestamp"))
                            )
                        }

                        activity?.runOnUiThread {
                            if (isAdded) redraw()
                        }
                    } catch (e: Exception) {
                        // Handle JSON parsing errors
                    }
                }
            }
        })
    }

    /* =============================================================== */
    /* FIXED MULTI-RANGE RIGHT AXIS FORMATTER */
    /* =============================================================== */
    inner class MultiRangeRightAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (rightSensorRanges.isEmpty()) return ""

            // Ensure we handle the exact min/max values correctly
            val normalizedValue = when {
                value <= 0f -> 0f  // Bottom of axis = minimum values
                value >= 1f -> 1f  // Top of axis = maximum values
                else -> value
            }

            val results = mutableListOf<String>()
            rightSensorRanges.forEach { sensorRange ->
                val actualValue = when (normalizedValue) {
                    0f -> sensorRange.min  // Ensure exact minimum is shown
                    1f -> sensorRange.max  // Ensure exact maximum is shown
                    else -> sensorRange.min + (sensorRange.max - sensorRange.min) * normalizedValue
                }
                results.add("${actualValue.roundToInt()}${sensorRange.unit}")
            }
            return results.joinToString(" | ")
        }
    }

    /* =============================================================== */
    /* NORMALIZE VALUE TO 0-1 RANGE FOR SENSOR */
    /* =============================================================== */
    private fun normalizeValue(value: Float, min: Float, max: Float): Float {
        if (max == min) return 0.5f // Avoid division by zero
        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }

    /* =============================================================== */
    /* MAIN DRAW function with individual sensor ranges */
    /* =============================================================== */
    private fun redraw() {
        if (dataList.isEmpty()) return

        /* ---- read toggles ---- */
        val hideNight = hideNightBox.isChecked
        val hideSep = hideSepBox.isChecked
        val viewMode = when {
            range24hRadio.isChecked -> 1
            rangeLastDipRadio.isChecked -> 2
            else -> 0
        }
        val bridge = bridgeBox.isChecked

        /* ---- helpers ---- */
        val now = OffsetDateTime.now()
        val cutoff = when (viewMode) {
            1 -> now.minusHours(24)
            2 -> now.minusDays(7)
            else -> null
        }

        val tFmt = DateTimeFormatter.ofPattern("HH:mm")
        val dFmt = DateTimeFormatter.ofPattern("dd MMM")
        val xAxis = chart.xAxis
        val leftAxis = chart.axisLeft
        val rightAxis = chart.axisRight

        xAxis.removeAllLimitLines()
        leftAxis.removeAllLimitLines()
        rightAxis.removeAllLimitLines()
        predictionTxt.text = ""

        /* ============================================================ */
        /* STEP 1: Create time-aligned data and calculate ranges */
        /* ============================================================ */
        val filteredReadings = dataList.filter { reading ->
            val ts = reading.timestamp
            if (cutoff != null && ts.isBefore(cutoff)) return@filter false
            if (hideNight && ts.hour < 6) return@filter false
            true
        }.sortedBy { it.timestamp }

        if (filteredReadings.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        // Create time-aligned data structure
        val timeSlots = mutableMapOf<OffsetDateTime, MutableMap<Int, Float>>()

        filteredReadings.forEach { reading ->
            val ts = if (bridge) {
                reading.timestamp.withMinute(reading.timestamp.minute / 10 * 10).withSecond(0).withNano(0)
            } else {
                reading.timestamp
            }

            if (!timeSlots.containsKey(ts)) {
                timeSlots[ts] = mutableMapOf()
            }

            selectedSensors.forEach { sensorIdx ->
                val value = when (sensorTypes[sensorIdx]) {
                    "temperature" -> reading.temperature?.toFloat()
                    "humidity" -> reading.humidity?.toFloat()
                    "co2" -> reading.co2?.toFloat()
                    "waterlevel" -> reading.waterlevel?.toFloat()
                    else -> null
                }
                value?.let { timeSlots[ts]!![sensorIdx] = it }
            }
        }

        val sortedTimeSlots = timeSlots.keys.sorted()
        if (sortedTimeSlots.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        /* ============================================================ */
        /* STEP 2: Calculate individual ranges for each right sensor */
        /* ============================================================ */
        rightSensorRanges.clear()
        val rightSensors = selectedSensors.filter { it != 0 }.sorted()
        val sensorValues = mutableMapOf<Int, MutableList<Float>>()

        // Collect all values for each sensor to calculate individual ranges
        selectedSensors.forEach { sensorIdx ->
            sensorValues[sensorIdx] = mutableListOf()
        }

        sortedTimeSlots.forEach { ts ->
            timeSlots[ts]?.forEach { (sensorIdx, value) ->
                sensorValues[sensorIdx]?.add(value)
            }
        }

        // Calculate individual ranges
        rightSensors.forEach { sensorIdx ->
            val values = sensorValues[sensorIdx] ?: return@forEach
            if (values.isNotEmpty()) {
                val min = values.minOrNull() ?: 0f
                val max = values.maxOrNull() ?: 100f
                rightSensorRanges.add(
                    SensorRange(
                        sensorIdx = sensorIdx,
                        min = min,
                        max = max,
                        label = sensorLabels[sensorIdx],
                        unit = sensorUnits[sensorIdx],
                        color = colours[sensorIdx]
                    )
                )
            }
        }

        /* ============================================================ */
        /* STEP 3: Create datasets with normalized right-axis values */
        /* ============================================================ */
        val dataSets = mutableListOf<ILineDataSet>()
        val labels = mutableListOf<String>()

        // Build labels from time slots
        sortedTimeSlots.forEachIndexed { index, ts ->
            labels.add(ts.format(tFmt))

            if (!hideSep && index > 0) {
                val prevDay = sortedTimeSlots[index - 1].toLocalDate()
                val currentDay = ts.toLocalDate()
                if (currentDay != prevDay) {
                    xAxis.addLimitLine(LimitLine(index.toFloat(), currentDay.format(dFmt)))
                }
            }
        }

        selectedSensors.forEach { sensorIdx ->
            val sensorType = sensorTypes[sensorIdx]
            val sensorLabel = sensorLabels[sensorIdx]
            val sensorUnit = sensorUnits[sensorIdx]
            val sensorColor = colours[sensorIdx]

            val entries = mutableListOf<Entry>()

            // Find the range for this sensor (if it's a right sensor)
            val sensorRange = rightSensorRanges.find { it.sensorIdx == sensorIdx }

            // Create entries for this sensor
            sortedTimeSlots.forEachIndexed { index, ts ->
                timeSlots[ts]?.get(sensorIdx)?.let { value ->
                    val yValue = if (sensorIdx == 0 || sensorRange == null) {
                        // Left axis (waterlevel) - use actual values
                        value
                    } else {
                        // Right axis - normalize to 0-1 range
                        normalizeValue(value, sensorRange.min, sensorRange.max)
                    }
                    entries.add(Entry(index.toFloat(), yValue))
                }
            }

            if (entries.isNotEmpty()) {
                val lineDataSet = LineDataSet(entries, "$sensorLabel ($sensorUnit)").apply {
                    lineWidth = 2f
                    setDrawCircles(entries.size <= 50)
                    circleRadius = 3f
                    setDrawValues(false)
                    color = sensorColor
                    setCircleColor(sensorColor)
                    axisDependency = if (sensorIdx == 0) YAxis.AxisDependency.LEFT else YAxis.AxisDependency.RIGHT
                }
                dataSets.add(lineDataSet)
            }
        }

        if (dataSets.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        /* ============================================================ */
        /* STEP 4: Configure axes */
        /* ============================================================ */
        leftAxis.isEnabled = true
        leftAxis.setDrawLabels(true)
        leftAxis.setDrawAxisLine(true)
        leftAxis.setDrawGridLines(true)
        leftAxis.textColor = colours[0] // Waterlevel color (blue)

        // Add waterlevel threshold lines
        leftAxis.addLimitLine(LimitLine(20f, "Low").apply {
            lineColor = android.graphics.Color.RED
        })
        leftAxis.addLimitLine(LimitLine(80f, "High").apply {
            lineColor = android.graphics.Color.GREEN
        })

        // Configure right axis
        rightAxis.isEnabled = rightSensorRanges.isNotEmpty()

        if (rightAxis.isEnabled) {
            rightAxis.axisMinimum = 0f
            rightAxis.axisMaximum = 1f
            rightAxis.setDrawLabels(true)
            rightAxis.setDrawAxisLine(true)
            rightAxis.setDrawGridLines(false)
            rightAxis.valueFormatter = MultiRangeRightAxisFormatter()
            rightAxis.textColor = android.graphics.Color.BLACK
            rightAxis.textSize = 10f

            // Force exact labels to include min and max
            rightAxis.setLabelCount(6, true) // Force exactly 6 labels
            rightAxis.granularity = 0.2f // 0, 0.2, 0.4, 0.6, 0.8, 1.0

            // Add extra offset to prevent label cutoff
            chart.extraRightOffset = 15f
            chart.extraTopOffset = 10f

            // Update prediction text with individual sensor ranges
            val sensorInfo = rightSensorRanges.map { range ->
                "${range.label}: ${range.min.roundToInt()}-${range.max.roundToInt()}${range.unit}"
            }
            predictionTxt.text = sensorInfo.joinToString(" | ")
        } else {
            predictionTxt.text = ""
            chart.extraRightOffset = 0f
            chart.extraTopOffset = 0f
        }

        /* ============================================================ */
        /* STEP 5: Apply data to chart */
        /* ============================================================ */
        chart.legend.isEnabled = true
        chart.legend.form = Legend.LegendForm.LINE
        chart.legend.textSize = 10f
        chart.data = LineData(dataSets)
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)

        finishChart()
    }

    /* =========================================================== */
    /* misc */
    /* =========================================================== */
    private fun finishChart() {
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.description.isEnabled = false
        chart.animateX(600)
        chart.invalidate()
    }

    /** Utility to convert dp → pixels */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }
}
