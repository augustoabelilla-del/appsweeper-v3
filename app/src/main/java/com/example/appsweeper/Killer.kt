package com.example.appsweeper

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

data class AppItem(
    val pkg: String,
    val label: String,
    val isSystem: Boolean
)

data class RamInfo(val usedBytes: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes == 0L) 0 else ((usedBytes * 100) / totalBytes).toInt()
    val freeBytes: Long get() = totalBytes - usedBytes
}

object Killer {

    /** Packages that must never be touched, whatever the user picks. */
    private val alwaysProtected = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.server.telecom",
        "com.google.android.gms",
        "com.google.android.gsf"
    )

    fun ram(ctx: Context): RamInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamInfo(mi.totalMem - mi.availMem, mi.totalMem)
    }

    private fun protectedPackages(ctx: Context): Set<String> {
        val set = alwaysProtected.toMutableSet()
        set += ctx.packageName

        // Current home launcher
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let { set += it }

        // Current keyboard
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { set += it }
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.forEach { set += it.packageName }

        return set
    }

    /** Apps that are currently "launched" i.e. not in the stopped state. */
    fun launchedApps(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val protectedSet = protectedPackages(ctx)
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_STOPPED) == 0 }
            .filter { it.packageName !in protectedSet }
            .map {
                AppItem(
                    pkg = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Close one app. Returns true if the attempt was made without error. */
    fun kill(ctx: Context, pkg: String, useRoot: Boolean): Boolean {
        if (pkg in protectedPackages(ctx)) return false
        return try {
            if (useRoot) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg"))
                p.waitFor() == 0
            } else {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(pkg)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    data class SweepResult(val attempted: Int, val failed: List<AppItem>)

    /** Run a full sweep using the saved settings. Used by both the button and the alarms. */
    fun sweep(ctx: Context, fromUi: Boolean = false): SweepResult {
        val s = Store(ctx)
        val exceptions = s.exceptions()
        val custom = s.customList()
        val targets = launchedApps(ctx).filter { app ->
            val typeOk = (app.isSystem && s.includeSystem) || (!app.isSystem && s.includeUser)
            val listOk = if (s.useCustomList) app.pkg in custom else true
            typeOk && listOk && app.pkg !in exceptions
        }
        s.lastRun = System.currentTimeMillis()
        s.lastRunCount = targets.size
        s.lastNote = ""

        // True force-stop through the Accessibility service (runs in the background, reports later)
        if (s.useAccessibility && targets.isNotEmpty() &&
            ForceStopService.enqueue(targets.map { it.pkg }, fromUi)
        ) {
            s.setFailed(emptySet())
            return SweepResult(targets.size, emptyList())
        }

        val failed = mutableListOf<AppItem>()
        targets.forEach { if (!kill(ctx, it.pkg, s.useRoot)) failed += it }
        s.setFailed(failed.map { it.pkg }.toSet())
        if (s.useAccessibility && !ForceStopService.isRunning()) {
            s.lastNote = "Accessibility is switched on here but the service is off, so only background processes were closed."
        }
        return SweepResult(targets.size, failed)
    }
}
