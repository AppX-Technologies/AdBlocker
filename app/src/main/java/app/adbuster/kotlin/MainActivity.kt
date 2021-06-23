package app.adbuster.kotlin

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.adbuster.R
import app.adbuster.java.HelpActivity
import app.adbuster.java.SettingsActivity
import app.adbuster.java.SharedPreManager
import app.adbuster.java.UIHelper
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
        initUI()

    }

    private fun initUI() {
        UIHelper.allApps(this);
        iv_drawer.setOnClickListener {
            if (drawer.isDrawerOpen(nav_menu)) {
                drawer.closeDrawers()
            } else {
                drawer.openDrawer(nav_menu)
            }
        }

        nav_menu.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.rate -> {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + applicationContext.packageName)
                    )
                    startActivity(intent)
                }
                R.id.share -> {
                    val sendIntent = Intent()
                    sendIntent.action = Intent.ACTION_SEND
                    sendIntent.putExtra(
                        Intent.EXTRA_TEXT,
                        "Download this amazing ad blocker App from the store: https://play.google.com/store/apps/details?id=" + applicationContext.packageName
                    )
                    sendIntent.type = "text/plain"
                    startActivity(sendIntent)
                }
                R.id.settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
                R.id.help -> {
                    val intent = Intent(this, HelpActivity::class.java)
                    startActivity(intent)
                }

            }
            drawer.closeDrawers()
            true
        }


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