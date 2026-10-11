package com.doxigo.tv30

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The title page's last row. 30nama has no similar-titles call, so it is TMDB's recommendations
 * (its similar titles when it has none) mapped back to what 30nama streams, as the trending row
 * is. Without TMDB, or when none of them is on 30nama, the row says what it is instead: more of
 * the title's first genre, of the same kind.
 */
object Similar {
    /** A row's name and its titles. */
    class Shelf(val name: String, val titles: List<JSONObject>)

    private const val SIZE = 12
    /** TMDB's matches by title and language ([] for none), for the process's life: twelve searches is too many to repeat. */
    private val matched = ConcurrentHashMap<String, List<JSONObject>>()

    /** «عناوین مشابه», else «بیشتر در درام»; null when neither has anything. */
    suspend fun titles(context: Context, title: JSONObject): Shelf? {
        val key = "${title.titleId}/${Api.language}"
        val similar = matched[key] ?: recommended(title)?.also {
            // A page closed mid-search matched nothing because it stopped, not because there is nothing.
            currentCoroutineContext().ensureActive()
            matched[key] = it
        }
        if (!similar.isNullOrEmpty()) return Shelf(context.getString(R.string.similar_titles), similar)
        return genre(context, title)
    }

    /** TMDB's first twelve as 30nama titles, without this one; null when TMDB is off or out of reach, so the next visit asks again. */
    private suspend fun recommended(title: JSONObject): List<JSONObject>? {
        val entry = runCatching { Tmdb.title(title) }.getOrElse { return null } ?: return emptyList()
        val items = entry.obj("recommendations")?.optJSONArray("results").objects()
            .ifEmpty { entry.obj("similar")?.optJSONArray("results").objects() }
        // ponytail: a 30nama search that fails here counts as no match until the app restarts.
        return Tmdb.match(items.take(SIZE), entry.optString("media_type")).filter { it.titleId != title.titleId }
    }

    // ponytail: page 1 of the genre (24 titles, about half of them the same kind); page 2 if the row runs short.
    private suspend fun genre(context: Context, title: JSONObject): Shelf? {
        val genre = title.optJSONArray("genre").objects().firstOrNull() ?: return null
        val slug = genre.str("slug") ?: return null
        val kind = title.str("options", "title_type")
        val titles = runCatching { Api.archive(slug, 1, "relevant") }.getOrNull()?.optJSONArray("posts").objects()
            .filter { it.str("options", "title_type") == kind && it.titleId != title.titleId }
            .take(SIZE)
        if (titles.isEmpty()) return null
        return Shelf(context.getString(R.string.more_in_genre, genre.str("name") ?: slug), titles)
    }
}
