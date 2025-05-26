package com.example.soilmonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class MoistureFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_moisture, container, false)

        val moistureLevels = listOf(150f, 400f, 700f, 1023f) // mock data
        val max = 1023f

        val views = listOf(
            root.findViewById<WaveView>(R.id.plant1),
            root.findViewById<WaveView>(R.id.plant2),
            root.findViewById<WaveView>(R.id.plant3),
            root.findViewById<WaveView>(R.id.plant4)
        )

        views.zip(moistureLevels).forEach { (view, level) ->
            view.progress = level / max
        }

        return root
    }
}
