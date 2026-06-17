package com.trailmap.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tiny SharedPreferences-backed store for user prefs (saved trails, map theme, rides). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("trailmap", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun savedIds(): Set<String> = sp.getStringSet(KEY_SAVED, emptySet())?.toSet() ?: emptySet()

    fun setSavedIds(ids: Set<String>) {
        sp.edit().putStringSet(KEY_SAVED, ids).apply()
    }

    /** One of "SYSTEM" | "LIGHT" | "DARK". */
    fun mapTheme(): String = sp.getString(KEY_MAP_THEME, "SYSTEM") ?: "SYSTEM"

    fun setMapTheme(value: String) {
        sp.edit().putString(KEY_MAP_THEME, value).apply()
    }

    fun rides(): List<Ride> =
        sp.getString(KEY_RIDES, null)?.let { raw ->
            runCatching { json.decodeFromString<List<Ride>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    fun setRides(rides: List<Ride>) {
        sp.edit().putString(KEY_RIDES, json.encodeToString(rides)).apply()
    }

    private companion object {
        const val KEY_SAVED = "saved_trail_ids"
        const val KEY_MAP_THEME = "map_theme"
        const val KEY_RIDES = "rides"
    }
}
