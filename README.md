# Shake Lock

Android app for when you catch yourself doomscrolling: shake the phone while in a selected app (Instagram, TikTok, …) and you're locked out.

- **Lock the selected apps** (1–30 min) or **the whole phone** (1–3 min, phone app stays usable)
- Lock screen shows how long you'd been scrolling, plus a "Do this instead" app (e.g. To-Dodo)
- Smart locks, each optional: escalating locks, harder shake = longer lock, limited comeback, early unlock with friction, scroll nudge
- Stats: escapes, time in blocked apps per day, time until you went back

## How it works

An `AccessibilityService` (`LockService`) watches which app window has focus. While a blocked app is in front it listens to the accelerometer (`ShakeDetector`); a shake starts a lock and puts `BlockedActivity` on top. Usage is logged to `events.jsonl` for the stats.

## Build & install

Needs the Android SDK and a JDK (Android Studio's bundled one works):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then in the app: pick the apps to block and turn on the Shake Lock accessibility service.

**Xiaomi / HyperOS:** set App settings → Battery saver → **No restrictions** (and enable Autostart), otherwise the system freezes the service and locks stop working.
**Android 13+ sideloading:** if the accessibility switch is greyed out, go to App info → ⋮ → *Allow restricted settings*.

Not on Google Play: Play doesn't allow accessibility services used this way.
