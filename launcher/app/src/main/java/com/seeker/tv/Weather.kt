package com.seeker.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Current conditions from Open-Meteo (no key) for the WEATHER LOCATION setting (default Denver).
 * The location and the last result are cached in prefs.
 */
class Weather(ctx: Context) {
    data class Now(val icon: String, val temp: String, val at: Long)
    data class Place(val name: String, val lat: Double, val lon: Double)

    private val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fetching = false

    /** Cached reading, or a placeholder on the very first start so the panel is never blank. */
    fun cached(): Now = Now(
        prefs.getString("icon", null) ?: "cloud",
        prefs.getString("temp", null) ?: "--°",
        prefs.getLong("at", 0L),
    )

    fun isStale(): Boolean = System.currentTimeMillis() - prefs.getLong("at", 0L) > REFRESH_MS

    fun place(): Place = place(prefs)

    /** Stores a new location and forgets the old reading, so the next fetch is for the new place. */
    fun setPlace(p: Place) = setPlace(prefs, p)

    fun fetch(done: (Now) -> Unit) {
        if (fetching) return
        fetching = true
        Thread({
            try {
                val p = place()
                val url = String.format(Locale.US, FORECAST, p.lat, p.lon)
                val cur = JSONObject(get(url)).getJSONObject("current")
                val now = Now(
                    iconFor(cur.getInt("weather_code")),
                    "${cur.getDouble("temperature_2m").roundToInt()}°",
                    System.currentTimeMillis(),
                )
                prefs.edit().putString("icon", now.icon).putString("temp", now.temp).putLong("at", now.at).apply()
                main.post { done(now) }
            } catch (e: Exception) {
                Log.w(TAG, "weather fetch failed, keeping cached: $e")
            } finally {
                fetching = false
            }
        }, "seeker-weather").start()
    }

    /** Looks up a city name with Open-Meteo's geocoder. [done] gets null when nothing matched. */
    fun lookup(query: String, done: (Place?) -> Unit) {
        Thread({
            val p = try {
                val r = JSONObject(get(GEOCODE + URLEncoder.encode(query.trim(), "UTF-8"))).optJSONArray("results")
                r?.optJSONObject(0)?.let {
                    Place(it.getString("name").uppercase(Locale.US), it.getDouble("latitude"), it.getDouble("longitude"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "location lookup failed: $e")
                null
            }
            main.post { done(p) }
        }, "seeker-geocode").start()
    }

    companion object {
        private const val TAG = "SeekerTV"
        const val REFRESH_MS = 20 * 60 * 1000L
        val DEFAULT = Place("DENVER", 39.7392, -104.9903)
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
            "&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=auto"
        private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="

        fun place(prefs: android.content.SharedPreferences): Place {
            val name = prefs.getString("place", null) ?: return DEFAULT
            return Place(name, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
        }

        fun setPlace(prefs: android.content.SharedPreferences, p: Place) {
            prefs.edit().putString("place", p.name).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat())
                .remove("icon").remove("temp").putLong("at", 0L).apply()
        }

        private fun get(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** WMO weather_code to the five mockup icons. */
        fun iconFor(code: Int): String = when (code) {
            0, 1 -> "sun"
            2, 3, 45, 48 -> "cloud"
            in 51..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "storm"
            else -> "cloud"
        }
    }
}
