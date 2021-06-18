package app.adbuster

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*

fun vpnStatusToToggleLevel(status: Int): Int = when (status) {
    VPN_STATUS_STOPPED -> 0
    VPN_STATUS_RUNNING -> 2
    else -> 1
}

fun vpnStatusShouldStop(status: Int): Boolean = when (status) {
    VPN_STATUS_STOPPED -> false
    else -> true
}

class MainActivity : AppCompatActivity() {
    companion object {
        private val TAG = "MainActivity"
    }

    var vpnServiceBroadcastReceiver = broadcastReceiver() { context, intent ->
        val str_id = intent.getIntExtra(VPN_UPDATE_STATUS_EXTRA, R.string.notification_stopped)
        updateStatus(str_id)
    }

    var adBlockedBroadcastReceiver = broadcastReceiver() { context, intent ->
        updateAdBlockedData()
    }

    private fun updateAdBlockedData() {
        val adBlockedNum = sharedPreManager?.getInt("ad_blocked", 0);
        val adBlockedInBytes = sharedPreManager?.getInt("ad_blocked_size", 0);
        val adBlockedSizeInMB = adBlockedInBytes?.div(125000f);

        tv_ad_blocked.text = "Ads blocked\n " + adBlockedNum
        tv_data_saved.text = "Data saved\n " + String.format("%.2f", adBlockedSizeInMB) + " mb"
    }

    private var sharedPreManager: SharedPreManager? = null


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sharedPreManager = SharedPreManager(this)
        updateAdBlockedData()
        vpn_toggle.setOnClickListener {
            if (vpnStatusShouldStop(AdVpnService.vpnStatus)) {
                Log.i(TAG, "Attempting to disconnect")

                val intent = Intent(this, AdVpnService::class.java)
                intent.putExtra("COMMAND", Command.STOP.ordinal)
                startService(intent)
            } else {
                Log.i(TAG, "Attempting to connect")

                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 0)
                } else {
                    onActivityResult(0, RESULT_OK, null)
                }

            }
        }

    }


    private fun updateStatus(status: Int) {
        text_status.text = getString(vpnStatusToTextId(status))
        val level = vpnStatusToToggleLevel(status)
        vpn_toggle.setImageLevel(level)

        text_status.setTextColor(ContextCompat.getColor(this, vpnStatusToColor(status)))
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result == RESULT_OK) {
            val intent = Intent(this, AdVpnService::class.java)
            intent.putExtra("COMMAND", Command.START.ordinal)
            intent.putExtra(
                "NOTIFICATION_INTENT",
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java), 0
                )
            )
            startService(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnServiceBroadcastReceiver)
    }

    override fun onResume() {
        super.onResume()

        updateStatus(AdVpnService.vpnStatus)
        updateAdBlockedData()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(vpnServiceBroadcastReceiver, IntentFilter(VPN_UPDATE_STATUS_INTENT))
    }

}