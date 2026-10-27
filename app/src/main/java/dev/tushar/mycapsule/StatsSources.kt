package dev.tushar.mycapsule

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale

/*
 * Cards that report on the phone itself: the battery, and its memory, storage, network and processor. Both read the
 * system afresh each time the deck builds them and ask to be redrawn every few seconds while it is open.
 */

private const val BYTES_PER_KB = 1024.0
private const val PERCENT = 100

/** Bytes as "512 MB" or "11.6 GB". */
private fun size(bytes: Long): String {
    val gb = bytes / (BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB)
    return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%d MB", (bytes / (BYTES_PER_KB * BYTES_PER_KB)).toLong())
}


/** The battery: its level as the card's bar, with the state, how long until it is full or empty, and its temperature and health. */
class BatterySource : CapsuleSource {

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = level * PERCENT / scale
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val temperature = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / TENTHS
        val health = when (sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            else -> "Unknown"
        }
        val stats = buildList {
            add(Stat(context.getDrawable(R.drawable.ic_thermostat), String.format(Locale.US, "%.1f °C", temperature), "Temperature"))
            add(Stat(context.getDrawable(R.drawable.ic_favorite), health, "Health"))
            add(Stat(context.getDrawable(R.drawable.ic_bolt), String.format(Locale.US, "%.2f V", sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / MILLI), "Voltage"))
            remaining(context, charging)?.let { add(Stat(context.getDrawable(R.drawable.ic_schedule), it, "Time until full")) }
        }
        return CapsuleEvent(
            kind = Kind.BATTERY,
            title = "$percent%",
            text = when {
                full -> "Full"
                charging -> "Charging"
                else -> "On battery"
            },
            icon = context.getDrawable(if (charging) R.drawable.ic_battery_charging_full else R.drawable.ic_battery_full),
            progress = percent / PERCENT.toFloat(),
            stats = stats,
            refreshMs = REFRESH_MS,
        )
    }

    /** How long until full while charging (Android 9 and later), when the phone has an estimate; null otherwise. */
    private fun remaining(context: Context, charging: Boolean): String? {
        if (!charging || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val ms = context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: return null
        if (ms <= 0) return null
        val minutes = ms / MS_PER_MINUTE
        return if (minutes >= MINUTES_PER_HOUR) "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min" else "$minutes min"
    }

    private companion object {
        const val TENTHS = 10.0
        const val MILLI = 1000.0
        const val MS_PER_MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60
        const val REFRESH_MS = 5_000L
    }
}

/**
 * Memory, storage, network speed, the processor and its heat. Android does not let an app read
 * the processor's load (only its own), so the card shows what it can: the number of cores, their current speed when the
 * phone lets that be read, and the heat the phone reports.
 */
class StatsSource : CapsuleSource {

    private var lastBytes = 0L
    private var lastAt = 0L

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        val used = memory.totalMem - memory.availMem
        val memoryShare = if (memory.totalMem > 0) used.toFloat() / memory.totalMem else 0f

        val data = StatFs(Environment.getDataDirectory().path)
        val storageUsed = data.totalBytes - data.availableBytes

        val stats = listOf(
            Stat(context.getDrawable(R.drawable.ic_memory), "${(memoryShare * PERCENT).toInt()}%", "Memory, ${size(used)} of ${size(memory.totalMem)}"),
            Stat(context.getDrawable(R.drawable.ic_storage), "${storageUsed * PERCENT / data.totalBytes.coerceAtLeast(1)}%", "Storage, ${size(storageUsed)} of ${size(data.totalBytes)}"),
            Stat(context.getDrawable(R.drawable.ic_swap_vert), speed(), "Network speed"),
            Stat(context.getDrawable(R.drawable.ic_speed), processor(), "Processor"),
            Stat(context.getDrawable(R.drawable.ic_thermostat), heat(context), "Heat"),
        )
        return CapsuleEvent(
            kind = Kind.STATS,
            title = "Phone stats",
            text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            icon = context.getDrawable(R.drawable.ic_memory),
            progress = memoryShare,
            stats = stats,
            refreshMs = REFRESH_MS,
        )
    }

    /** The speed over the network since the card was last drawn, from the system's byte counters. */
    private fun speed(): String {
        val now = SystemClock.elapsedRealtime()
        val bytes = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: return "Not available"
        val sent = TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        val total = bytes + sent
        val elapsed = now - lastAt
        val perSecond = if (lastAt == 0L || elapsed <= 0) 0.0 else (total - lastBytes) * MS_PER_SECOND / elapsed
        lastBytes = total
        lastAt = now
        return when {
            perSecond >= BYTES_PER_KB * BYTES_PER_KB -> String.format(Locale.US, "%.1f MB/s", perSecond / (BYTES_PER_KB * BYTES_PER_KB))
            else -> String.format(Locale.US, "%d KB/s", (perSecond / BYTES_PER_KB).toLong())
        }
    }

    /** The cores and their fastest current clock. The clocks are in a system file that some phones do not let an app read. */
    private fun processor(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val khz = (0 until cores).mapNotNull { core ->
            runCatching { File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq").readText().trim().toLong() }.getOrNull()
        }.maxOrNull()
        return if (khz != null) String.format(Locale.US, "%.1f GHz", khz / KHZ_PER_GHZ) else "$cores cores"
    }

    /** The phone's own verdict on its heat (Android 10 and later), with the battery's temperature as a fallback. */
    private fun heat(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
            return when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Slightly warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
                PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Hot"
                else -> "Unknown"
            }
        }
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return String.format(Locale.US, "%.1f °C", tenths / TENTHS)
    }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val MS_PER_SECOND = 1_000.0
        const val KHZ_PER_GHZ = 1_000_000.0
        const val TENTHS = 10.0
    }
}
