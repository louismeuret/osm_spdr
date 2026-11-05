package org.spdr.companion

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session CSV logs (spec §3) so walks can be replayed offline:
 * steps.csv, events.csv, estimates.csv, session.json and, optionally, raw sensor CSVs.
 * Timestamps are SensorEvent / elapsedRealtimeNanos based.
 */
class SessionLog(context: Context, extra: JSONObject) {
    val dir: File = File(context.getExternalFilesDir(null), "sessions/" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }

    private val writers = HashMap<String, BufferedWriter>()
    private var lastFlush = SystemClock.elapsedRealtime()

    init {
        val info = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("wall_clock_ms", System.currentTimeMillis())
            .put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
        for (k in extra.keys()) info.put(k, extra.get(k))
        File(dir, "session.json").writeText(info.toString(2))
    }

    @Synchronized
    fun write(file: String, header: String, line: String) {
        val w = writers.getOrPut(file) {
            BufferedWriter(FileWriter(File(dir, file), true), 64 * 1024).also { it.write(header); it.newLine() }
        }
        w.write(line); w.newLine()
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlush > 3000) { writers.values.forEach { it.flush() }; lastFlush = now }
    }

    fun event(type: String, payload: String) =
        write("events.csv", "timestamp_ns,type,payload", "${SystemClock.elapsedRealtimeNanos()},$type,\"${payload.replace("\"", "'")}\"")

    @Synchronized
    fun close() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
    }
}
