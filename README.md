# App Sweeper

A KillApps-style Android app with a built-in scheduler. Written in Kotlin + Jetpack Compose.

## Features
- Live RAM gauge, free memory and count of launched apps
- **Run now** button to close apps immediately
- **User apps / System apps** toggles
- **Schedules**: run daily at a set time, or every 15 min / 30 min / 1 h / 2 h / 6 h / 12 h. Multiple schedules, each can be switched on/off. They survive reboots.
- **Protect** button per app (exception list) and an optional **custom list** mode
- **Closing failure** list for apps that refused to close
- Safety net: the launcher, keyboard, phone/telecom, SystemUI, Google Play services and this app itself are never touched

## Build
1. Install Android Studio (Koala or newer).
2. File > Open > select the `AppSweeper` folder and let Gradle sync.
3. Plug in your phone (USB debugging on) and press Run, or Build > Build APK(s).

## Important limits (Android rules, not app bugs)
- **Without root**, the app uses `killBackgroundProcesses()`. That ends *background* processes only. Many apps (and most system apps) restart themselves or refuse. Those land in the "Closing failure" list.
- **With root**, turn on "Use root" and it runs `am force-stop <package>`, which is a true force-stop.
- Android blocks normal apps from force-stopping others; `FORCE_STOP_PACKAGES` is signature-only. If you don't want root, the other route is Shizuku (not included here), which can be added later.
- Battery optimisation: if schedules don't fire, set App Sweeper to "Unrestricted" under Settings > Apps > App Sweeper > Battery.
- Exact alarms: on Android 12+ allow "Alarms & reminders" for the app (the app shows a button for this).
- System apps are **off by default** because killing them can cause odd behaviour and extra battery drain as they restart.
- This build will not pass Google Play review as-is (QUERY_ALL_PACKAGES, exact alarms). It's meant for personal sideloading.

## Accessibility force-stop (added)
Turn on **Accessibility force-stop** in the app, then switch on **App Sweeper** under Settings > Accessibility.
A sweep then opens each app's info page in Settings and taps Force stop > OK (`ForceStopService.kt`).
- It only taps when the page shows the name of the app being closed.
- Screen must be on and unlocked. Scheduled runs wake the screen but cannot bypass a lock screen.
- On Android 13+ a sideloaded app's Accessibility switch may be greyed out: Settings > Apps > App Sweeper > three dots > Allow restricted settings.
- On Xiaomi/Oppo/Vivo/Huawei also allow "Display pop-up windows while running in the background" and Autostart.
