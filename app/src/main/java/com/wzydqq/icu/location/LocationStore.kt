package com.wzydqq.icu.location

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

object LocationStore {
    private const val PREFS_NAME = "location_prefs"
    private const val KEY_LOCATION = "selected_location"
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, location: SelectedLocation) {
        prefs(context).edit()
            .putString(KEY_LOCATION, gson.toJson(location))
            .apply()
    }

    fun get(context: Context): SelectedLocation? {
        val json = prefs(context).getString(KEY_LOCATION, null) ?: return null
        return try {
            gson.fromJson(json, SelectedLocation::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LOCATION).apply()
    }

    fun hasLocation(context: Context): Boolean = get(context) != null
}
