package com.fr3ts0n.ecu.gui.androbd.fold

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val source: String,
    val device: String?,
    val samples: Int,
    val channels: Int,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    val isDemo: Boolean get() = source == SessionRecorder.SOURCE_DEMO
}

class SessionChannel(val key: String, val label: String, val units: String, val order: Int)

class SessionData(val summary: SessionSummary, val channels: List<SessionChannel>, val series: Map<String, Series>)

/** SQLite schema for recorded sessions (stored in the app's private storage). */
private class SessionDb(context: Context) : SQLiteOpenHelper(context, "fold_sessions.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE session(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                device TEXT,
                samples INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE channel(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
                key TEXT NOT NULL,
                label TEXT NOT NULL,
                units TEXT NOT NULL DEFAULT '',
                ord INTEGER NOT NULL DEFAULT 0,
                UNIQUE(session_id, key))"""
        )
        db.execSQL(
            """CREATE TABLE sample(
                channel_id INTEGER NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
                t INTEGER NOT NULL,
                v REAL NOT NULL)"""
        )
        db.execSQL("CREATE INDEX sample_channel_t ON sample(channel_id, t)")
        db.execSQL("CREATE INDEX channel_session ON channel(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * Saves live data on the phone.
 *
 * Samples are queued from the comm thread and written once a second in one transaction on a
 * single background thread. A session starts with the first sample and ends when the link
 * drops, live data is left, or no data arrives for [GAP_MS].
 */
object SessionRecorder {
    const val SOURCE_DEMO = "demo"
    const val SOURCE_LIVE = "live"

    private const val TAG = "FoldRecorder"
    /** no reading for this long (engine off, adapter silent) ends the session */
    private const val GAP_MS = 60_000L

    private class Pending(val key: String, val label: String, val units: String, val order: Int, val t: Long, val v: Float)

    @Volatile
    private var helper: SessionDb? = null
    private val queue = ConcurrentLinkedQueue<Pending>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fold-recorder").apply { isDaemon = true }
    }

    // --- worker thread state ---
    private var sessionId = -1L
    private var sessionSamples = 0
    private var lastT = 0L
    private val channelIds = HashMap<String, Long>()

    /** start time of the session being recorded, or 0 if none (read by the UI) */
    @Volatile
    var recordingSince: Long = 0L
        private set

    /** bumps whenever sessions are added, closed or deleted (UI refresh hint) */
    @Volatile
    var changeCount: Int = 0
        private set

    @JvmStatic
    fun init(context: Context) {
        if (helper != null) return
        synchronized(this) {
            if (helper != null) return
            helper = SessionDb(context.applicationContext)
            worker.scheduleWithFixedDelay({ safely { flush() } }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun offer(key: String, label: String, units: String, order: Int, t: Long, v: Float) {
        if (helper == null) return
        queue.add(Pending(key, label, units, order, t, v))
    }

    /** Close the current session (link dropped, demo stopped, other OBD service selected). */
    @JvmStatic
    fun endSession() {
        if (helper == null) return
        worker.execute { safely { flush(); closeSession() } }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "recorder error", e)
        }
    }

    private fun closeSession() {
        if (sessionId >= 0) changeCount++
        sessionId = -1L
        sessionSamples = 0
        channelIds.clear()
        recordingSince = 0L
    }

    private fun flush() {
        try {
            flushQueue()
        } catch (e: Exception) {
            // never keep pointing at rows a rolled-back transaction removed
            closeSession()
            throw e
        }
    }

    private fun flushQueue() {
        val db = helper?.writableDatabase ?: return
        if (queue.isEmpty()) {
            if (sessionId >= 0 && System.currentTimeMillis() - lastT > GAP_MS) closeSession()
            return
        }
        val insert = db.compileStatement("INSERT INTO sample(channel_id, t, v) VALUES (?, ?, ?)")
        db.beginTransaction()
        try {
            while (true) {
                val p = queue.poll() ?: break
                if (sessionId >= 0 && p.t - lastT > GAP_MS) closeSession()
                if (sessionId < 0) openSession(db, p.t)
                val channelId = channelIds[p.key] ?: insertChannel(db, p).also { channelIds[p.key] = it }
                insert.bindLong(1, channelId)
                insert.bindLong(2, p.t)
                insert.bindDouble(3, p.v.toDouble())
                insert.executeInsert()
                insert.clearBindings()
                if (p.t > lastT) lastT = p.t
                sessionSamples++
            }
            if (sessionId >= 0) {
                db.execSQL(
                    "UPDATE session SET ended_at = ?, samples = ? WHERE id = ?",
                    arrayOf<Any>(lastT, sessionSamples, sessionId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            insert.close()
        }
    }

    private fun openSession(db: SQLiteDatabase, t: Long) {
        val demo = LiveTelemetry.mode == LinkMode.DEMO
        val values = ContentValues().apply {
            put("started_at", t)
            put("ended_at", t)
            put("source", if (demo) SOURCE_DEMO else SOURCE_LIVE)
            put("device", if (demo) null else LiveTelemetry.deviceName)
            put("samples", 0)
        }
        sessionId = db.insertOrThrow("session", null, values)
        sessionSamples = 0
        lastT = t
        channelIds.clear()
        recordingSince = t
        changeCount++
    }

    private fun insertChannel(db: SQLiteDatabase, p: Pending): Long {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("key", p.key)
            put("label", p.label)
            put("units", p.units)
            put("ord", p.order)
        }
        return db.insertOrThrow("channel", null, values)
    }

    // ---------------------------------------------------------------- queries (call off the main thread)

    fun listSessions(): List<SessionSummary> {
        val db = helper?.readableDatabase ?: return emptyList()
        val out = ArrayList<SessionSummary>()
        db.rawQuery(
            """SELECT s.id, s.started_at, s.ended_at, s.source, s.device, s.samples,
                      (SELECT COUNT(*) FROM channel c WHERE c.session_id = s.id)
               FROM session s WHERE s.samples > 0 ORDER BY s.started_at DESC""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += SessionSummary(
                    id = c.getLong(0),
                    startedAt = c.getLong(1),
                    endedAt = c.getLong(2),
                    source = c.getString(3),
                    device = if (c.isNull(4)) null else c.getString(4),
                    samples = c.getInt(5),
                    channels = c.getInt(6),
                )
            }
        }
        return out
    }

    fun load(id: Long): SessionData? {
        val summary = listSessions().firstOrNull { it.id == id } ?: return null
        val db = helper?.readableDatabase ?: return null
        val channels = ArrayList<Pair<Long, SessionChannel>>()
        db.rawQuery("SELECT id, key, label, units, ord FROM channel WHERE session_id = ? ORDER BY ord, id", arrayOf(id.toString()))
            .use { c -> while (c.moveToNext()) channels += c.getLong(0) to SessionChannel(c.getString(1), c.getString(2), c.getString(3), c.getInt(4)) }
        val series = LinkedHashMap<String, Series>()
        for ((channelId, ch) in channels) {
            db.rawQuery("SELECT t, v FROM sample WHERE channel_id = ? ORDER BY t", arrayOf(channelId.toString())).use { c ->
                val t = LongArray(c.count)
                val v = FloatArray(c.count)
                var i = 0
                while (c.moveToNext()) {
                    t[i] = c.getLong(0)
                    v[i] = c.getFloat(1)
                    i++
                }
                series[ch.key] = Series(t, v)
            }
        }
        return SessionData(summary, channels.map { it.second }, series)
    }

    /** Delete a session. Runs on the recorder thread so the session being recorded is closed first. */
    fun delete(id: Long) {
        val db = helper?.writableDatabase ?: return
        worker.submit(Runnable {
            if (id == sessionId) {
                safely { flush() }
                closeSession()
            }
            safely { db.delete("session", "id = ?", arrayOf(id.toString())) }
            changeCount++
        }).get()
    }

    fun databaseBytes(context: Context): Long {
        val f = context.applicationContext.getDatabasePath("fold_sessions.db")
        return listOf(f, File(f.path + "-wal"), File(f.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }

    /** Write one session as CSV (one row per sample) into the app cache; returns the file. */
    fun exportCsv(context: Context, id: Long): File? {
        val data = load(id) ?: return null
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(data.summary.startedAt))
        val file = File(dir, "androbd-session-$stamp-$id.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        file.bufferedWriter().use { w ->
            w.write("time,elapsed_s,key,label,value,units\n")
            for (ch in data.channels) {
                val s = data.series[ch.key] ?: continue
                for (i in 0 until s.size) {
                    val t = s.t[i]
                    w.write(iso.format(Date(t)))
                    w.write(",")
                    w.write(String.format(Locale.US, "%.3f", (t - data.summary.startedAt) / 1000.0))
                    w.write(",${csv(ch.key)},${csv(ch.label)},${s.v[i]},${csv(ch.units)}\n")
                }
            }
        }
        return file
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
