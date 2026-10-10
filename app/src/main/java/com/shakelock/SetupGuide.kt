package com.shakelock

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Everything needed to get Airlock set up, and shortcuts straight to the right settings pages. */
object Setup {
    /** Extra battery step for brands that freeze background apps on top of stock Android. */
    class BrandBattery(val brand: String, val path: String, val detail: String?)

    /** Null on Pixel and other phones close to stock Android. Menu names vary a bit per version. */
    val brandBattery: BrandBattery? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> BrandBattery("Xiaomi", "Battery saver → No restrictions", "And turn on Autostart.")
        "samsung" -> BrandBattery("Samsung", "Battery → Unrestricted", null)
        "oneplus", "oppo", "realme" -> BrandBattery(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, "Battery usage → Allow background activity", "And allow auto launch.")
        "huawei", "honor" -> BrandBattery(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, "Battery → App launch → Manage manually", "Turn everything on.")
        "vivo", "iqoo" -> BrandBattery("Vivo", "Battery → Allow high background power use", null)
        else -> null
    }

    fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, LockService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun batteryUnrestricted(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun canShowBubble(context: Context) = Settings.canDrawOverlays(context)

    /**
     * Android 13+ greys out the accessibility switch for apps installed from a file, until you
     * "Allow restricted settings" in App info. Null = can't tell on this phone.
     */
    fun restrictedSettings(context: Context): Boolean? = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow("android:access_restricted_settings", context.applicationInfo.uid, context.packageName)
        mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED
    }.getOrNull()

    /**
     * Opens the accessibility list (apps can't jump straight to their own switch, that needs a
     * system permission). Stock Android scrolls to and highlights Airlock thanks to the fragment args.
     */
    fun openAccessibility(context: Context) {
        val component = ComponentName(context, LockService::class.java).flattenToString()
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Bubble.show(
            context,
            "Tap Airlock (near the top, maybe under \"Downloaded apps\") and turn it on.",
            "Switch greyed out? Tap it once anyway, then come back to Airlock.",
        )
    }

    fun openAppInfo(context: Context, hint: String, detail: String? = null) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Bubble.show(context, hint, detail)
    }

    fun openBubblePermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun requestBattery(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Small card that floats over the Settings app and tells you what to tap (needs "Display over
 * other apps"; without it, nothing is shown). Removed when you come back to Airlock.
 */
object Bubble {
    private var view: View? = null

    fun show(context: Context, text: String, detail: String? = null) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        hide(app)
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), app.resources.displayMetrics).toInt() }

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF2221F5C.toInt())
            }
            elevation = dp(8).toFloat()
        }
        card.addView(ImageView(app).apply {
            setImageDrawable(app.packageManager.getApplicationIcon(app.packageName))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        texts.addView(TextView(app).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (detail != null) {
            texts.addView(TextView(app).apply {
                this.text = detail
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
            })
        }
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(app).apply {
            this.text = "✕"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 18f
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { hide(app) }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: taps outside the card still go to Settings.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(56)
            horizontalMargin = 0.04f
        }
        runCatching {
            app.getSystemService(WindowManager::class.java).addView(card, params)
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(250).start()
            view = card
        }
    }

    fun hide(context: Context) {
        val current = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(current) }
    }
}
