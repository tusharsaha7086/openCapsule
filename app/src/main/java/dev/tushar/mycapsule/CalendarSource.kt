package dev.tushar.mycapsule

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.text.DateFormat
import java.util.Date

/** One event in the next day: [begin] and [end] in milliseconds, and the page where its meeting takes place, if it names one. */
private class Occurrence(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val where: String?, val link: String?)

/** How the card asks for the calendar permission: it opens the app, which shows Android's own request. */
object CalendarAccess {
    const val EXTRA_ASK = "ask_calendar"

    fun intent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_ASK, true)

    fun granted(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

/**
 * The calendar card of the deck: what is on now or comes next, with the next few events under it and a Join button when
 * an event names a video call. Reads the phone's calendar (with the user's permission); the app never writes to it.
 */
class CalendarSource : CapsuleSource {

    private var cached: List<Occurrence> = emptyList()
    private var cachedAt = 0L

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val icon = context.getDrawable(R.drawable.ic_event)
        if (!CalendarAccess.granted(context)) {
            return CapsuleEvent(
                kind = Kind.CALENDAR,
                title = "Calendar",
                text = "Allow calendar access to see your next event",
                icon = icon,
                actions = listOf(
                    CapsuleAction(null, "Allow calendar access", label = "Allow", dismisses = true) {
                        runCatching { context.startActivity(CalendarAccess.intent(context)) }
                    },
                ),
            )
        }
        val events = upcoming(context)
        val first = events.firstOrNull()
            ?: return CapsuleEvent(kind = Kind.CALENDAR, title = "Calendar", text = "Nothing in the next 24 hours", icon = icon)

        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val rows = events.drop(1).take(VISIBLE).map { event ->
            Row(text = "${whenText(event, time)}   ${event.title}") { open(context, event) }
        }
        return CapsuleEvent(
            kind = Kind.CALENDAR,
            title = first.title,
            text = when {
                first.allDay -> "All day"
                first.begin <= now -> "Now  ·  until ${time.format(Date(first.end))}"
                else -> "${relative(first.begin - now)}  ·  ${time.format(Date(first.begin))}" + first.where?.let { "  ·  $it" }.orEmpty()
            },
            icon = icon,
            onTap = pending(context, first),
            rows = rows,
            actions = first.link?.let { link ->
                listOf(CapsuleAction(null, "Join the meeting", label = "Join", dismisses = true) { openLink(context, link) })
            }.orEmpty(),
            // The countdown moves; the events are cached, so redrawing does not ask the calendar again.
            refreshMs = REFRESH_MS,
        )
    }

    /** The events that are on now or start within a day, soonest first. Asked of the calendar at most every half minute. */
    private fun upcoming(context: Context): List<Occurrence> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached
        cachedAt = now
        cached = runCatching { query(context, now) }.getOrDefault(emptyList())
        return cached
    }

    private fun query(context: Context, now: Long): List<Occurrence> {
        val window = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + DAY_MS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        val found = mutableListOf<Occurrence>()
        context.contentResolver.query(window, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val end = cursor.getLong(COL_END)
                val allDay = cursor.getInt(COL_ALL_DAY) == 1
                // Instances reports whatever overlaps the window; what has already finished is not "next".
                if (end <= now) continue
                val location = cursor.getString(COL_LOCATION)
                val link = MEETING_LINK.find("${location.orEmpty()} ${cursor.getString(COL_DESCRIPTION).orEmpty()}")?.value
                found += Occurrence(
                    cursor.getLong(COL_ID), cursor.getString(COL_TITLE)?.takeIf { it.isNotBlank() } ?: "(No title)",
                    cursor.getLong(COL_BEGIN), end, allDay, location?.takeIf { it.isNotBlank() && link == null }, link,
                )
            }
        }
        // Events that run all day come after the ones with a time, so the next thing to attend is first.
        return found.sortedWith(compareBy({ it.allDay }, { it.begin }))
    }

    private fun whenText(event: Occurrence, time: DateFormat) = if (event.allDay) "All day" else time.format(Date(event.begin))

    private fun relative(ms: Long): String {
        val minutes = (ms / MS_PER_MINUTE).coerceAtLeast(1)
        return when {
            minutes < MINUTES_PER_HOUR -> "in $minutes min"
            else -> "in ${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        }
    }

    private fun viewIntent(event: Occurrence) = Intent(Intent.ACTION_VIEW)
        .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun open(context: Context, event: Occurrence) {
        runCatching { context.startActivity(viewIntent(event)) }
    }

    private fun pending(context: Context, event: Occurrence) =
        PendingIntent.getActivity(context, event.id.toInt(), viewIntent(event), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openLink(context: Context, link: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        const val VISIBLE = 2
        const val DAY_MS = 86_400_000L
        const val CACHE_MS = 30_000L
        const val REFRESH_MS = 30_000L
        const val MS_PER_MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60
        const val COL_ID = 0
        const val COL_TITLE = 1
        const val COL_BEGIN = 2
        const val COL_END = 3
        const val COL_ALL_DAY = 4
        const val COL_LOCATION = 5
        const val COL_DESCRIPTION = 6

        /** The video call services whose links count as a meeting to join. */
        val MEETING_LINK = Regex("https://(?:meet\\.google\\.com|[\\w.-]*zoom\\.us|teams\\.microsoft\\.com|teams\\.live\\.com|[\\w.-]*webex\\.com)/[^\\s<>\"]+")
    }
}
