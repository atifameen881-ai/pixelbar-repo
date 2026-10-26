package dev.anom.pixelbar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StatusMonitor(context: Context) {
    private val ctx = context.applicationContext
    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val wm = ctx.getSystemService(WifiManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val exec = ContextCompat.getMainExecutor(ctx)

    private var started = false
    private var telRegistered = false
    private var cellLevel = 0
    private var inService = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    private val wifiCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiInfo ?: return
            val lvl = wm.calculateSignalLevel(info.rssi).coerceIn(0, 4)
            _state.update { it.copy(wifi = lvl) }
        }
        override fun onLost(network: Network) {
            _state.update { it.copy(wifi = -1) }
        }
    }

    private inner class TelCb : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            cellLevel = signalStrength.level
            pushCell()
        }
        override fun onServiceStateChanged(serviceState: ServiceState) {
            inService = serviceState.state == ServiceState.STATE_IN_SERVICE
            pushCell()
        }
    }
    private val telCb = TelCb()

    private fun pushCell() {
        val v = if (inService) cellLevel.coerceIn(0, 4) else -1
        _state.update { it.copy(cell = v) }
    }

    private fun ensureTelephony() {
        if (telRegistered) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            tm.registerTelephonyCallback(exec, telCb)
            telRegistered = true
        } catch (_: SecurityException) { }
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCb
            )
        } catch (_: Exception) { }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) { }
        try { cm.unregisterNetworkCallback(wifiCb) } catch (_: Exception) { }
        if (telRegistered) {
            try { tm.unregisterTelephonyCallback(telCb) } catch (_: Exception) { }
            telRegistered = false
        }
    }

    /** Call after READ_PHONE_STATE is granted; also retried on every refresh. */
    fun refresh() {
        ensureTelephony()
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val f = nm.currentInterruptionFilter
        _state.update {
            it.copy(
                now = System.currentTimeMillis(),
                battery = if (level >= 0 && scale > 0) level * 100 / scale else it.battery,
                charging = plugged != 0,
                saver = pm.isPowerSaveMode,
                alarm = am.nextAlarmClock != null,
                dnd = f != NotificationManager.INTERRUPTION_FILTER_ALL &&
                      f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            )
        }
    }
}
