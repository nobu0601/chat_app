package io.github.nobu0601.icocaautocharge.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.github.nobu0601.icocaautocharge.core.SecureLog

/** 設定の「Wi-Fi時のみ」「充電中のみ」を判定するための端末状態（指示書 §7）。 */
class DeviceConditions(private val context: Context) {

    /** 従量課金でないネットワークに繋がっているか（実質 Wi-Fi / Ethernet）。 */
    fun isUnmeteredNetwork(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        SecureLog.e("failed to read network state", e)
        false
    }

    fun isCharging(): Boolean = try {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) {
        SecureLog.e("failed to read battery state", e)
        false
    }
}
