package com.ghaly.findme.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Snapshots a few basic, non-sensitive facts about the device and its
 * current connection — phone model, Android version, the app's own version,
 * and whether it's on WiFi or mobile data (and which network name, when the
 * OS exposes one) — synced alongside location so the admin dashboard has
 * enough context to tell devices apart. Nothing here goes beyond what the
 * phone's own status bar already shows the person holding it.
 */
object DeviceInfoCollector {

    data class Snapshot(
        val deviceModel: String,
        val androidVersion: String,
        val appVersionName: String,
        val networkType: String,
        val networkName: String?
    )

    fun collect(context: Context): Snapshot {
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidVersion = "Android ${Build.VERSION.RELEASE}"
        val appVersionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

        var networkType = "none"
        var networkName: String? = null
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            when {
                capabilities == null -> networkType = "none"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    networkType = "wifi"
                    networkName = try {
                        val wifiManager = context.applicationContext
                            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val ssid = wifiManager?.connectionInfo?.ssid?.trim('"')
                        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") null else ssid
                    } catch (e: Exception) {
                        null
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    networkType = "cellular"
                    networkName = try {
                        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                        telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        null
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> networkType = "bluetooth"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> networkType = "ethernet"
                else -> networkType = "other"
            }
        } catch (e: Exception) {
            // Leave the "none"/null defaults — this is a nice-to-have, never
            // worth crashing or blocking anything else over.
        }

        return Snapshot(deviceModel, androidVersion, appVersionName, networkType, networkName)
    }
}
