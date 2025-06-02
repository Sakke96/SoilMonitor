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
import android.widget.SeekBar
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

    // Slider components
    private lateinit var seekBarFps: SeekBar
    private lateinit var tvFpsLabel: TextView
    private lateinit var fpsSliderRow: View    // reference to the entire row for show/hide

    // Base URL to fetch JPEG index pages and images
    private val SERVER_BASE = "http://kasiyip.be/shitting"

    // Coroutine Jobs for animation and live refresh
    private var animJob: Job? = null
    private var liveJob: Job? = null

    // Date formatter using "dd/MM/yyyy HH:mm" (no seconds)
    private val sdfDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

    // Caching & FPS handling
    private var currentFrames: List<Pair<Bitmap, Long>>? = null
    private var currentDays: Int = 1
    private var currentFps: Int = 100   // default = 100 fps (10ms delay)

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

        // Slider views
        fpsSliderRow = root.findViewById(R.id.fps_slider_row)
        seekBarFps = root.findViewById(R.id.seekBarFps)
        tvFpsLabel = root.findViewById(R.id.tvFpsLabel)

        // By default, image uses fitCenter (no cropping).
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

        // ──────────────────────────────────────────────────────────────────────────
        // 1) Initialize FPS slider to 100, update label, and hide slider (Live mode)
        // ──────────────────────────────────────────────────────────────────────────
        currentFps = 100
        seekBarFps.progress = currentFps - 1     // raw 0..199 → actual 1..200
        tvFpsLabel.text = "FPS: $currentFps"
        fpsSliderRow.visibility = View.GONE      // hide initially (Live is default)

        // SeekBar change listener
        seekBarFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentFps = progress + 1
                tvFpsLabel.text = "FPS: $currentFps"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                currentFrames?.let { frames ->
                    restartAnimation(frames)
                }
            }
        })

        // ──────────────────────────────────────────────────────────────────────────
        // 2) Start Live mode by default on fragment creation
        // ──────────────────────────────────────────────────────────────────────────
        startLiveMode()

        return root
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1) ANIMATION MODE (1-Day / 7-Day) → show FPS slider, download frames, then animate
    // ──────────────────────────────────────────────────────────────────────────────
    private fun startAnimationMode(days: Int) {
        progressBar.visibility = View.VISIBLE
        gifView.visibility = View.GONE
        tvTimestamp.text = "—"
        stopAnimationMode()

        currentDays = days
        currentFrames = null               // clear any old frames

        // Show FPS slider when starting animation mode
        fpsSliderRow.visibility = View.VISIBLE

        animJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Build a list of the last N dates (e.g. ["2025-06-02", "2025-06-01", ...])
                val dateList = getLastNDates(days)

                // 2. Download all JPEGs for those dates and collect (Bitmap, timestampMillis)
                val frames: MutableList<Pair<Bitmap, Long>> = mutableListOf()
                for (dateStr in dateList) {
                    val localFiles = downloadAllForDate(dateStr)
                    for (file in localFiles) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            val tsMillis = file.lastModified() // from HTTP “Last-Modified”
                            frames.add(Pair(bmp, tsMillis))
                        }
                    }
                }

                // Cache them so we can re‐animate at a new FPS without re-downloading
                currentFrames = frames

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (frames.isNotEmpty()) {
                        gifView.visibility = View.VISIBLE
                        animateFrames(frames)
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

    /** Starts a coroutine that loops through `frames` at `currentFps`. */
    private fun animateFrames(frames: List<Pair<Bitmap, Long>>) {
        // Cancel existing job if any
        animJob?.cancel()

        // Compute delay in ms (guard if currentFps somehow becomes 0)
        val delayMs = if (currentFps > 0) 1000L / currentFps else 10L

        animJob = lifecycleScope.launch(Dispatchers.Main) {
            var index = 0
            while (isActive) {
                val (bitmap, tsMillis) = frames[index]
                gifView.setImageBitmap(bitmap)
                tvTimestamp.text = sdfDisplay.format(Date(tsMillis))
                index = (index + 1) % frames.size
                delay(delayMs)
            }
        }
    }

    /** Called after the user adjusts FPS and releases the SeekBar. */
    private fun restartAnimation(frames: List<Pair<Bitmap, Long>>) {
        // Only restart if we’re truly in animation mode
        if (currentFrames != null) {
            animateFrames(frames)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 2) LIVE MODE → hide FPS slider and refresh the latest JPEG every 60s
    // ──────────────────────────────────────────────────────────────────────────────
    private fun startLiveMode() {
        stopLiveMode()
        progressBar.visibility = View.VISIBLE
        tvTimestamp.text = "—"

        // Hide FPS slider in Live mode
        fpsSliderRow.visibility = View.GONE

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
                // Refresh every 60 seconds
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
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val localDir = File(requireContext().filesDir, "photos/$dateStr")
        if (!localDir.exists()) localDir.mkdirs()

        val indexUrl = "$SERVER_BASE/data/$dateStr/"
        val html = fetchUrlAsString(indexUrl) ?: return null

        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html).toList()
        if (matches.isEmpty()) return null

        val latestHref = matches
            .maxByOrNull { it.groupValues[1].substringBefore('.').toIntOrNull() ?: 0 }
            ?.groupValues
            ?.get(1) ?: return null

        val latestUrl = "$indexUrl$latestHref"
        val latestLocal = File(localDir, latestHref)

        if (!latestLocal.exists()) {
            downloadFile(latestUrl, latestLocal)
        }

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
            val href = m.groupValues[1]
            val fileUrl = "$indexUrl$href"
            val localFile = File(localDir, href)

            if (!localFile.exists()) {
                downloadFile(fileUrl, localFile)
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
