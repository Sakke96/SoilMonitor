package com.example.soilmonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var tvTimestamp: TextView

    // Base URL to fetch JPEG index pages and images
    private val SERVER_BASE = "http://kasiyip.be/shitting"

    // Coroutine Jobs for animation and live refresh
    private var animJob: Job? = null
    private var liveJob: Job? = null

    // Date formatter using "dd/MM/yyyy HH:mm" (no seconds)
    private val sdfDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

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
        tvTimestamp = root.findViewById(R.id.tvTimestamp)

        // By default, ensure ImageView uses fitCenter (full width, no cropping)
        gifView.scaleType = ImageView.ScaleType.FIT_CENTER

        btnOneDay.setOnClickListener {
            stopLiveMode()
            startAnimationMode(days = 1)
        }
        btnSevenDays.setOnClickListener {
            stopLiveMode()
            startAnimationMode(days = 7)
        }
        btnLive.setOnClickListener {
            stopAnimationMode()
            startLiveMode()
        }

        // ──────────────────────────────────────────────────────────────────────────────
        // *** NEW: Start Live Mode immediately when the fragment is opened ***
        // ──────────────────────────────────────────────────────────────────────────────
        startLiveMode()

        return root
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1) ANIMATION MODE (1-Day / 7-Day): download all JPEGs for each requested date,
    //    set each file’s lastModified from the HTTP header, and cycle frames.
    // ──────────────────────────────────────────────────────────────────────────────
    private fun startAnimationMode(days: Int) {
        progressBar.visibility = View.VISIBLE
        gifView.visibility = View.GONE
        tvTimestamp.text = "—"
        stopAnimationMode()

        animJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Build a list of the last N dates, e.g. ["2025-06-02", "2025-06-01", ...]
                val dateList = getLastNDates(days)

                // 2. For each date, download all JPEGs and collect (Bitmap, timestampMillis)
                val frames: MutableList<Pair<Bitmap, Long>> = mutableListOf()
                for (dateStr in dateList) {
                    val localFiles = downloadAllForDate(dateStr)
                    for (file in localFiles) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            val tsMillis = file.lastModified() // Already set during download
                            frames.add(Pair(bmp, tsMillis))
                        }
                    }
                }

                // 3. Switch to Main thread to start cycling through frames
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (frames.isNotEmpty()) {
                        gifView.visibility = View.VISIBLE

                        // Launch a coroutine on Main to cycle through the frames
                        animJob = lifecycleScope.launch {
                            var index = 0
                            val frameDelayMs = 50L
                            while (isActive) {
                                val (bitmap, tsMillis) = frames[index]
                                gifView.setImageBitmap(bitmap)
                                tvTimestamp.text = sdfDisplay.format(Date(tsMillis))
                                index = (index + 1) % frames.size
                                delay(frameDelayMs)
                            }
                        }
                    } else {
                        gifView.visibility = View.GONE
                        tvTimestamp.text = "No images available"
                    }
                }
            } catch (e: Exception) {
                Log.e("PhotoFragment", "Error fetching/animating photos", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvTimestamp.text = "Error loading images"
                }
            }
        }
    }

    private fun stopAnimationMode() {
        animJob?.cancel()
        animJob = null
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 2) LIVE MODE: Every 60 seconds, fetch the latest JPEG for “today,” show it,
    //    and update the timestamp below the ImageView.
    // ──────────────────────────────────────────────────────────────────────────────
    private fun startLiveMode() {
        stopLiveMode()
        progressBar.visibility = View.VISIBLE
        tvTimestamp.text = "—"

        liveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val latestPair = fetchLatestPhoto()
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (latestPair != null) {
                        val (bitmap, tsMillis) = latestPair
                        gifView.setImageBitmap(bitmap)
                        gifView.visibility = View.VISIBLE
                        tvTimestamp.text = sdfDisplay.format(Date(tsMillis))
                    } else {
                        gifView.visibility = View.GONE
                        tvTimestamp.text = "No live image"
                    }
                }
                // Wait 60 seconds before refreshing
                delay(60_000L)
            }
        }
    }

    private fun stopLiveMode() {
        liveJob?.cancel()
        liveJob = null
    }

    override fun onPause() {
        super.onPause()
        stopAnimationMode()
        stopLiveMode()
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 3) HELPERS: fetchLatestPhoto(), getLastNDates(), downloadAllForDate(), downloadFile(), fetchUrlAsString()
    // ──────────────────────────────────────────────────────────────────────────────
    private fun fetchLatestPhoto(): Pair<Bitmap, Long>? {
        // Build today’s folder, e.g. “photos/2025-06-02”
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val localDir = File(requireContext().filesDir, "photos/$dateStr")
        if (!localDir.exists()) localDir.mkdirs()

        // 1) Fetch the index HTML for today’s folder
        val indexUrl = "$SERVER_BASE/data/$dateStr/"
        val html = fetchUrlAsString(indexUrl) ?: return null

        // 2) Parse “href="<n>.jpg"” and pick the largest <n> for the latest frame
        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html).toList()
        if (matches.isEmpty()) return null

        val latestHref = matches
            .maxByOrNull { it.groupValues[1].substringBefore('.').toIntOrNull() ?: 0 }
            ?.groupValues
            ?.get(1) ?: return null

        val latestUrl = "$indexUrl$latestHref"
        val latestLocal = File(localDir, latestHref)

        // 3) Download if not already on disk (downloadFile(...) will set lastModified)
        if (!latestLocal.exists()) {
            downloadFile(latestUrl, latestLocal)
        }

        // 4) Decode bitmap and return (bitmap, file.lastModified())
        val bmp = BitmapFactory.decodeFile(latestLocal.absolutePath) ?: return null
        val tsMillis = latestLocal.lastModified()
        return Pair(bmp, tsMillis)
    }

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

        val indexUrl = "$SERVER_BASE/data/$dateStr/"
        val html = fetchUrlAsString(indexUrl) ?: return emptyList()

        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html)
        val downloadedFiles = mutableListOf<File>()

        for (m in matches) {
            val href = m.groupValues[1]                      // e.g. "1.jpg"
            val fileUrl = "$indexUrl$href"                   // e.g. "http://kasiyip.be/shitting/data/2025-06-01/1.jpg"
            val localFile = File(localDir, href)

            if (!localFile.exists()) {
                downloadFile(fileUrl, localFile)             // downloadFile(...) sets file.lastModified()
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
                    // 1) Copy stream → local file
                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                    // 2) Read “Last-Modified” (milliseconds since epoch) and apply it
                    val remoteLastMod = conn.lastModified
                    if (remoteLastMod > 0) {
                        destFile.setLastModified(remoteLastMod)
                    }
                } else {
                    throw Exception("HTTP ${conn.responseCode} for $urlStr")
                }
            } catch (t: Throwable) {
                Log.e("PhotoFragment", "Error downloading $urlStr", t)
            } finally {
                conn.disconnect()
            }
        }
    }
}
