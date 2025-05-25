package com.example.soilmonitor

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.example.soilmonitor.R

class CustomMarkerView(context: Context) : MarkerView(context, R.layout.marker_view) {
    private val textView: TextView = findViewById(R.id.markerText)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        e?.let {
            textView.text = "Value: ${e.y.toInt()}"
        }
        super.refreshContent(e, highlight)
    }
}
