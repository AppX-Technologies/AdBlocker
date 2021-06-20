package app.adbuster.kotlin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.adbuster.java.SharedPreManager
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.xbill.DNS.ARecord
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun getDnsServers(context: Context): InetAddress {
    val cm = context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Seriously, Android? Seriously?
    val activeInfo = cm.activeNetworkInfo ?: throw VpnNetworkException("No DNS Server")
    val servers = cm.allNetworks.filter {
        val ni = cm.getNetworkInfo(it)
        ni != null && ni.isConnected && ni.type == activeInfo.type && ni.subtype == activeInfo.subtype
    }.elementAtOrNull(0)?.let {
        cm.getLinkProperties(it)?.dnsServers
    }
    val dnsServer = servers?.first() ?: throw VpnNetworkException("No DNS Server")
    return dnsServer
}

class AdVpnThread(vpnService: VpnService, notify: ((Int) -> Unit)?) : Runnable {
    companion object {
        const val TAG = "AdVpnThread"
    }

    private var notify: ((Int) -> Unit)? = notify
    private var vpnService = vpnService
    private var dnsServer: InetAddress? = null
    private var vpnFileDescriptor: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private var interruptible: InterruptibleFileInputStream? = null
    private var blockedHosts: Set<String>? = null
    private var sharedPreManager: SharedPreManager? = null

    fun startThread() {
        Log.i(TAG, "Starting Vpn Thread")
        thread = Thread(this, "AdBusterVpnThread").apply { start() }
        Log.i(TAG, "Vpn Thread started")
    }

    fun stopThread() {
        Log.i(TAG, "Stopping Vpn Thread")
        thread?.interrupt()
        interruptible?.interrupt()
        thread?.join(2000)
        if (thread?.isAlive ?: false) {
            Log.w(TAG, "Couldn't kill Vpn Thread")
        }
        thread = null
        Log.i(TAG, "Vpn Thread stopped")

    }

    @Synchronized
    override fun run() {
        try {
            Log.i(TAG, "Starting")

            // Load the block list
            loadBlockedHosts()

            notify?.let { it(VPN_STATUS_STARTING) }
            notify?.let { it(VPN_STATUS_STARTING) }

            var retryTimeout = MIN_RETRY_TIME
            // Try connecting the vpn continuously
            while (true) {
                try {
                    // If the function returns, that means it was interrupted
                    runVpn()

                    Log.i(TAG, "Told to stop")
                    break
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: VpnNetworkException) {
                    // We want to filter out VpnNetworkException from out crash analytics as these
                    // are exceptions that we expect to happen from network errors
                    Log.w(TAG, "Network exception in vpn thread, ignoring and reconnecting", e)
                    // If an exception was thrown, show to the user and try again
                    notify?.let { it(VPN_STATUS_RECONNECTING_NETWORK_ERROR) }
                } catch (e: Exception) {
                    Log.e(TAG, "Network exception in vpn thread, reconnecting", e)
                    notify?.let { it(VPN_STATUS_RECONNECTING_NETWORK_ERROR) }
                }

                // ...wait and try again
                Log.i(TAG, "Retrying to connect in $retryTimeout seconds...")
                Thread.sleep(retryTimeout.toLong() * 1000)
                retryTimeout = if (retryTimeout < MAX_RETRY_TIME) {
                    retryTimeout * 2
                } else {
                    retryTimeout
                }
            }

            Log.i(TAG, "Stopped")
        } catch (e: InterruptedException) {
            Log.i(TAG, "Vpn Thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in run() ", e)
        } finally {
            notify?.let { it(VPN_STATUS_STOPPING) }
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun runVpn() {
        // Authenticate and configure the virtual network interface.
        val pfd = configure()
        vpnFileDescriptor = pfd

        // Packets to be sent are queued in this input stream.
        val inputStream = pfd?.fileDescriptor?.let { InterruptibleFileInputStream(it) }
        interruptible = inputStream

        // Allocate the buffer for a single packet.
        val packet = ByteArray(32767)

        // Like this `Executors.newCachedThreadPool()`, except with an upper limit
        val executor =
            ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

        try {
            // Now we are connected. Set the flag and show the message.
            notify?.let { it(VPN_STATUS_RUNNING) }

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                val length = try {
                    inputStream?.read(packet)
                } catch (e: InterruptibleFileInputStream.InterruptedStreamException) {
                    Log.i(TAG, "Told to stop VPN")
                    return
                }

                if (length == 0) {
                    // TODO: Possibly change to exception
                    Log.w(TAG, "Got empty packet!")
                }

                val readPacket = length?.let { packet.copyOfRange(0, it) }

                // Packets received need to be written to this output stream.
                val outFd = FileOutputStream(pfd?.fileDescriptor)

                // Packets to be sent to the real DNS server will need to be protected from the VPN
                val dnsSocket = DatagramSocket()
                vpnService.protect(dnsSocket)

                Log.i(
                    TAG, "Starting new thread to handle dns request" +
                            " (active = ${executor.activeCount} backlog = ${executor.queue.size})"
                )
                // Start a new thread to handle the DNS request
                try {
                    executor.execute {
                        if (readPacket != null) {
                            handleDnsRequest(readPacket, dnsSocket, outFd)
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    VpnNetworkException("High backlog in dns thread pool executor, network probably stalled")
                }
            }
        } finally {
            executor.shutdownNow()
            pfd?.close()
            vpnFileDescriptor = null
        }
    }

    private fun handleDnsRequest(
        packet: ByteArray,
        dnsSocket: DatagramSocket,
        outFd: FileOutputStream
    ) {
        try {
            val parsedPacket = IpV4Packet.newPacket(packet, 0, packet.size)

            if (parsedPacket.payload !is UdpPacket) {
                Log.i(TAG, "Ignoring unknown packet type ${parsedPacket.payload}")
                return
            }

            val dnsRawData = (parsedPacket.payload as UdpPacket).payload.rawData
            val dnsMsg = Message(dnsRawData)
            if (dnsMsg.question == null) {
                Log.i(TAG, "Ignoring DNS packet with no query $dnsMsg")
                return
            }
            val dnsQueryName = dnsMsg.question.name.toString(true)

            val response: ByteArray

            if (!blockedHosts!!.contains(dnsQueryName)) {
                Log.i(TAG, "DNS Name $dnsQueryName Allowed!")
                val outPacket = DatagramPacket(dnsRawData, 0, dnsRawData.size, dnsServer!!, 53)

                try {
                    dnsSocket.send(outPacket)
                } catch (e: ErrnoException) {
                    if ((e.errno == OsConstants.ENETUNREACH) || (e.errno == OsConstants.EPERM)) {
                        throw VpnNetworkException("Network unreachable, can't send DNS packet")
                    } else {
                        throw e
                    }
                }

                val datagramData = ByteArray(1024)
                val replyPacket = DatagramPacket(datagramData, datagramData.size)
                dnsSocket.receive(replyPacket)
                response = datagramData
            } else {
                Log.i(TAG, "DNS Name $dnsQueryName Blocked!")
                dnsMsg.header.setFlag(Flags.QR.toInt())
                dnsMsg.addRecord(
                    ARecord(
                        dnsMsg.question.name,
                        dnsMsg.question.dClass,
                        10.toLong(),
                        Inet4Address.getLocalHost()
                    ), Section.ANSWER
                )
                response = dnsMsg.toWire()
                updateSharedPreference(parsedPacket.rawData.size)
            }


            val udpOutPacket = parsedPacket.payload as UdpPacket
            val ipOutPacket = IpV4Packet.Builder(parsedPacket)
                .srcAddr(parsedPacket.header.dstAddr)
                .dstAddr(parsedPacket.header.srcAddr)
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(
                    UdpPacket.Builder(udpOutPacket)
                        .srcPort(udpOutPacket.header.dstPort)
                        .dstPort(udpOutPacket.header.srcPort)
                        .srcAddr(parsedPacket.header.dstAddr)
                        .dstAddr(parsedPacket.header.srcAddr)
                        .correctChecksumAtBuild(true)
                        .correctLengthAtBuild(true)
                        .payloadBuilder(
                            UnknownPacket.Builder()
                                .rawData(response)
                        )
                ).build()
            try {
                outFd.write(ipOutPacket.rawData)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EBADF) {
                    throw VpnNetworkException("Outgoing VPN socket closed")
                } else {
                    throw e
                }
            } catch (e: IOException) {
                // TODO: Make this more specific, only for: "File descriptor closed"
                throw VpnNetworkException("Outgoing VPN output stream closed")
            }
        } catch (e: VpnNetworkException) {
            Log.w(TAG, "Ignoring exception, stopping thread", e)
        } catch (e: Exception) {
            Log.e(TAG, "Got exception", e)
        } finally {
            dnsSocket.close()
            outFd.close()
        }

    }

    private fun updateSharedPreference(adSizeInByte: Int) {
        if (sharedPreManager == null) {
            sharedPreManager = SharedPreManager(vpnService);
        }

        val prevNum = sharedPreManager?.getInt("ad_blocked", 0);
        sharedPreManager?.putInt("ad_blocked", prevNum!! + 1)
        LocalBroadcastManager.getInstance(vpnService).sendBroadcast(Intent("ad_blocked_updated"))

        val prevSize = sharedPreManager?.getInt("ad_blocked_size", 0);
        sharedPreManager?.putInt("ad_blocked_size", prevSize!! + adSizeInByte)
        LocalBroadcastManager.getInstance(vpnService).sendBroadcast(Intent("ad_blocked_updated"))
    }

    private fun loadBlockedHosts() {
        // Don't load the hosts more than once (temporary til we have dynamic lists)
        if (blockedHosts != null) {
            Log.i(TAG, "Block list already loaded")
            return
        }

        Log.i(TAG, "Loading block list")
        val _blockedHosts: MutableSet<String> = mutableSetOf()

        for (fileName in listOf("adaway_hosts.txt", "ad_servers.txt")) {
            val reader = vpnService.assets.open(fileName)
            var count = 0
            try {
                InputStreamReader(reader.buffered()).forEachLine {
                    val s = it.removeSurrounding(" ")
                    if (s.length != 0 && s[0] != '#') {
                        val split = s.split(" ", "\t")
                        if (split.size == 2 && split[0] == "127.0.0.1") {
                            count += 1
                            _blockedHosts.add(split[1].toLowerCase())
                        }
                    }
                }
            } finally {
                reader.close()
            }

            Log.i(TAG, "From file $fileName loaded $count entires")
        }

        blockedHosts = _blockedHosts
        Log.i(TAG, "Loaded ${blockedHosts!!.size} blocked hosts")
    }

    @Throws(Exception::class)
    private fun configure(): ParcelFileDescriptor? {

        Log.i(TAG, "Configuring")

        // Get the current DNS servers before starting the VPN
        dnsServer = getDnsServers(vpnService as Context)
        Log.i(TAG, "Got DNS server = $dnsServer")

        // Configure a builder while parsing the parameters.
        // TODO: Make this dynamic
        val builder = vpnService.Builder()
        builder.addAddress("192.168.50.1", 24)
        builder.addDnsServer("192.168.50.5")
        builder.addRoute("192.168.50.0", 24)
        builder.setBlocking(true)

        // Create a new interface using the builder and save the parameters.
        val pfd = builder
            .setSession("Ad Buster")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    vpnService, 1, Intent(vpnService, MainActivity::class.java),
                    PendingIntent.FLAG_CANCEL_CURRENT
                )
            ).establish()
        Log.i(TAG, "Configured")
        return pfd
    }

}