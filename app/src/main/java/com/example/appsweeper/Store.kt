package com.example.appsweeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A scheduled sweep: either at a fixed time of day, or every N minutes. */
data class Schedule(
    val id: Int,
    val intervalMode: Boolean,
    val hour: Int = 3,
    val minute: Int = 0,
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true
) {
    fun label(): String =
        if (intervalMode) {
            if (intervalMinutes % 60 == 0) "Every ${intervalMinutes / 60} h" else "Every $intervalMinutes min"
        } else {
            "Daily at %02d:%02d".format(hour, minute)
        }
}

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("sweeper", Context.MODE_PRIVATE)

    var includeUser: Boolean
        get() = p.getBoolean("user", true)
        set(v) = p.edit().putBoolean("user", v).apply()

    var includeSystem: Boolean
        get() = p.getBoolean("system", false) // off by default, safer
        set(v) = p.edit().putBoolean("system", v).apply()

    var useCustomList: Boolean
        get() = p.getBoolean("custom", false)
        set(v) = p.edit().putBoolean("custom", v).apply()

    var useRoot: Boolean
        get() = p.getBoolean("root", false)
        set(v) = p.edit().putBoolean("root", v).apply()

    var useAccessibility: Boolean
        get() = p.getBoolean("access", false)
        set(v) = p.edit().putBoolean("access", v).apply()

    var hideScreens: Boolean
        get() = p.getBoolean("hide", true)
        set(v) = p.edit().putBoolean("hide", v).apply()

    var lastNote: String
        get() = p.getString("note", "") ?: ""
        set(v) = p.edit().putString("note", v).apply()

    fun failedPackages(): Set<String> = p.getStringSet("failed", emptySet()) ?: emptySet()
    fun setFailed(v: Set<String>) = p.edit().putStringSet("failed", v.toSet()).apply()

    var lastRun: Long
        get() = p.getLong("lastRun", 0L)
        set(v) = p.edit().putLong("lastRun", v).apply()

    var lastRunCount: Int
        get() = p.getInt("lastCount", 0)
        set(v) = p.edit().putInt("lastCount", v).apply()

    private fun getSet(key: String): Set<String> = p.getStringSet(key, emptySet()) ?: emptySet()
    private fun putSet(key: String, v: Set<String>) = p.edit().putStringSet(key, v.toSet()).apply()

    fun exceptions(): Set<String> = getSet("exceptions")
    fun setExceptions(v: Set<String>) = putSet("exceptions", v)

    fun customList(): Set<String> = getSet("customList")
    fun setCustomList(v: Set<String>) = putSet("customList", v)

    fun schedules(): List<Schedule> {
        val raw = p.getString("schedules", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Schedule(
                id = o.getInt("id"),
                intervalMode = o.getBoolean("interval"),
                hour = o.getInt("h"),
                minute = o.getInt("m"),
                intervalMinutes = o.getInt("every"),
                enabled = o.getBoolean("on")
            )
        }
    }

    fun saveSchedules(list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("interval", it.intervalMode)
                    .put("h", it.hour)
                    .put("m", it.minute)
                    .put("every", it.intervalMinutes)
                    .put("on", it.enabled)
            )
        }
        p.edit().putString("schedules", arr.toString()).apply()
    }

    fun nextScheduleId(): Int = (schedules().maxOfOrNull { it.id } ?: 0) + 1
}
