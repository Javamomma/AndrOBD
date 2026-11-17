package com.fr3ts0n.ecu.gui.androbd.fold

import com.fr3ts0n.ecu.BitmapConversion
import com.fr3ts0n.ecu.Conversion
import com.fr3ts0n.ecu.EcuCodeList
import com.fr3ts0n.ecu.EcuDataItem
import com.fr3ts0n.ecu.EcuDataPv
import com.fr3ts0n.ecu.HashConversion

private val plainNumber = Regex("""^-?\d{4,}(\.\d+)?$""")

/** "13750" -> "13,750" (locale grouping), keeping the decimals AndrOBD chose. */
internal fun groupDigits(text: String): String {
    val t = text.trim()
    if (!plainNumber.matches(t)) return text
    val decimals = t.substringAfter('.', "").length
    val f = java.text.NumberFormat.getNumberInstance()
    f.minimumFractionDigits = decimals
    f.maximumFractionDigits = decimals
    return f.format(t.toDouble())
}

/** Link state, mirrored from MainActivity.MODE (same names). */
enum class LinkMode { OFFLINE, ONLINE, DEMO, FILE }

/** One graphable data item (an OBD PID or plugin value). */
class ChannelInfo(val key: String, val label: String, val order: Int)

/** Immutable time series snapshot: times in epoch ms, values as floats. */
class Series(val t: LongArray, val v: FloatArray) {
    val size: Int get() = t.size

    companion object {
        val EMPTY = Series(LongArray(0), FloatArray(0))
    }
}

/** Fixed-capacity ring buffer of (time, value) samples. Thread safe. */
class SampleRing(private val capacity: Int) {
    private val t = LongArray(capacity)
    private val v = FloatArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(time: Long, value: Float) {
        t[head] = time
        v[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun lastTime(): Long = if (size == 0) 0L else t[(head - 1 + capacity) % capacity]

    @Synchronized
    fun lastValue(): Float? = if (size == 0) null else v[(head - 1 + capacity) % capacity]

    /** Samples at or after [from], plus the one just before it so a line enters from the left edge. */
    @Synchronized
    fun snapshot(from: Long): Series {
        if (size == 0) return Series.EMPTY
        val oldest = (head - size + capacity) % capacity
        var first = 0
        while (first < size && t[(oldest + first) % capacity] < from) first++
        if (first > 0) first--
        val n = size - first
        val ot = LongArray(n)
        val ov = FloatArray(n)
        for (i in 0 until n) {
            val idx = (oldest + first + i) % capacity
            ot[i] = t[idx]
            ov[i] = v[idx]
        }
        return Series(ot, ov)
    }
}

/**
 * Live data hub for the split view.
 *
 * ObdItemAdapter registers every displayed data item and forwards each new value (on the
 * comm thread). Values are kept in a ring buffer per item for the graphs and handed to the
 * [SessionRecorder], which saves them on the phone.
 */
object LiveTelemetry {
    /** ~30-60 minutes per item at typical ELM327 poll rates */
    private const val CAPACITY = 8192

    private class Entry(@Volatile var pv: EcuDataPv, val info: ChannelInfo, val ring: SampleRing)

    private val entries = LinkedHashMap<String, Entry>()

    /** keys of text-only items (status flags, code lists) that are never graphed */
    private val textOnly = HashSet<String>()

    /** Status flags and code lists display text, not numbers: keep them out of graphs and recordings. */
    private fun isGraphable(pv: EcuDataPv): Boolean {
        val cnv = pv.get(EcuDataPv.FID_CNVID) as? Array<*> ?: return true
        val c = cnv.getOrNull(EcuDataItem.cnvSystem) ?: return true
        return !(c is BitmapConversion || c is HashConversion || c is EcuCodeList)
    }

    @Volatile
    var mode: LinkMode = LinkMode.OFFLINE
        private set

    @Volatile
    var deviceName: String? = null
        private set

    /** true while OBD service 1 (live data) is selected; freeze frames are not live data */
    @Volatile
    var liveService: Boolean = false
        private set

    /** time of the most recent sample (epoch ms) */
    @Volatile
    var lastSampleAt: Long = 0L
        private set

    @JvmStatic
    fun keyOf(pv: EcuDataPv): String {
        val mnemonic = pv.get(EcuDataPv.FID_MNEMONIC)?.toString()
        return if (!mnemonic.isNullOrBlank()) mnemonic else pv.toString()
    }

    @JvmStatic
    fun register(pv: EcuDataPv) {
        // read the PV (which takes the PV's own lock) before taking ours: the comm thread
        // holds the PV lock while it calls onSample(), so the opposite order could deadlock
        val key = keyOf(pv)
        val graphable = isGraphable(pv)
        val label = pv.get(EcuDataPv.FID_DESCRIPT)?.toString()?.trim().orEmpty().ifEmpty { key }
        synchronized(this) {
            if (!graphable) {
                textOnly.add(key)
                return
            }
            val existing = entries[key]
            if (existing != null) {
                existing.pv = pv
            } else {
                entries[key] = Entry(pv, ChannelInfo(key, label, entries.size), SampleRing(CAPACITY))
            }
        }
    }

    /** forget everything from the previous link (new car, demo -> real adapter …) */
    private fun clearLive() {
        synchronized(this) {
            entries.clear()
            textOnly.clear()
        }
        lastSampleAt = 0L
    }

    @JvmStatic
    fun onSample(pv: EcuDataPv, time: Long, value: Number) {
        if (!liveService) return
        if (mode != LinkMode.ONLINE && mode != LinkMode.DEMO) return
        val f = value.toFloat()
        if (f.isNaN() || f.isInfinite()) return
        val key = keyOf(pv)
        val entry = synchronized(this) { if (key in textOnly) return else entries[key] } ?: run {
            register(pv)
            synchronized(this) { entries[key] }
        } ?: return
        entry.ring.add(time, f)
        lastSampleAt = time
        SessionRecorder.offer(key, entry.info.label, pv.units.orEmpty(), entry.info.order, time, f)
    }

    @JvmStatic
    fun setLink(newMode: LinkMode, device: String?) {
        val previous = mode
        mode = newMode
        deviceName = device
        if (newMode != previous) {
            // every link change ends the session (demo data never mixes with a real drive)
            SessionRecorder.endSession()
            if (newMode == LinkMode.ONLINE || newMode == LinkMode.DEMO) clearLive()
        }
    }

    @JvmStatic
    fun setLiveService(live: Boolean) {
        if (liveService && !live) SessionRecorder.endSession()
        liveService = live
    }

    /** Channels that have delivered at least one numeric value, in list order. */
    fun channels(): List<ChannelInfo> = synchronized(this) {
        entries.values.filter { it.ring.lastTime() > 0L }.map { it.info }
    }

    fun series(key: String, from: Long): Series =
        synchronized(this) { entries[key] }?.ring?.snapshot(from) ?: Series.EMPTY

    fun lastValue(key: String): Float? = synchronized(this) { entries[key] }?.ring?.lastValue()

    fun units(key: String): String = synchronized(this) { entries[key] }?.pv?.units.orEmpty()

    /** Current value formatted the same way as the classic AndrOBD list. */
    fun valueText(key: String): String {
        val pv = synchronized(this) { entries[key] }?.pv ?: return "—"
        val value = pv.get(EcuDataPv.FID_VALUE)
        return try {
            val cnv = pv.get(EcuDataPv.FID_CNVID)
            val sys = EcuDataItem.cnvSystem
            if (cnv is Array<*> && sys < cnv.size && cnv[sys] is Conversion && value is Number) {
                groupDigits((cnv[sys] as Conversion).physToPhysFmtString(value, pv.get(EcuDataPv.FID_FORMAT) as String?))
            } else if (value is Number) {
                formatNumber(value.toFloat())
            } else {
                value?.toString() ?: "—"
            }
        } catch (e: Exception) {
            value?.toString() ?: "—"
        }
    }
}
