package com.doxigo.tv30

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    @Test
    fun parametersInTheShapesTheServerReads() {
        // type and genre as lists, country as a string, score and years as numbers (see Filter.parameters).
        val all = Filter(genre = "comedy", country = "FR", score = 7.5, decade = 2010).parameters("movie")
        assertEquals(setOf("stream", "type", "genre", "country", "imdb_rate", "min_year", "max_year"), all.keys().asSequence().toSet())
        assertEquals("true", all.getString("stream"))
        assertEquals("""["movie"]""", all.getJSONArray("type").toString())
        assertEquals("""["comedy"]""", all.getJSONArray("genre").toString())
        assertEquals("FR", all.get("country"))
        assertEquals(7.5, all.get("imdb_rate"))
        assertEquals(2010, all.get("min_year"))
        assertEquals(2019, all.get("max_year"))
        val one = Filter(score = 7.0).parameters("anime")
        assertEquals(setOf("stream", "type", "imdb_rate"), one.keys().asSequence().toSet())
        assertEquals("""["anime"]""", one.getJSONArray("type").toString())
        assertTrue(Filter().isEmpty)
    }

    @Test
    fun choicesFromAdvancedSearchParameters() {
        val choices = FilterChoices(JSONObject("""{
            "genre":[{"name":"درام","value":"drama"},{"name":"بی‌نام","value":""}],
            "country":[{"name":"آمریکا","value":"US"}],
            "imdb_rate":[{"name":"بالای ۹.۰","value":9},{"name":"بالای ۷.۵","value":7.5},{"name":"زیر ۵.۰","value":-5}],
            "min_year":1890,"max_year":2026}"""))
        assertEquals(listOf(FilterOption("درام", "drama")), choices.genres) // a blank value is no choice
        assertEquals(listOf(FilterOption("آمریکا", "US")), choices.countries)
        assertEquals(listOf(9.0, 7.5), choices.scores) // "under 5" is not a floor
        assertEquals((2020 downTo 1890 step 10).toList(), choices.decades)
    }

    @Test
    fun scoreLabels() {
        assertEquals("IMDb 7+‎", Filter.scoreLabel(7.0, fa = false))
        assertEquals("IMDb 8.5+‎", Filter.scoreLabel(8.5, fa = false))
        assertEquals("IMDb ۷+‎", Filter.scoreLabel(7.0, fa = true))
        assertEquals("IMDb ۸٫۵+‎", Filter.scoreLabel(8.5, fa = true))
    }
}
