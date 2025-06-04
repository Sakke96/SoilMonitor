package com.example.soilmonitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
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
import kotlin.math.ceil
import kotlin.math.roundToLong

class SurroundingFragment : Fragment() {

    /* ---------- UI ---------- */
    private lateinit var surroundingChart: LineChart
    private lateinit var switchTemp: SwitchMaterial
    private lateinit var switchHumidity: SwitchMaterial
    private lateinit var switchCO2: SwitchMaterial
    private lateinit var switchPH: SwitchMaterial
    private lateinit var switchPPM: SwitchMaterial
    private lateinit var switchTC: SwitchMaterial

    // check-boxes
    private lateinit var hideNightCheckBox: MaterialCheckBox
    private lateinit var hideSeparatorCheckBox: MaterialCheckBox
    private lateinit var last24hCheckBox: MaterialCheckBox
    private lateinit var bridgeGapsCheckBox: MaterialCheckBox

    /* ---------- data ---------- */
    private var dataList: List<JSONObject> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchSurroundingData()
            handler.postDelayed(this, 60_000L)
        }
    }

    /* ---------- meta ---------- */
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

    /* ---------- lifecycle ---------- */

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_surrounding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /* ---- bind views ---- */
        surroundingChart = view.findViewById(R.id.surroundingChart)

        switchTemp     = view.findViewById(R.id.switchTemp)
        switchHumidity = view.findViewById(R.id.switchHumidity)
        switchCO2      = view.findViewById(R.id.switchCO2)
        switchPH       = view.findViewById(R.id.switchPH)
        switchPPM      = view.findViewById(R.id.switchPPM)
        switchTC       = view.findViewById(R.id.switchTC)

        hideNightCheckBox     = view.findViewById(R.id.checkHideNight)
        hideSeparatorCheckBox = view.findViewById(R.id.checkHideSeparator)
        last24hCheckBox       = view.findViewById(R.id.checkLast24h)
        bridgeGapsCheckBox    = view.findViewById(R.id.checkBridgeGaps)

        /* ---- auto‐enable temperature by default ---- */
        switchTemp.isChecked = true

        /* ---- listeners ---- */
        val rerender: (View, Boolean) -> Unit = { _, _ -> updateChart() }
        listOf(
            switchTemp, switchHumidity, switchCO2, switchPH, switchPPM, switchTC,
            hideNightCheckBox, hideSeparatorCheckBox, last24hCheckBox, bridgeGapsCheckBox
        ).forEach { v ->
            when (v) {
                is SwitchMaterial   -> v.setOnCheckedChangeListener(rerender)
                is MaterialCheckBox -> v.setOnCheckedChangeListener(rerender)
            }
        }

        /* ---- immediately render once (temperature on) ---- */
        updateChart()

        /* ---- start polling ---- */
        handler.post(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    /* ---------- network ---------- */

    private fun fetchSurroundingData() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* ignore */ }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val jArr = JSONObject(body).getJSONArray("items")
                    dataList = List(jArr.length()) { i -> jArr.getJSONObject(i) }
                    activity?.runOnUiThread { if (isAdded) updateChart() }
                }
            }
        })
    }

    /* ---------- chart ---------- */

    private fun updateChart() {
        if (dataList.isEmpty()) return

        /* ---- read options ---- */
        val hideNight      = hideNightCheckBox.isChecked
        val hideSeparators = hideSeparatorCheckBox.isChecked
        val last24hOnly    = last24hCheckBox.isChecked
        val bridgeGaps     = bridgeGapsCheckBox.isChecked

        val now    = OffsetDateTime.now().plusHours(2)
        val cutoff = now.minusHours(24)

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val dateFmt = DateTimeFormatter.ofPattern("dd MMM")

        /* ---- clear old extras ---- */
        val xAxis = surroundingChart.xAxis
        xAxis.removeAllLimitLines()
        surroundingChart.axisLeft.removeAllLimitLines()

        /* ---- 1. collect readings per sensor with coarse filters ---- */
        val readingsPerSensor =
            sensorKeys.associateWith { mutableListOf<Pair<OffsetDateTime, Float>>() }

        dataList.forEach { item ->
            val ts = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
            if (last24hOnly && ts.isBefore(cutoff)) return@forEach
            if (hideNight && ts.hour < 6)          return@forEach

            sensorKeys.forEach { key ->
                val v = item.optDouble(key, Double.NaN)
                if (!v.isNaN()) readingsPerSensor[key]?.add(ts to v.toFloat())
            }
        }

        /* ---- 2. determine active sensors ---- */
        val sensorToggles = listOf(
            switchTemp.isChecked, switchHumidity.isChecked, switchCO2.isChecked,
            switchPH.isChecked, switchPPM.isChecked, switchTC.isChecked
        )

        /* ---- 3. build x-axis labels & slot list ---- */
        val labels = mutableListOf<String>()
        val slotList = mutableListOf<OffsetDateTime>()
        var lastDateSeen: java.time.LocalDate? = null

        if (bridgeGaps) {
            /* -- find first & last timestamp among ALL readings -- */
            val allTs = readingsPerSensor.values.flatten().map { it.first }
            if (allTs.isEmpty()) return
            var slot = allTs.minOrNull()!!
                .withMinute((allTs.minOrNull()!!.minute / 10) * 10)
                .withSecond(0).withNano(0)
            val lastSlot = allTs.maxOrNull()!!
                .withMinute((allTs.maxOrNull()!!.minute / 10) * 10)
                .withSecond(0).withNano(0)

            var pos = 0
            while (!slot.isAfter(lastSlot)) {
                val useSlot = !(hideNight && slot.hour < 6) &&
                        !(last24hOnly && slot.isBefore(cutoff))

                if (useSlot) {
                    if (!hideSeparators && slot.toLocalDate() != lastDateSeen) {
                        xAxis.addLimitLine(
                            LimitLine(
                                pos.toFloat(),
                                slot.toLocalDate().format(dateFmt)
                            )
                        )
                        lastDateSeen = slot.toLocalDate()
                    }
                    labels += slot.format(timeFmt)
                    slotList += slot
                    pos++
                }
                slot = slot.plusMinutes(10)
            }
        } else {
            /* original unequal spacing */
            var pos = 0
            dataList.forEach { item ->
                val ts = OffsetDateTime.parse(item.getString("created_at")).plusHours(2)
                if (last24hOnly && ts.isBefore(cutoff)) return@forEach
                if (hideNight && ts.hour < 6)          return@forEach

                if (!hideSeparators && ts.toLocalDate() != lastDateSeen) {
                    xAxis.addLimitLine(
                        LimitLine(
                            pos.toFloat(),
                            ts.toLocalDate().format(dateFmt)
                        )
                    )
                    lastDateSeen = ts.toLocalDate()
                }
                labels += ts.format(timeFmt)
                slotList += ts                     // keep real timestamps for mapping
                pos++
            }
        }

        /* ---- 4. build datasets ---- */
        val dataSets = mutableListOf<ILineDataSet>()
        var activeCount = 0              // track visible series
        var leftAxisColor   = android.graphics.Color.DKGRAY
        var rightAxisColor  = android.graphics.Color.DKGRAY

        sensorKeys.forEachIndexed { idx, key ->
            if (!sensorToggles[idx]) return@forEachIndexed

            val entries = mutableListOf<Entry>()
            val slotToVal = mutableMapOf<OffsetDateTime, Float>()
            readingsPerSensor[key]?.forEach { (ts, v) ->
                val slot = if (bridgeGaps) {
                    ts.withMinute((ts.minute / 10) * 10).withSecond(0).withNano(0)
                } else ts
                slotToVal[slot] = v                       // keep latest per slot
            }

            var prevVal: Float? = null
            var haveFirst = false

            slotList.forEachIndexed { pos, slot ->
                val v = slotToVal[slot] ?: if (bridgeGaps && haveFirst) prevVal else null
                if (v != null) {
                    entries += Entry(pos.toFloat(), v)
                    prevVal = v
                    haveFirst = true
                }
            }

            if (entries.isNotEmpty()) {
                val color = sensorColors.getOrElse(idx) { android.graphics.Color.BLACK }

                dataSets += LineDataSet(entries, sensorLabels[idx]).apply {
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    this.color = color

                    // decide which axis to use
                    axisDependency = if (activeCount == 1)
                        YAxis.AxisDependency.RIGHT   // 2nd visible series
                    else
                        YAxis.AxisDependency.LEFT    // 1st + 3rd+
                }

                // remember colours for axes
                if (activeCount == 0)  leftAxisColor  = color
                if (activeCount == 1)  rightAxisColor = color

                activeCount++
            }
        }

        /* ---- 5. draw ---- */
        surroundingChart.data = LineData(dataSets)
        surroundingChart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
        }

        // colour the axes to match their series
        surroundingChart.axisLeft.apply {
            textColor = leftAxisColor
            axisLineColor = leftAxisColor
            setDrawGridLines(true)
        }

        surroundingChart.axisRight.apply {
            isEnabled = activeCount >= 2
            textColor = rightAxisColor
            axisLineColor = rightAxisColor
            setDrawGridLines(false)
        }

        surroundingChart.setTouchEnabled(true)
        surroundingChart.setPinchZoom(true)
        surroundingChart.description.isEnabled = false
        surroundingChart.animateX(800)
        surroundingChart.invalidate()
    }
}
