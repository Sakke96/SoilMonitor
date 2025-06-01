package com.example.soilmonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class PhotoFragment : Fragment() {

    private lateinit var btnOneDay: Button
    private lateinit var btnSevenDays: Button
    private lateinit var btnLive: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var gifView: ImageView

    private val SERVER_BASE = "http://kasiyip.be/shitting"

    private var liveJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_photo, container, false)

        btnOneDay = root.findViewById(R.id.btnOneDay)
        btnSevenDays = root.findViewById(R.id.btnSevenDays)
        btnLive = root.findViewById(R.id.btnLive)
        progressBar = root.findViewById(R.id.progressBar)
        gifView = root.findViewById(R.id.gifView)

        btnOneDay.setOnClickListener {
            stopLiveMode()
            fetchAndAnimatePhotos(days = 1)
        }
        btnSevenDays.setOnClickListener {
            stopLiveMode()
            fetchAndAnimatePhotos(days = 7)
        }
        btnLive.setOnClickListener { startLiveMode() }

        return root
    }

    private fun fetchAndAnimatePhotos(days: Int) {
        progressBar.visibility = View.VISIBLE
        gifView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dateList = getLastNDates(days)
                val allBitmaps = ArrayList<Bitmap>()
                for (dateStr in dateList) {
                    val localFiles = downloadAllForDate(dateStr)
                    for (file in localFiles) {
                        BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                            allBitmaps.add(bmp)
                        }
                    }
                }

                val frameDelayMs = 50

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (allBitmaps.isNotEmpty()) {
                        val animDrawable = AnimationDrawable().apply {
                            isOneShot = false
                            allBitmaps.forEach { bmp ->
                                addFrame(BitmapDrawable(resources, bmp), frameDelayMs)
                            }
                        }
                        gifView.setImageDrawable(animDrawable)
                        gifView.visibility = View.VISIBLE
                        animDrawable.start()
                    } else {
                        gifView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("PhotoFragment", "Error fetching photos", e)
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun startLiveMode() {
        stopLiveMode()
        liveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val bitmap = fetchLatestPhoto()
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (bitmap != null) {
                        gifView.setImageBitmap(bitmap)
                        gifView.visibility = View.VISIBLE
                    } else {
                        gifView.visibility = View.GONE
                    }
                }
                delay(60000)
            }
        }
    }

    private fun stopLiveMode() {
        liveJob?.cancel()
    }

    override fun onPause() {
        super.onPause()
        stopLiveMode()
    }

    private fun fetchLatestPhoto(): Bitmap? {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val localDir = File(requireContext().filesDir, "photos/$dateStr")
        if (!localDir.exists()) localDir.mkdirs()

        val indexUrl = "$SERVER_BASE/data/$dateStr/"
        val html = fetchUrlAsString(indexUrl) ?: return null

        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html).toList()

        if (matches.isEmpty()) return null

        val latestHref = matches.maxByOrNull { match ->
            match.groupValues[1].substringBefore('.').toIntOrNull() ?: 0
        }?.groupValues?.get(1) ?: return null

        val latestUrl = "$indexUrl$latestHref"
        val latestFile = File(localDir, latestHref)

        if (!latestFile.exists()) {
            downloadFile(latestUrl, latestFile)
        }

        return BitmapFactory.decodeFile(latestFile.absolutePath)
    }

    // Include existing methods:
    private fun getLastNDates(n: Int): List<String> {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val dates = ArrayList<String>()
        repeat(n) {
            dates.add(df.format(cal.time))
            cal.add(Calendar.DATE, -1)
        }
        return dates
    }
    private fun downloadAllForDate(dateStr: String): List<File> {
        val localDir = File(requireContext().filesDir, "photos/$dateStr")
        if (!localDir.exists()) localDir.mkdirs()

        val indexUrl = "$SERVER_BASE/$dateStr/pictures.jpg"
        val html = fetchUrlAsString(indexUrl) ?: return emptyList()

        val regex = Regex("""href=\"([^\"]+\.jpg)\"""")
        val matches = regex.findAll(html)
        val downloadedFiles = ArrayList<File>()

        for (m in matches) {
            val href = m.groupValues[1]   // e.g. "/shitting/data/2025-06-01/1.jpg"
            val url = if (href.startsWith("http")) href else "http://kasiyip.be$href"
            val fileName = href.substringAfterLast("/")
            val localFile = File(localDir, fileName)
            if (!localFile.exists()) {
                downloadFile(url, localFile)
            }
            downloadedFiles.add(localFile)
        }
        return downloadedFiles
    }

    private fun fetchUrlAsString(urlStr: String): String? {
        (URL(urlStr).openConnection() as? HttpURLConnection)?.let { conn ->
            return try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.connect()
                if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else null
            } catch (t: Throwable) {
                Log.e("PhotoFragment", "Error fetching $urlStr", t)
                null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun downloadFile(urlStr: String, destFile: File) {
        (URL(urlStr).openConnection() as? HttpURLConnection)?.let { conn ->
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.connect()
                if (conn.responseCode == 200) {
                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                } else {
                    throw Exception("HTTP ${conn.responseCode} for $urlStr")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

}
