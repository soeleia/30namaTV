package com.doxigo.tv30

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---- New episodes: what came out of the followed series since the viewer last looked. ----

/**
 * 30nama says only the day a series' next episode airs (`air.next_episode_date`), and moves on to
 * the one after once it has. So each look remembers the days still ahead, and a remembered day
 * passed by the next look is an episode out since, as the Apple TV client's EpisodeAlerts has it;
 * half a day ([GRACE]) is allowed after it, for time zones and for the episode to reach 30nama.
 * Followed is Continue Watching and the notify list, looked at with home's own refresh.
 *
 * The news stands until the viewer goes back to the series, which moves its continue_watching
 * point ([watchingAt]) off where it stood before the episode aired; for a week ([KEEP]) at most,
 * when the next one is due; and not once it is taken off the launcher (WatchNext). The state is
 * the signed-in account's, in app-private preferences.
 */
object NewEpisodes {
    const val GRACE = 12 * 3_600_000L
    const val KEEP = 7 * 86_400_000L
    private const val KEY = "state"

    /** An episode's [day] (UTC midnight, ms), and where the viewer was in its series before it ([watchingAt]). */
    class Alert(val day: Long, val watching: String)

    /** A followed series as a look finds it: the day its next episode airs, if 30nama says, and where it is being watched. */
    class Followed(val id: Int, val day: Long?, val watching: String)

    /** The days still [ahead] at the last look, and the episodes [out] since. */
    class State(val ahead: Map<Int, Alert> = emptyMap(), val out: Map<Int, Alert> = emptyMap())

    /**
     * [state] after a look at the series [followed] (the first of an id counts) at [now]. A
     * remembered day [GRACE] past is an episode out, news as of where its series stood at the look
     * before; news the viewer has gone back to, or a week old, is dropped, and so is a series no
     * longer followed. Days still ahead are remembered with where the series stands, until the day
     * itself: from then on a viewer at it may be watching the new episode. A day moved before it
     * came is the new one; once it has come, it waits out its grace though 30nama names the next.
     */
    fun look(state: State, followed: List<Followed>, now: Long): State {
        val byId = followed.distinctBy { it.id }.associateBy { it.id }
        val out = state.out.filterTo(HashMap()) { (id, alert) -> byId[id]?.let { isNew(alert, it.watching, now) } == true }
        val ahead = HashMap<Int, Alert>()
        for ((id, alert) in state.ahead) {
            val series = byId[id] ?: continue
            when {
                alert.day + GRACE <= now -> if (isNew(alert, series.watching, now)) out[id] = alert
                now < alert.day -> ahead[id] = Alert(alert.day, series.watching)
                else -> ahead[id] = alert
            }
        }
        // A day already past its grace is one this look is too late for, or one already told: never news.
        for (series in byId.values) {
            val day = series.day?.takeIf { it + GRACE > now } ?: continue
            val waiting = ahead[series.id]
            if (waiting == null || waiting.day != day && now < waiting.day) ahead[series.id] = Alert(day, series.watching)
        }
        return State(ahead, out)
    }

    /** Whether [alert] is still news for a series at [watching]: not gone back to, and under a week old. */
    fun isNew(alert: Alert, watching: String, now: Long) = alert.watching == watching && now < alert.day + KEEP

    fun encode(state: State): String = JSONObject().put("ahead", alerts(state.ahead)).put("out", alerts(state.out)).toString()

    private fun alerts(alerts: Map<Int, Alert>) = JSONObject().apply {
        for ((id, alert) in alerts) put(id.toString(), JSONArray().put(alert.day).put(alert.watching))
    }

    fun decode(text: String?): State {
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return State()
        fun alerts(key: String): Map<Int, Alert> {
            val all = json.optJSONObject(key) ?: return emptyMap()
            return all.keys().asSequence().mapNotNull { id ->
                val alert = all.optJSONArray(id) ?: return@mapNotNull null
                id.toIntOrNull()?.let { it to Alert(alert.optLong(0), alert.optString(1)) }
            }.toMap()
        }
        return State(alerts("ahead"), alerts("out"))
    }

    /** Read once, then kept. Main thread only. */
    private var state: State? = null

    private fun prefs(context: Context) = context.getSharedPreferences("episodes", Context.MODE_PRIVATE)

    private fun state(context: Context) = state ?: decode(prefs(context).getString(KEY, null)).also { state = it }

    private fun save(context: Context, new: State) {
        state = new
        prefs(context).edit().putString(KEY, encode(new)).apply()
    }

    /** Home's look at the [followed] posts: Continue Watching's first (they say where each series stands), then the notify list's. */
    fun look(context: Context, followed: List<JSONObject>, now: Long = System.currentTimeMillis()) =
        save(context, look(state(context), followed.filter { !it.isMovie }.map { Followed(it.titleId, it.nextEpisodeDay, it.watchingAt) }, now))

    /** [title]'s news (a continue or notify post): the episode out that its viewer has not gone back to, or null. */
    fun of(context: Context, title: JSONObject, now: Long = System.currentTimeMillis()): Alert? =
        state(context).out[title.titleId]?.takeIf { isNew(it, title.watchingAt, now) }

    /** Taken off the launcher: told, and no longer news here either. */
    fun dismiss(context: Context, id: Int) {
        val now = state(context)
        if (id in now.out) save(context, State(now.ahead, now.out - id))
    }

    /** Signed out: the account's series go with it. */
    fun clear(context: Context) {
        state = State()
        prefs(context).edit().clear().apply()
    }
}

/** Where a continue_watching post stands, "season episode seconds" from its `data`; empty for a series not being watched. */
val JSONObject.watchingAt get() = obj("data")?.let { "${it.optInt("season")} ${it.optInt("episode")} ${it.optLong("time")}" }.orEmpty()

/**
 * The day of a series' next episode as UTC midnight, in ms: "23 October 2026 Friday" (parse()
 * stops after the year). UTC, as the server's day is no one's in particular; [NewEpisodes.GRACE]
 * covers the difference.
 */
val JSONObject.nextEpisodeDay: Long? get() = str("air", "next_episode_date", "g")?.let { day ->
    runCatching { SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(day)?.time }.getOrNull()
}
