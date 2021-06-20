package app.adbuster.kotlin

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Message
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import androidx.annotation.RequiresApi
import app.adbuster.R
import app.adbuster.java.SharedPreManager

enum class Command {
    START, STOP
}

class VpnNetworkException(msg: String) : RuntimeException(msg)

const val VPN_STATUS_STARTING = 0
const val VPN_STATUS_RUNNING = 1
const val VPN_STATUS_STOPPING = 2
const val VPN_STATUS_WAITING_FOR_NETWORK = 3
const val VPN_STATUS_RECONNECTING = 4
const val VPN_STATUS_RECONNECTING_NETWORK_ERROR = 5
const val VPN_STATUS_STOPPED = 6

fun vpnStatusToTextId(status: Int): Int = when (status) {
    VPN_STATUS_STARTING -> R.string.notification_starting
    VPN_STATUS_RUNNING -> R.string.notification_running
    VPN_STATUS_STOPPING -> R.string.notification_stopping
    VPN_STATUS_WAITING_FOR_NETWORK -> R.string.notification_waiting_for_net
    VPN_STATUS_RECONNECTING -> R.string.notification_reconnecting
    VPN_STATUS_RECONNECTING_NETWORK_ERROR -> R.string.notification_reconnecting_error
    VPN_STATUS_STOPPED -> R.string.notification_stopped
    else -> throw IllegalArgumentException("Invalid vpnStatus value ($status)")
}

fun vpnStatusToColor(status: Int): Int = when (status) {
    VPN_STATUS_STARTING -> R.color.colorSuccessLight
    VPN_STATUS_RUNNING -> R.color.colorSuccessDark
    VPN_STATUS_STOPPING -> R.color.colorError
    VPN_STATUS_STOPPED -> R.color.colorErrorDark
    else -> R.color.colorMaleBlue
}

const val VPN_MSG_STATUS_UPDATE = 0
const val VPN_MSG_NETWORK_CHANGED = 1

const val VPN_UPDATE_STATUS_INTENT = "app.adbuster.VPN_UPDATE_STATUS"
const val VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS"

const val MIN_RETRY_TIME = 5
const val MAX_RETRY_TIME = 2 * 60

fun checkStartVpnOnBoot(context: Context) {
    Log.i("BOOT", "Checking whether to start ad buster on boot")

    val sharedPreManager = SharedPreManager(context);

    if (!sharedPreManager.getBoolean(context.getString(R.string.vpn_enabled_key), false)) {
        return
    }

    if (VpnService.prepare(context) != null) {
        Log.i("BOOT", "VPN preparation not confirmed by user, changing enabled to false")
        sharedPreManager.putBoolean(context.getString(R.string.vpn_enabled_key), false)
    }

    Log.i("BOOT", "Starting ad buster from boot")

    val intent = Intent(context, AdVpnService::class.java)
    intent.putExtra("COMMAND", Command.START.ordinal)
    intent.putExtra(
        "NOTIFICATION_INTENT",
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java), 0
        )
    )
    context.startService(intent)

}

class AdVpnService : VpnService() {
    companion object {
        private val TAG = "VpnService"

        // TODO: Temporary Hack til refactor is done
        var vpnStatus: Int = VPN_STATUS_STOPPED
    }

    // TODO: There must be a better way in kotlin to do this
    private val commandValue = mapOf(
        Pair(Command.START.ordinal, Command.START),
        Pair(Command.STOP.ordinal, Command.STOP)
    )

    private var handler: Handler = Handler() {
        handleMessage(it)
    }
    private var vpnThread: AdVpnThread = AdVpnThread(this) {
        handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, it, 0))
    }
    private var connectivityChangedReceiver = broadcastReceiver() { context, intent ->
        handler.sendMessage(handler.obtainMessage(VPN_MSG_NETWORK_CHANGED, intent))
    }

    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel("ad_blocker", "Ad blocker")
            } else {
                ""
            }
        notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setPriority(Notification.PRIORITY_MIN)
    }


    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (commandValue[intent.getIntExtra("COMMAND", Command.START.ordinal)]) {
            Command.START -> intent.getParcelableExtra<PendingIntent>("NOTIFICATION_INTENT")
                ?.let { startVpn(it) }
            Command.STOP -> stopVpn()
        }

        return Service.START_STICKY
    }

    private fun updateVpnStatus(status: Int) {
        vpnStatus = status
        val notificationTextId = vpnStatusToTextId(status)
        notificationBuilder.setContentText(getString(notificationTextId))


        startForeground(10, notificationBuilder.build())

        val intent = Intent(VPN_UPDATE_STATUS_INTENT)
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startVpn(notificationIntent: PendingIntent) {
        // TODO: Should this be in the activity instead?
        val edit_pref =
            getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        edit_pref.putBoolean(getString(R.string.vpn_enabled_key), true)
        edit_pref.apply()

        notificationBuilder.setContentTitle(getString(R.string.notification_title))
        notificationBuilder.setContentIntent(notificationIntent)
        updateVpnStatus(VPN_STATUS_STARTING)

        registerReceiver(
            connectivityChangedReceiver,
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        )

        restartVpnThread()
    }

    private fun restartVpnThread() {
        vpnThread.stopThread()
        vpnThread.startThread()
    }

    private fun stopVpnThread() {
        vpnThread.stopThread()
    }

    private fun waitForNetVpn() {
        stopVpnThread()
        updateVpnStatus(VPN_STATUS_WAITING_FOR_NETWORK)
    }

    private fun reconnect() {
        updateVpnStatus(VPN_STATUS_RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn() {
        // TODO: Should this be in the activity instead?
        val edit_pref =
            getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        edit_pref.putBoolean(getString(R.string.vpn_enabled_key), false)
        edit_pref.apply()

        Log.i(TAG, "Stopping Service")
        stopVpnThread()
        try {
            unregisterReceiver(connectivityChangedReceiver)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "Ignoring exception on unregistering receiver")
        }
        updateVpnStatus(VPN_STATUS_STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroyed, shutting down")
        stopVpn()
    }

    fun handleMessage(message: Message?): Boolean {
        if (message == null) {
            return true
        }

        when (message.what) {
            VPN_MSG_STATUS_UPDATE -> updateVpnStatus(message.arg1)
            VPN_MSG_NETWORK_CHANGED -> connectivityChanged(message.obj as Intent)
            else -> throw IllegalArgumentException("Invalid message with what = ${message.what}")
        }
        return true
    }

    fun connectivityChanged(intent: Intent) {
        if (intent.getIntExtra(
                ConnectivityManager.EXTRA_NETWORK_TYPE,
                0
            ) == ConnectivityManager.TYPE_VPN
        ) {
            Log.i(TAG, "Ignoring connectivity changed for our own network")
            return
        }

        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            Log.e(TAG, "Got bad intent on connectivity changed " + intent.action)
        }
        if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
            Log.i(TAG, "Connectivity changed to no connectivity, wait for a network")
            waitForNetVpn()
        } else {
            Log.i(TAG, "Network changed, try to reconnect")
            reconnect()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_NONE
        )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }
}
