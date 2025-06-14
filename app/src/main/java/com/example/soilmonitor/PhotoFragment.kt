package com.example.soilmonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var btnTimelapse: Button
    private lateinit var btnLive: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var gifView: ImageView
    private lateinit var tvTimestamp: TextView
    private lateinit var btnFrom: Button
    private lateinit var btnTo: Button
    private lateinit var seekBarTime: SeekBar

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
    private var currentFps: Int = 100   // default = 100 fps (10ms delay)
    private val sdfInput = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private var fromDate: Date = Date()
    private var toDate: Date = Date()
    private var liveFiles: List<File> = emptyList()
    private var userSeeking: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_photo, container, false)

        btnTimelapse = root.findViewById(R.id.btnTimelapse)
        btnLive = root.findViewById(R.id.btnLive)
        progressBar = root.findViewById(R.id.progressBar)
        gifView = root.findViewById(R.id.gifView)
        tvTimestamp = root.findViewById(R.id.tvTimestamp)
        btnFrom = root.findViewById(R.id.btnFrom)
        btnTo = root.findViewById(R.id.btnTo)
        seekBarTime = root.findViewById(R.id.seekBarTime)

        val calNow = Calendar.getInstance()
        toDate = calNow.time
        fromDate = (calNow.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        btnFrom.text = sdfInput.format(fromDate)
        btnTo.text = sdfInput.format(toDate)

        btnFrom.setOnClickListener {
            showDateTimePicker(fromDate) { date ->
                fromDate = date
                btnFrom.text = sdfInput.format(date)
            }
        }

        btnTo.setOnClickListener {
            showDateTimePicker(toDate) { date ->
                toDate = date
                btnTo.text = sdfInput.format(date)
            }
        }

        // Slider views
        fpsSliderRow = root.findViewById(R.id.fps_slider_row)
        seekBarFps = root.findViewById(R.id.seekBarFps)
        tvFpsLabel = root.findViewById(R.id.tvFpsLabel)

        // By default, image uses fitCenter (no cropping).
        gifView.scaleType = ImageView.ScaleType.FIT_CENTER

        btnTimelapse.setOnClickListener {
            stopLiveMode()

            if (fromDate.after(toDate)) {
                Toast.makeText(requireContext(), "Invalid date range", Toast.LENGTH_SHORT).show()
            } else {
                startTimelapseMode(fromDate, toDate)
            }
        }
        btnLive.setOnClickListener {
            stopAnimationMode()
            startLiveMode(fromDate, toDate)
        }

        // ──────────────────────────────────────────────────────────────────────────
        // 1) Initialize FPS slider to 100, update label, and hide slider (Live mode)
        // ──────────────────────────────────────────────────────────────────────────
        currentFps = 100
        seekBarFps.progress = currentFps - 1     // raw 0..199 → actual 1..200
        tvFpsLabel.text = "FPS: $currentFps"
        fpsSliderRow.visibility = View.GONE      // hide initially (Live is default)

        // SeekBar change listener for FPS
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

        // SeekBar listener for live timeline
        seekBarTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress < liveFiles.size) {
                    val file = liveFiles[progress]
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        gifView.setImageBitmap(bmp)
                        tvTimestamp.text = sdfDisplay.format(Date(file.lastModified()))
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
        })

        // ──────────────────────────────────────────────────────────────────────────
        // 2) Start Live mode by default on fragment creation
        // ──────────────────────────────────────────────────────────────────────────
        startLiveMode(fromDate, toDate)

        return root
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1) ANIMATION MODE (1-Day / 7-Day) → show FPS slider, download frames, then animate
    // ──────────────────────────────────────────────────────────────────────────────
    private fun startTimelapseMode(from: Date, to: Date) {
        progressBar.visibility = View.VISIBLE
        gifView.visibility = View.GONE
        tvTimestamp.text = "—"
        stopAnimationMode()

        currentFrames = null

        // Show FPS slider when starting animation mode
        fpsSliderRow.visibility = View.VISIBLE

        animJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dateList = getDatesBetween(from, to)

                val frames: MutableList<Pair<Bitmap, Long>> = mutableListOf()
                for (dateStr in dateList) {
                    val localFiles = downloadSmartForDate(dateStr)
                    for (file in localFiles) {
                        val tsMillis = file.lastModified()
                        if (tsMillis in from.time..to.time) {
                            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) {
                                frames.add(Pair(bmp, tsMillis))
                            }
                        }
                    }
                }

                frames.sortBy { it.second }
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
        fpsSliderRow.visibility = View.GONE
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
    private fun startLiveMode(from: Date, to: Date) {
        stopLiveMode()
        progressBar.visibility = View.VISIBLE
        tvTimestamp.text = "—"

        // Hide FPS slider in Live mode
        fpsSliderRow.visibility = View.GONE
        seekBarTime.progress = 0
        seekBarTime.visibility = View.GONE

        liveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val latestPair = fetchLatestPhotoRange(from, to)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (latestPair != null) {
                        if (!userSeeking) {
                            seekBarTime.visibility = View.VISIBLE
                            seekBarTime.max = liveFiles.size - 1
                            seekBarTime.progress = liveFiles.size - 1
                        }

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
        seekBarTime.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        stopAnimationMode()
        stopLiveMode()
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 3) HELPERS: fetchLatestPhoto(), getDatesBetween(), downloadSmartForDate(), downloadFile(), fetchUrlAsString()
    // ──────────────────────────────────────────────────────────────────────────────
    private fun fetchLatestPhoto(): Pair<Bitmap, Long>? {
        val nowStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val localDir = File(requireContext().filesDir, "photos/$nowStr")
        if (!localDir.exists()) localDir.mkdirs()

        val indexUrl = "$SERVER_BASE/data/$nowStr/"
        val html = fetchUrlAsString(indexUrl) ?: return null

        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html).toList()
        if (matches.isEmpty()) return null

        val remoteNames = matches.map { it.groupValues[1] }
            .sortedBy { it.substringBefore('.').toIntOrNull() ?: 0 }

        val localNames = localDir.listFiles { f -> f.extension.equals("jpg", true) }?.map { it.name } ?: emptyList()
        if (localNames.size < remoteNames.size) {
            val missing = remoteNames.takeLast(remoteNames.size - localNames.size)
            for (name in missing) {
                downloadFile("$indexUrl$name", File(localDir, name))
            }
        }

        liveFiles = remoteNames.map { File(localDir, it) }
            .sortedBy { it.lastModified() }

        val latestFile = liveFiles.lastOrNull() ?: return null
        val bmp = BitmapFactory.decodeFile(latestFile.absolutePath) ?: return null
        val tsMillis = latestFile.lastModified()
        return Pair(bmp, tsMillis)
    }

    private fun fetchLatestPhotoRange(from: Date, to: Date): Pair<Bitmap, Long>? {
        val dates = getDatesBetween(from, to)
        val files = mutableListOf<File>()
        for (date in dates) {
            val daily = downloadSmartForDate(date)
            files.addAll(daily.filter { f ->
                val ts = f.lastModified()
                ts in from.time..to.time
            })
        }
        liveFiles = files.sortedBy { it.lastModified() }
        val latestFile = liveFiles.lastOrNull() ?: return null
        val bmp = BitmapFactory.decodeFile(latestFile.absolutePath) ?: return null
        return Pair(bmp, latestFile.lastModified())
    }

    private fun getDatesBetween(from: Date, to: Date): List<String> {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { time = from }
        val dates = mutableListOf<String>()
        while (!cal.time.after(to)) {
            dates.add(df.format(cal.time))
            cal.add(Calendar.DATE, 1)
        }
        return dates
    }

    private fun downloadSmartForDate(dateStr: String): List<File> {
        val localDir = File(requireContext().filesDir, "photos/$dateStr")
        if (!localDir.exists()) localDir.mkdirs()

        val indexUrl = "$SERVER_BASE/data/$dateStr/"
        val html = fetchUrlAsString(indexUrl) ?: return emptyList()

        val regex = Regex("""href="(\d+\.jpg)"""")
        val matches = regex.findAll(html).toList()
        val remoteNames = matches.map { it.groupValues[1] }.sorted()

        val localNames = localDir.listFiles { f -> f.extension.equals("jpg", true) }?.map { it.name } ?: emptyList()

        if (localNames.size == remoteNames.size) {
            return remoteNames.map { File(localDir, it) }
        }

        val existing = localNames.toSet()
        val missing = if (localNames.size < remoteNames.size) {
            remoteNames.takeLast(remoteNames.size - localNames.size).filter { it !in existing }
        } else {
            remoteNames.filter { it !in existing }
        }

        for (name in missing) {
            val url = "$indexUrl$name"
            val dest = File(localDir, name)
            downloadFile(url, dest)
        }

        return remoteNames.map { File(localDir, it) }
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

    private fun showDateTimePicker(initial: Date, callback: (Date) -> Unit) {
        val cal = Calendar.getInstance().apply { time = initial }
        DatePickerDialog(requireContext(), { _, year, month, day ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            TimePickerDialog(requireContext(), { _, hour, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                callback(cal.time)
            }, h, m, true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
}
