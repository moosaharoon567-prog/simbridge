package com.simbridge

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadsetClient
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

const val PORT = 8765

fun prefs(c: Context) = c.getSharedPreferences("bridge", Context.MODE_PRIVATE)

fun localIps(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
        ni.inetAddresses.toList()
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .map { "${it.hostAddress}  (${ni.name})" }
    }
} catch (e: Exception) {
    emptyList()
}

fun ensureChannels(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel("status", "Bridge status", NotificationManager.IMPORTANCE_LOW)
    )
    val ring = NotificationChannel("ring", "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
    ring.setSound(
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    )
    ring.enableVibration(true)
    nm.createNotificationChannel(ring)
    nm.createNotificationChannel(
        NotificationChannel("sms", "Messages", NotificationManager.IMPORTANCE_HIGH)
    )
}

fun nb(c: Context, ch: String): Notification.Builder =
    Notification.Builder(c, ch).setSmallIcon(android.R.drawable.sym_call_incoming)

fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
    c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
)

@Suppress("DEPRECATION")
fun actionOf(c: Context, label: String, act: String): Notification.Action {
    val pi = PendingIntent.getBroadcast(
        c, act.hashCode(),
        Intent(c, ActionReceiver::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE
    )
    return Notification.Action.Builder(0, label, pi).build()
}

fun startFg(s: Service, n: Notification) {
    if (Build.VERSION.SDK_INT >= 34) {
        s.startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        s.startForeground(1, n)
    }
}

// ───────────────────────── SIM PHONE SIDE (A07) ─────────────────────────

class HostService : Service() {
    companion object {
        @Volatile var instance: HostService? = null
    }

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<HClient>()
    private val io = Executors.newSingleThreadExecutor()
    private var wl: PowerManager.WakeLock? = null
    private var pin: String = ""
    private var state = "idle"
    private var number = ""
    private var name = ""

    inner class HClient(val sock: Socket) {
        private val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
        @Volatile var authed = false

        fun send(o: JSONObject) {
            try {
                synchronized(this) {
                    w.write(o.toString())
                    w.write("\n")
                    w.flush()
                }
            } catch (e: Exception) {
                close()
            }
        }

        fun close() {
            try { sock.close() } catch (_: Exception) {}
            clients.remove(this)
        }

        fun loop() {
            try {
                sock.soTimeout = 45000
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    try {
                        handle(JSONObject(line))
                    } catch (e: SecurityException) {
                        send(JSONObject().put("ev", "error").put("msg", "Permission missing on SIM phone"))
                    } catch (e: Exception) {
                        // ignore bad command
                    }
                }
            } catch (e: Exception) {
            } finally {
                close()
            }
        }

        private fun handle(j: JSONObject) {
            val cmd = j.optString("cmd")
            if (!authed) {
                if (cmd == "hello" && pin.isNotEmpty() && j.optString("pin") == pin) {
                    authed = true
                    send(JSONObject().put("ev", "hello"))
                    io.execute { send(callEvent()) }
                } else {
                    send(JSONObject().put("ev", "denied"))
                    close()
                }
                return
            }
            val tm = getSystemService(TelecomManager::class.java)
            when (cmd) {
                "ping" -> send(JSONObject().put("ev", "pong"))
                "answer" -> tm.acceptRingingCall()
                "reject", "hangup" -> tm.endCall()
                "dial" -> {
                    val n = j.optString("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (n.isNotEmpty()) {
                        io.execute { number = n; name = lookupName(n) }
                        tm.placeCall(Uri.fromParts("tel", n, null), Bundle())
                    }
                }
                "sms" -> {
                    val to = j.optString("to")
                    val body = j.optString("body")
                    if (to.isNotEmpty() && body.isNotEmpty()) {
                        val sm = smsMgr()
                        sm.sendMultipartTextMessage(to, null, sm.divideMessage(body), null, null)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsMgr(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun callEvent(): JSONObject =
        JSONObject().put("ev", "call").put("state", state).put("number", number).put("name", name)

    private fun broadcast(o: JSONObject) {
        clients.filter { it.authed }.forEach { it.send(o) }
    }

    private fun lookupName(n: String): String = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
    } catch (e: Exception) {
        ""
    }

    fun onPhoneState(st: String?, num: String?) {
        io.execute {
            val s = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                TelephonyManager.EXTRA_STATE_OFFHOOK -> "active"
                else -> "idle"
            }
            var changed = s != state
            if (s == "idle") {
                if (number.isNotEmpty() || name.isNotEmpty()) changed = true
                number = ""
                name = ""
            } else if (!num.isNullOrEmpty() && num != number) {
                number = num
                name = lookupName(num)
                changed = true
            }
            state = s
            if (changed) broadcast(callEvent())
        }
    }

    fun onSms(from: String, body: String) {
        io.execute {
            broadcast(
                JSONObject().put("ev", "sms").put("from", from)
                    .put("name", lookupName(from)).put("body", body)
            )
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        instance = this
        pin = prefs(this).getString("pin", "") ?: ""
        ensureChannels(this)
        val n = nb(this, "status")
            .setContentTitle("SIM bridge running")
            .setContentText("Relaying calls and texts to your other phone")
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()
        startFg(this, n)
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simbridge:host")
            wl?.acquire()
        }
        if (server == null) {
            Thread {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(PORT))
                    server = ss
                    while (true) {
                        val s = ss.accept()
                        val c = HClient(s)
                        clients.add(c)
                        Thread { c.loop() }.start()
                    }
                } catch (e: Exception) {
                }
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { it.close() }
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        HostService.instance?.onPhoneState(
            i.getStringExtra(TelephonyManager.EXTRA_STATE),
            i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        )
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        HostService.instance?.onSms(from, body)
    }
}

// ───────────────────────── REMOTE PHONE SIDE (S24) ─────────────────────────

object ClientState {
    @Volatile var status = "Not started"
    @Volatile var call = "idle"
    @Volatile var number = ""
    @Volatile var name = ""
    val sms = CopyOnWriteArrayList<String>()
    @Volatile var btStatus = "Not connected"
}

object BluetoothAudioRelay {
    @Volatile private var headsetClient: BluetoothHeadsetClient? = null
    @Volatile private var a07Device: BluetoothDevice? = null
    private var profileListener: BluetoothProfile.ServiceListener? = null

    fun init(c: Context, a07Mac: String) {
        try {
            val ba = BluetoothAdapter.getDefaultAdapter() ?: return
            a07Device = ba.getRemoteDevice(a07Mac)
            
            profileListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET_CLIENT) {
                        headsetClient = proxy as BluetoothHeadsetClient
                        ClientState.btStatus = "Ready to connect"
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET_CLIENT) {
                        headsetClient = null
                        ClientState.btStatus = "Disconnected"
                    }
                }
            }
            
            ba.getProfileProxy(c, profileListener!!, BluetoothProfile.HEADSET_CLIENT)
            ClientState.btStatus = "Initializing..."
        } catch (e: Exception) {
            ClientState.btStatus = "Error: ${e.message}"
        }
    }

    fun connect() {
        try {
            val dev = a07Device ?: run { ClientState.btStatus = "No A07 MAC set"; return }
            val hc = headsetClient ?: run { ClientState.btStatus = "Headset client not ready"; return }
            
            if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                ClientState.btStatus = "Bonding with A07..."
                dev.createBond()
            }
            
            hc.connect(dev)
            ClientState.btStatus = "Connecting..."
        } catch (e: SecurityException) {
            ClientState.btStatus = "Permission denied"
        } catch (e: Exception) {
            ClientState.btStatus = "Error: ${e.message}"
        }
    }

    fun disconnect() {
        try {
            val dev = a07Device ?: return
            val hc = headsetClient ?: return
            hc.disconnect(dev)
            ClientState.btStatus = "Disconnected"
        } catch (e: Exception) {
            ClientState.btStatus = "Error: ${e.message}"
        }
    }

    fun isConnected(): Boolean = headsetClient?.getConnectionState(a07Device) == BluetoothProfile.STATE_CONNECTED
}

class ClientService : Service() {
    companion object {
        @Volatile var instance: ClientService? = null
        @Volatile private var writer: BufferedWriter? = null
        private val io = Executors.newSingleThreadExecutor()

        fun send(o: JSONObject) {
            io.execute {
                try {
                    val w = writer ?: return@execute
                    synchronized(w) {
                        w.write(o.toString())
                        w.write("\n")
                        w.flush()
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    @Volatile private var running = false
    private var sock: Socket? = null
    private var wl: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun gateway(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val g = wm.dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        return "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
    }

    private fun connectLoop() {
        while (running) {
            val p = prefs(this)
            val pin = p.getString("pin", "") ?: ""
            val saved = p.getString("hostIp", "")?.trim().orEmpty()
            val candidates = listOfNotNull(saved.ifEmpty { null }, gateway()).distinct()
            if (candidates.isEmpty()) ClientState.status = "No Wi-Fi / IP to try"
            for (ip in candidates) {
                if (!running) break
                try {
                    ClientState.status = "Connecting to $ip ..."
                    val s = Socket()
                    s.connect(InetSocketAddress(ip, PORT), 4000)
                    s.soTimeout = 10000
                    sock = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    send(JSONObject().put("cmd", "hello").put("pin", pin))
                    var lastRx = System.currentTimeMillis()
                    while (running) {
                        var line: String? = null
                        try {
                            line = r.readLine()
                        } catch (e: SocketTimeoutException) {
                            if (System.currentTimeMillis() - lastRx > 40000) break
                            send(JSONObject().put("cmd", "ping"))
                            continue
                        }
                        if (line == null) break
                        lastRx = System.currentTimeMillis()
                        handle(JSONObject(line))
                    }
                } catch (e: Exception) {
                } finally {
                    writer = null
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
            if (ClientState.status != "Wrong PIN") ClientState.status = "Disconnected - retrying"
            ClientState.call = "idle"
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(2)
            nm.cancel(3)
            try {
