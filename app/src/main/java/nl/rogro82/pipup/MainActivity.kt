package nl.rogro82.pipup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView

/**
 * Status screen. Mostly a diagnostics page now: the three things that silently
 * stop PiPup from working (no overlay permission, battery optimisation killing
 * the service, no network) are each reported here instead of being invisible.
 */
class MainActivity : Activity() {

    // Prompt at most once per visit; onResume runs again when the user returns
    // from (or backs out of) the settings screen, and re-launching there loops.
    private var promptedOverlay = false
    private var promptedBattery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ServiceStarter.scheduleWatchdog(this)
        ServiceStarter.start(this)

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val connection = findViewById<TextView>(R.id.textViewConnection)
        val serverAddress = findViewById<TextView>(R.id.textViewServerAddress)
        val warnings = findViewById<TextView>(R.id.textViewWarnings)

        when (val ipAddress = Utils.getIpAddress()) {
            is String -> {
                connection.setText(R.string.server_running)
                serverAddress.apply {
                    visibility = View.VISIBLE
                    text = resources.getString(
                        R.string.server_address, ipAddress, PiPupService.SERVER_PORT
                    )
                }
            }
            else -> {
                connection.setText(R.string.no_network_connection)
                serverAddress.visibility = View.INVISIBLE
            }
        }

        val issues = mutableListOf<String>()

        if (!canDrawOverlays()) {
            issues += getString(R.string.warning_overlay, packageName)
            requestOverlayPermission()
        }

        if (!isIgnoringBatteryOptimizations()) {
            issues += getString(R.string.warning_battery)
            requestBatteryOptimizationExemption()
        }

        warnings.apply {
            if (issues.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = issues.joinToString("\n\n")
            }
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /**
     * Most Android TV builds have no UI for this and the intent throws, which is
     * why the readme tells people to use adb. Try anyway -- newer Google TV
     * builds do have the screen -- and fall back to the on-screen instructions.
     */
    private fun requestOverlayPermission() {
        if (canDrawOverlays() || promptedOverlay) return
        promptedOverlay = true
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "no overlay permission screen on this device: ${ex.message}")
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (isIgnoringBatteryOptimizations() || promptedBattery) return
        promptedBattery = true
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "no battery optimisation screen on this device: ${ex.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "could not request notification permission: ${ex.message}")
        }
    }

    companion object {
        const val LOG_TAG = "PiPupMain"
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
