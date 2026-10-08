# Shake Lock

Android app for when you catch yourself doomscrolling: shake the phone while in a selected app (Instagram, TikTok, …) and you're locked out.

- **Lock the selected apps** (1–30 min) or **the whole phone** (1–3 min, phone app stays usable)
- Lock screen shows how long you'd been scrolling, plus a "Do this instead" app (e.g. To-Dodo)
- **Learn Spanish while locked:** multiple-choice flash cards with spaced repetition; optionally the price for unlocking early
- Smart locks, each optional: escalating locks, keep shaking = longer lock, limited comeback, early unlock with friction, scroll nudge
- **Friends & nukes:** add friends by code, catch them doomscrolling and nuke them, a missile flies over their app, the cloud clears and their blocked apps are locked for 30 s. Live only, 3 per friend per day.
- Stats: escapes, time in blocked apps per day, time until you went back, cards learned

## How it works

An `AccessibilityService` (`LockService`) watches which app window has focus. While a blocked app is in front it listens to the accelerometer (`ShakeDetector`); a shake starts a lock and puts `BlockedActivity` on top. Usage is logged to `events.jsonl` for the stats.

Friends & nukes run on Supabase (anonymous sign-in, Postgres + Realtime). The service keeps a realtime subscription for nukes aimed at you; `NukeActivity` is a see-through window so the missile flies over the app you're in.

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

### Your own Supabase (for friends & nukes)

Without it everything else works; the Friends tab just says there's no server.

1. Create a Supabase project, run [`supabase/001-friends-and-nukes.sql`](supabase/001-friends-and-nukes.sql) in the SQL editor.
2. Authentication → Sign In / Providers → enable **anonymous sign-ins**.
3. Add to `local.properties` (not committed):
   ```
   supabase.url=https://<project>.supabase.co
   supabase.key=<publishable / anon key>
   ```

Not on Google Play: Play doesn't allow accessibility services used this way.
