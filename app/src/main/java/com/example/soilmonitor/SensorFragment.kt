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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class SensorFragment : Fragment() {

    /* ---------- UI ---------- */
    private lateinit var chart: LineChart
    private lateinit var sensorLayout: LinearLayout         // container for equally‐weighted Buttons
    private lateinit var hideNightBox: CheckBox
    private lateinit var hideSepBox: CheckBox
    private lateinit var rangeGroup: RadioGroup
    private lateinit var range24hRadio: RadioButton
    private lateinit var rangeLastDipRadio: RadioButton
    private lateinit var rangeAllRadio: RadioButton
    private lateinit var bridgeBox: CheckBox
    private lateinit var trendBox: CheckBox
    private lateinit var predictionTxt: TextView

    /* ---------- prefs / raw data ---------- */
    private lateinit var prefs: SharedPreferences
    private var dataList: List<JSONObject> = emptyList()

    /* ---------- static meta ---------- */
    private lateinit var sensorKeys: List<String>   // e.g. "sensor_u0" … "sensor_uN"
    private lateinit var sensorLabels: List<String> // "All", "1", "2", …
    private lateinit var dryVals: List<Float>
    private lateinit var wetVals: List<Float>

    /** Fixed palette: Plant 1–4 = red, blue, green, magenta  */
    private val colours = listOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.MAGENTA
    )

    /* ---------- selection state ---------- */
    private var selectedSensorIndex = 0   // 0 = "All", 1 = "1", etc.
    private val sensorButtons = mutableListOf<Button>()

    /* ---------- polling ---------- */
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            fetch()
            handler.postDelayed(this, 60_000L)
        }
    }

    /* ==================== */
    /*  Companion factory   */
    /* ==================== */
    companion object {
        private const val ARG_PLANT_INDEX = "argPlantIndex"
        private const val ARG_HIDE_NIGHT   = "argHideNight"
        private const val ARG_HIDE_SEP     = "argHideSep"
        private const val ARG_VIEW_MODE    = "argViewMode"
        private const val ARG_BRIDGE       = "argBridge"
        private const val ARG_SHOW_TREND   = "argShowTrend"

        const val MODE_ALL = 0
        const val MODE_24H = 1
        const val MODE_LAST_DIP = 2

        /**
         * Create a SensorFragment pre-configured to display exactly this plant,
         * with all toggles set as indicated.
         *
         * @param plantIndex  zero-based index (0 → Plant 1, 1 → Plant 2, etc.)
         * @param hideNight   true = hide 00:00–06:00
         * @param hideSep     true = hide day separators
         * @param viewMode    MODE_* constant for time range
         * @param bridge      true = bridge missing 10-min gaps
         * @param showTrend   true = show the trend line
         */
        @JvmStatic
        fun newInstance(
            plantIndex: Int,
            hideNight: Boolean,
            hideSep: Boolean,
            viewMode: Int,
            bridge: Boolean,
            showTrend: Boolean
        ): SensorFragment {
            val frag = SensorFragment()
            val args = Bundle()
            args.putInt(ARG_PLANT_INDEX, plantIndex)
            args.putBoolean(ARG_HIDE_NIGHT, hideNight)
            args.putBoolean(ARG_HIDE_SEP, hideSep)
            args.putInt(ARG_VIEW_MODE, viewMode)
            args.putBoolean(ARG_BRIDGE, bridge)
            args.putBoolean(ARG_SHOW_TREND, showTrend)
            frag.arguments = args
            return frag
        }
    }

    /* ==================== */
    /*  Lifecycle           */
    /* ==================== */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_sensor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /* ---- bind views ---- */
        prefs         = PreferenceManager.getDefaultSharedPreferences(requireContext())
        chart         = view.findViewById(R.id.lineChart)
        sensorLayout  = view.findViewById(R.id.sensorLayout)      // NEW: equal‐width button container
        hideNightBox  = view.findViewById(R.id.hideNightCheckBox)
        hideSepBox    = view.findViewById(R.id.hideSeparatorCheckBox)
        rangeGroup    = view.findViewById(R.id.rangeRadioGroup)
        range24hRadio = view.findViewById(R.id.range24hRadio)
        rangeLastDipRadio = view.findViewById(R.id.rangeLastDipRadio)
        rangeAllRadio = view.findViewById(R.id.rangeAllRadio)
        bridgeBox     = view.findViewById(R.id.bridgeGapsCheckBox)
        trendBox      = view.findViewById(R.id.trendLineCheckBox)
        predictionTxt = view.findViewById(R.id.trendPredictionText)

        /* ---- sensor meta from prefs ---- */
        val plants = prefs.getInt("plantCount", 4)
        sensorKeys   = List(plants) { i -> "sensor_u${i}" }
        // Display simple numeric labels instead of "Plant N" for the selector
        sensorLabels = listOf("All") + List(plants) { i -> "${i + 1}" }
        dryVals      = List(plants) { i -> prefs.getFloat("plant_${i + 1}_dry", 400f) }
        wetVals      = List(plants) { i -> prefs.getFloat("plant_${i + 1}_wet", 350f) }

        /* ---- dynamically create one equally‐weighted Button per label ---- */
        sensorLayout.removeAllViews()
        sensorButtons.clear()

        // Convert 48dp to pixels for fixed button height
        val buttonHeightPx = dpToPx(48)

        sensorLabels.forEachIndexed { idx, label ->
            val btn = Button(requireContext()).apply {
                text = label
                tag = idx
                isAllCaps = false

                // FORCE single line (no wrapping). If the text is too long, it will be ellipsized.
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END

                // FIXED HEIGHT = 48dp; width = 0dp + weight = 1 so all 5 share space equally
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    buttonHeightPx,
                    1f
                ).apply {
                    // small horizontal margin between buttons
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }

                // Center text vertically & horizontally
                gravity = Gravity.CENTER

                // Background based on whether this is the initially selected index
                setBackgroundResource(
                    if (idx == selectedSensorIndex)
                        R.drawable.sensor_button_selected
                    else
                        R.drawable.sensor_button_unselected
                )

                setOnClickListener {
                    val clickedIndex = it.tag as Int
                    if (clickedIndex == selectedSensorIndex) return@setOnClickListener

                    // Un‐select the previously selected button
                    sensorButtons[selectedSensorIndex].setBackgroundResource(
                        R.drawable.sensor_button_unselected
                    )
                    // Mark this one as selected
                    selectedSensorIndex = clickedIndex
                    this.setBackgroundResource(R.drawable.sensor_button_selected)

                    redraw()
                }
            }
            sensorButtons.add(btn)
            sensorLayout.addView(btn)
        }

        /* —— IF fragment arguments exist, pre‐select as requested —— */
        arguments?.let { args ->
            val plantIndex = args.getInt(ARG_PLANT_INDEX, 0).coerceIn(0, plants - 1)
            val desiredIdx = plantIndex + 1
            // swap selected backgrounds
            sensorButtons[selectedSensorIndex].setBackgroundResource(
                R.drawable.sensor_button_unselected
            )
            sensorButtons[desiredIdx].setBackgroundResource(
                R.drawable.sensor_button_selected
            )
            selectedSensorIndex = desiredIdx

            hideNightBox.isChecked  = args.getBoolean(ARG_HIDE_NIGHT, false)
            hideSepBox.isChecked    = args.getBoolean(ARG_HIDE_SEP, false)
            when (args.getInt(ARG_VIEW_MODE, MODE_ALL)) {
                MODE_24H -> range24hRadio.isChecked = true
                MODE_LAST_DIP -> rangeLastDipRadio.isChecked = true
                else -> rangeAllRadio.isChecked = true
            }
            bridgeBox.isChecked     = args.getBoolean(ARG_BRIDGE, false)
            trendBox.isChecked      = args.getBoolean(ARG_SHOW_TREND, false)
        }

        /* ---- toggles: on change → redraw() ---- */
        val listener = CompoundButton.OnCheckedChangeListener { _, _ -> redraw() }
        hideNightBox.setOnCheckedChangeListener(listener)
        hideSepBox.setOnCheckedChangeListener(listener)
        rangeGroup.setOnCheckedChangeListener { _, _ -> redraw() }
        bridgeBox.setOnCheckedChangeListener(listener)
        trendBox.setOnCheckedChangeListener(listener)

        /* initial fetch + start polling */
        fetch()
        handler.post(refresher)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refresher)
    }

    /* ============================ */
    /*  Network: fetch entire data  */
    /* ============================ */
    private fun fetch() {
        OkHttpClient().newCall(
            Request.Builder()
                .url("https://g2f12813f9dfc61-garden.adb.eu-paris-1.oraclecloudapps.com/ords/admin/log/log")
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* ignore */ }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let {
                    val arr = JSONObject(it).getJSONArray("items")
                    dataList = List(arr.length()) { i -> arr.getJSONObject(i) }
                    activity?.runOnUiThread {
                        if (isAdded) redraw()
                    }
                }
            }
        })
    }

    /* =============================================================== */
    /*  MAIN DRAW function                                              */
    /*  (same logic as before, except using selectedSensorIndex…)       */
    /* =============================================================== */
    private fun redraw() {
        if (dataList.isEmpty()) return

        /* ---- read toggles ---- */
        val hideNight   = hideNightBox.isChecked
        val hideSep     = hideSepBox.isChecked
        val viewMode = when {
            range24hRadio.isChecked -> MODE_24H
            rangeLastDipRadio.isChecked -> MODE_LAST_DIP
            else -> MODE_ALL
        }
        val bridge      = bridgeBox.isChecked
        // only show trend if a single plant is chosen (i.e. selectedSensorIndex ≠ 0)
        val showTrend   = trendBox.isChecked && selectedSensorIndex != 0

        /* ---- helpers ---- */
        val now    = OffsetDateTime.now().plusHours(2)
        val cutoff = when (viewMode) {
            MODE_24H -> now.minusHours(24)
            MODE_LAST_DIP -> computeLastDipCutoff()
            else -> null
        }
        val tFmt   = DateTimeFormatter.ofPattern("HH:mm")
        val dFmt   = DateTimeFormatter.ofPattern("dd MMM")

        val xAxis = chart.xAxis
        val yAxis = chart.axisLeft
        xAxis.removeAllLimitLines()
        yAxis.removeAllLimitLines()
        predictionTxt.text = ""

        /* ============================================================ */
        /*  A) “All Sensors” (index 0)                                  */
        /* ============================================================ */
        if (selectedSensorIndex == 0) {
            chart.fitScreen()
            chart.setAutoScaleMinMaxEnabled(true)
            yAxis.resetAxisMinimum()
            yAxis.resetAxisMaximum()

            /* gather per-sensor time/value pairs */
            val perSensor = sensorKeys.associateWith { mutableListOf<Pair<OffsetDateTime, Float>>() }
            dataList.forEach { obj ->
                val ts = OffsetDateTime.parse(obj.getString("created_at")).plusHours(2)
                if (cutoff != null && ts.isBefore(cutoff)) return@forEach
                if (hideNight && ts.hour < 6) return@forEach
                sensorKeys.forEach { k ->
                    val v = obj.optInt(k, -1)
                    if (v >= 0) perSensor[k]?.add(ts to v.toFloat())
                }
            }

            if (!bridge) {
                /* no bridging → irregular spacing */
                val labels = mutableListOf<String>()
                val rawIdx = mutableListOf<Int>()
                var lastDay: java.time.LocalDate? = null

                dataList.forEachIndexed { i, obj ->
                    val ts = OffsetDateTime.parse(obj.getString("created_at")).plusHours(2)
                    if (cutoff != null && ts.isBefore(cutoff)) return@forEachIndexed
                    if (hideNight && ts.hour < 6) return@forEachIndexed

                    if (!hideSep && ts.toLocalDate() != lastDay) {
                        xAxis.addLimitLine(
                            LimitLine(
                                labels.size.toFloat(),
                                ts.toLocalDate().format(dFmt)
                            )
                        )
                        lastDay = ts.toLocalDate()
                    }
                    labels += ts.format(tFmt)
                    rawIdx += i
                }

                val sets = mutableListOf<ILineDataSet>()
                sensorKeys.forEachIndexed { idx, key ->
                    val es = rawIdx.mapIndexedNotNull { pos, r ->
                        dataList[r].optInt(key, -1)
                            .takeIf { it >= 0 }
                            ?.let { Entry(pos.toFloat(), it.toFloat()) }
                    }
                    if (es.isNotEmpty()) {
                        sets += LineDataSet(es, sensorLabels[idx + 1]).apply {
                            lineWidth = 2f
                            setDrawCircles(false)
                            setDrawValues(false)
                            color = colours.getOrElse(idx) { android.graphics.Color.BLACK }
                        }
                    }
                }

                chart.legend.apply { isEnabled = true; form = Legend.LegendForm.LINE }
                chart.data = LineData(sets)
                xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                finishChart()
                return
            }

            /* ============================================================ */
            /*  bridging ON → 10-min raster, only real points              */
            /* ============================================================ */
            val slotsAll = perSensor.values.flatten()
                .map {
                    it.first
                        .withMinute(it.first.minute / 10 * 10)
                        .withSecond(0)
                        .withNano(0)
                }
            if (slotsAll.isEmpty()) return

            var slot = slotsAll.minOrNull()!!
            val lastSlot = slotsAll.maxOrNull()!!
            val slotList = mutableListOf<OffsetDateTime>()
            var pos = 0
            var lastDay: java.time.LocalDate? = null

            while (!slot.isAfter(lastSlot)) {
                val keep = !(hideNight && slot.hour < 6) &&
                        !(cutoff != null && slot.isBefore(cutoff))
                if (keep) {
                    if (!hideSep && slot.toLocalDate() != lastDay) {
                        xAxis.addLimitLine(
                            LimitLine(
                                pos.toFloat(),
                                slot.toLocalDate().format(dFmt)
                            )
                        )
                        lastDay = slot.toLocalDate()
                    }
                    slotList += slot
                    pos++
                }
                slot = slot.plusMinutes(10)
            }

            val dataSets = mutableListOf<ILineDataSet>()
            sensorKeys.forEachIndexed { idx, key ->
                val map = perSensor[key]!!.associateBy(
                    { it.first.withMinute(it.first.minute / 10 * 10).withSecond(0).withNano(0) },
                    { it.second }
                )
                val es = mutableListOf<Entry>()
                slotList.forEachIndexed { p, s ->
                    map[s]?.let { es += Entry(p.toFloat(), it) }
                }
                if (es.isNotEmpty()) {
                    dataSets += LineDataSet(es, sensorLabels[idx + 1]).apply {
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        color = colours.getOrElse(idx) { android.graphics.Color.BLACK }
                    }
                }
            }

            chart.legend.apply { isEnabled = true; form = Legend.LegendForm.LINE }
            chart.data = LineData(dataSets)
            xAxis.valueFormatter = IndexAxisValueFormatter(slotList.map { it.format(tFmt) })
            finishChart()
            return
        }

        /* ============================================================ */
        /*  B) SINGLE PLANT (index ≥ 1)                                 */
        /* ============================================================ */
        val idx = selectedSensorIndex - 1
        val key = sensorKeys[idx]
        val wet = wetVals[idx]
        val dry = dryVals[idx]

        val raw = dataList.mapNotNull { obj ->
            val ts = OffsetDateTime.parse(obj.getString("created_at")).plusHours(2)
            if (cutoff != null && ts.isBefore(cutoff)) return@mapNotNull null
            if (hideNight && ts.hour < 6) return@mapNotNull null
            val v = obj.optInt(key, -1).takeIf { it >= 0 } ?: return@mapNotNull null
            ts to v.toFloat()
        }.sortedBy { it.first }
        if (raw.isEmpty()) return

        val labels = mutableListOf<String>()
        val entries = mutableListOf<Entry>()
        var lastDay: java.time.LocalDate? = null

        if (bridge) {
            val slotMap = mutableMapOf<OffsetDateTime, Float>()
            raw.forEach { (ts, v) ->
                val s = ts.withMinute(ts.minute / 10 * 10).withSecond(0).withNano(0)
                slotMap[s] = v
            }
            var slot = slotMap.keys.minOrNull()!!
            val last = slotMap.keys.maxOrNull()!!
            var pos = 0
            while (!slot.isAfter(last)) {
                val keep = !(hideNight && slot.hour < 6) &&
                        !(cutoff != null && slot.isBefore(cutoff))
                if (keep) {
                    if (!hideSep && slot.toLocalDate() != lastDay) {
                        xAxis.addLimitLine(
                            LimitLine(
                                pos.toFloat(),
                                slot.toLocalDate().format(dFmt)
                            )
                        )
                        lastDay = slot.toLocalDate()
                    }
                    labels += slot.format(tFmt)
                    slotMap[slot]?.let { entries += Entry(pos.toFloat(), it) }
                    pos++
                }
                slot = slot.plusMinutes(10)
            }
        } else {
            var pos = 0
            raw.forEach { (ts, v) ->
                if (!hideSep && ts.toLocalDate() != lastDay) {
                    xAxis.addLimitLine(
                        LimitLine(
                            pos.toFloat(),
                            ts.toLocalDate().format(dFmt)
                        )
                    )
                    lastDay = ts.toLocalDate()
                }
                labels += ts.format(tFmt)
                entries += Entry(pos.toFloat(), v)
                pos++
            }
        }

        val dataSets = mutableListOf<ILineDataSet>()
        dataSets += LineDataSet(entries, sensorLabels[idx + 1]).apply {
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            color = colours.getOrElse(idx) { android.graphics.Color.BLACK }
        }

        /* wet / dry bands */
        chart.axisLeft.addLimitLine(LimitLine(dry, "Dry"))
        chart.axisLeft.addLimitLine(LimitLine(wet, "Wet"))
        val span = max(wet, dry) - min(wet, dry)
        chart.axisLeft.axisMinimum = min(entries.minOf { it.y }, min(wet, dry) - span)
        chart.axisLeft.axisMaximum = max(entries.maxOf { it.y }, max(wet, dry) + span)

        /* Trend‐to‐dry line */
        if (trendBox.isChecked && selectedSensorIndex != 0 && entries.size >= 2) {
            val stableCount = 3
            var sIdx = entries.indexOfFirst { it.y <= wet }.let { if (it == -1) 0 else it }
            for (i in 1 until entries.size - stableCount) {
                if (entries[i - 1].y > wet &&
                    (0 until stableCount).all { j -> entries[i + j].y <= wet }) {
                    sIdx = i
                    break
                }
            }
            val start = entries[sIdx]
            val end = entries.last()
            val dx = end.x - start.x
            val dy = end.y - start.y
            val slope = if (dx != 0f) dy / dx else 0f
            if (slope > 0 && end.y < dry) {
                val slotsToDry = (dry - end.y) / slope
                val predX = end.x + slotsToDry
                dataSets += LineDataSet(
                    listOf(Entry(start.x, start.y), Entry(predX, dry)), "Trend"
                ).apply {
                    lineWidth = 1.5f
                    setDrawCircles(false)
                    setDrawValues(false)
                    enableDashedLine(10f, 5f, 0f)
                    color = android.graphics.Color.GRAY
                }
                repeat(ceil(predX - (labels.size - 1)).toInt()) { labels += "" }
                val mins = (slotsToDry * 10).roundToLong()
                val predicted = raw.last().first.plusMinutes(mins)
                predictionTxt.text =
                    "Expected dry hit: ${predicted.format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))}"
            }
        }

        chart.legend.isEnabled = false
        chart.data = LineData(dataSets)
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        finishChart()
    }

    /**
     * Determine the timestamp of the lowest point of the most recent dip below
     * the wet threshold within the last five days. The dip is considered to
     * start once values drop below `wet` for several consecutive readings and
     * the returned timestamp corresponds to the minimum of that dip.
     */
    private fun computeLastDipCutoff(): OffsetDateTime {
        val now = OffsetDateTime.now().plusHours(2)
        val lookback = now.minusDays(5)
        val stable = 3

        fun dipFor(entries: List<Pair<OffsetDateTime, Float>>, wet: Float): OffsetDateTime? {
            if (entries.size <= stable) return null
            for (i in entries.size - stable downTo 1) {
                val before = entries[i - 1].second
                if (before > wet && (0 until stable).all { j -> entries[i + j].second <= wet }) {
                    var minIdx = i
                    var minVal = entries[i].second
                    var k = i
                    while (k < entries.size && entries[k].second <= wet) {
                        val v = entries[k].second
                        if (v <= minVal) { minVal = v; minIdx = k }
                        k++
                    }
                    return entries[minIdx].first
                }
            }
            return null
        }

        if (selectedSensorIndex == 0) {
            var latest = lookback
            sensorKeys.forEachIndexed { idx, key ->
                val entries = dataList.mapNotNull { obj ->
                    val ts = OffsetDateTime.parse(obj.getString("created_at")).plusHours(2)
                    if (ts.isBefore(lookback)) return@mapNotNull null
                    val v = obj.optInt(key, -1).takeIf { it >= 0 } ?: return@mapNotNull null
                    ts to v.toFloat()
                }.sortedBy { it.first }
                dipFor(entries, wetVals[idx])?.let { if (it.isAfter(latest)) latest = it }
            }
            return latest
        } else {
            val key = sensorKeys[selectedSensorIndex - 1]
            val entries = dataList.mapNotNull { obj ->
                val ts = OffsetDateTime.parse(obj.getString("created_at")).plusHours(2)
                if (ts.isBefore(lookback)) return@mapNotNull null
                val v = obj.optInt(key, -1).takeIf { it >= 0 } ?: return@mapNotNull null
                ts to v.toFloat()
            }.sortedBy { it.first }
            return dipFor(entries, wetVals[selectedSensorIndex - 1]) ?: lookback
        }
    }

    /* =========================================================== */
    /*  misc                                                      */
    /* =========================================================== */
    private fun finishChart() {
        chart.axisRight.isEnabled = false
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
