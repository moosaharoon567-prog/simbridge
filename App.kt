package com.simbridge

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// ── Constants ────────────────────────────────────────────────────────────────
const val PORT_CTRL  = 8765   // TCP control channel
const val PORT_AUDIO_A2S = 8766 // UDP  A07 mic  → S24 speaker
const val PORT_AUDIO_S2A = 8767 // UDP  S24 mic  → A07 speaker

const val SAMPLE_RATE  = 16000
const val CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO
const val CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO
const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT

fun prefs(c: Context) = c.getSharedPreferences("bridge", Context.MODE_PRIVATE)

fun localIps(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
        ni.inetAddresses.toList()
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .map { "${it.hostAddress}  (${ni.name})" }
    }
} catch (e: Exception) { emptyList() }

fun ensureChannels(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
        NotificationChannel("status", "Bridge status", NotificationManager.IMPORTANCE_LOW))
    val ring = NotificationChannel("ring", "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
    ring.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    ring.enableVibration(true)
    nm.createNotificationChannel(ring)
    nm.createNotificationChannel(
        NotificationChannel("sms", "Messages", NotificationManager.IMPORTANCE_HIGH))
}

fun nb(c: Context, ch: String): Notification.Builder =
    Notification.Builder(c, ch).setSmallIcon(android.R.drawable.sym_call_incoming)

fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
    c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

@Suppress("DEPRECATION")
fun actionOf(c: Context, label: String, act: String): Notification.Action {
    val pi = PendingIntent.getBroadcast(c, act.hashCode(),
        Intent(c, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Action.Builder(0, label, pi).build()
}

fun startFg(s: Service, n: Notification) {
    if (Build.VERSION.SDK_INT >= 34)
        s.startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    else
        s.startForeground(1, n)
}

// ── Audio relay (runs on A07 when a call is active) ──────────────────────────
// A07 → S24: capture mic → send UDP packets to S24
// S24 → A07: receive UDP packets → play to speaker (caller hears S24 user)

object AudioRelay {
    @Volatile var running = false
    @Volatile var remoteIp: String? = null   // set by HostService when S24 connects

    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    private var rxSock: DatagramSocket? = null

    fun start(ctx: Context, ip: String) {
        if (running) return
        remoteIp = ip
        running = true

        val am = ctx.getSystemService(AudioManager::class.java)

        // TX: record from mic (VOICE_COMMUNICATION = AEC + noise suppression)
        txThread = Thread {
            val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize * 4)
            val sock = DatagramSocket()
            val buf = ByteArray(bufSize)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val pkt = DatagramPacket(buf, n,
                            java.net.InetAddress.getByName(ip), PORT_AUDIO_A2S)
                        sock.send(pkt)
                    }
                }
            } catch (_: Exception) {
            } finally {
                rec.stop(); rec.release(); sock.close()
            }
        }.also { it.start() }

        // RX: receive from S24, play to speaker so caller hears S24 user's voice
        rxThread = Thread {
            val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT).build())
                .setBufferSizeInBytes(bufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val sock = DatagramSocket(PORT_AUDIO_S2A)
            rxSock = sock
            val buf = ByteArray(bufSize)
            try {
                // Route audio to earpiece / speakerphone
                am?.mode = AudioManager.MODE_IN_COMMUNICATION
                track.play()
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    track.write(pkt.data, 0, pkt.length)
                }
            } catch (_: Exception) {
            } finally {
                track.stop(); track.release(); sock.close()
                am?.mode = AudioManager.MODE_NORMAL
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try { rxSock?.close() } catch (_: Exception) {}
        txThread?.interrupt(); rxThread?.interrupt()
        txThread = null; rxThread = null; rxSock = null
    }
}

// Audio relay client side (S24)
object AudioRelayClient {
    @Volatile var running = false

    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    private var rxSock: DatagramSocket? = null

    fun start(ctx: Context, hostIp: String) {
        if (running) return
        running = true

        // RX: receive A07 mic (caller voice) → play to S24 earpiece
        rxThread = Thread {
            val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT).build())
                .setBufferSizeInBytes(bufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val sock = DatagramSocket(PORT_AUDIO_A2S)
            rxSock = sock
            sock.soTimeout = 2000
            val buf = ByteArray(bufSize)
            val am = ctx.getSystemService(AudioManager::class.java)
            try {
                am?.mode = AudioManager.MODE_IN_COMMUNICATION
                track.play()
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        track.write(pkt.data, 0, pkt.length)
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (_: Exception) {
            } finally {
                track.stop(); track.release(); sock.close()
                am?.mode = AudioManager.MODE_NORMAL
            }
        }.also { it.start() }

        // TX: record S24 mic → send to A07 (so caller hears S24 user)
        txThread = Thread {
            val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize * 4)
            val sock = DatagramSocket()
            val buf = ByteArray(bufSize)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val pkt = DatagramPacket(buf, n,
                            java.net.InetAddress.getByName(hostIp), PORT_AUDIO_S2A)
                        sock.send(pkt)
                    }
                }
            } catch (_: Exception) {
            } finally {
                rec.stop(); rec.release(); sock.close()
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try { rxSock?.close() } catch (_: Exception) {}
        txThread?.interrupt(); rxThread?.interrupt()
        txThread = null; rxThread = null; rxSock = null
    }
}

// ── SIM PHONE SIDE (A07) ─────────────────────────────────────────────────────

class HostService : Service() {
    companion object { @Volatile var instance: HostService? = null }

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<HClient>()
    private val io = Executors.newSingleThreadExecutor()
    private var wl: PowerManager.WakeLock? = null
    private var pin = ""
    private var state = "idle"
    private var number = ""
    private var name = ""

    inner class HClient(val sock: Socket) {
        private val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
        @Volatile var authed = false
        val remoteIp: String = sock.inetAddress.hostAddress ?: ""

        fun send(o: JSONObject) {
            try { synchronized(this) { w.write(o.toString()); w.write("\n"); w.flush() } }
            catch (_: Exception) { close() }
        }

        fun close() {
            try { sock.close() } catch (_: Exception) {}
            clients.remove(this)
            // stop audio relay if this was the audio client
            if (AudioRelay.remoteIp == remoteIp) AudioRelay.stop()
        }

        fun loop() {
            try {
                sock.soTimeout = 45000
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    try { handle(JSONObject(line)) }
                    catch (e: SecurityException) {
                        send(JSONObject().put("ev","error").put("msg","Permission missing on SIM phone"))
                    }
                    catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally { close() }
        }

        private fun handle(j: JSONObject) {
            val cmd = j.optString("cmd")
            if (!authed) {
                if (cmd == "hello" && pin.isNotEmpty() && j.optString("pin") == pin) {
                    authed = true
                    send(JSONObject().put("ev","hello"))
                    io.execute { send(callEvent()) }
                } else { send(JSONObject().put("ev","denied")); close() }
                return
            }
            val tm = getSystemService(TelecomManager::class.java) ?: return
            when (cmd) {
                "ping"           -> send(JSONObject().put("ev","pong"))
                "answer"         -> tm.acceptRingingCall()
                "reject","hangup"-> tm.endCall()
                "audio_start"    -> {
                    // S24 is ready to relay audio — start AudioRelay toward S24
                    AudioRelay.start(this@HostService, remoteIp)
                }
                "audio_stop"     -> AudioRelay.stop()
                "dial"           -> {
                    val n = j.optString("number").filter { it.isDigit()||it=='+'||it=='*'||it=='#' }
                    if (n.isNotEmpty()) {
                        io.execute { number = n; name = lookupName(n) }
                        tm.placeCall(Uri.fromParts("tel", n, null), Bundle())
                    }
                }
                "sms"            -> {
                    val to   = j.optString("to")
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
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)!!
        else SmsManager.getDefault()

    private fun callEvent() =
        JSONObject().put("ev","call").put("state",state).put("number",number).put("name",name)

    private fun broadcast(o: JSONObject) = clients.filter { it.authed }.forEach { it.send(o) }

    private fun lookupName(n: String): String = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
    } catch (_: Exception) { "" }

    fun onPhoneState(st: String?, num: String?) {
        io.execute {
            val s = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING  -> "ringing"
                TelephonyManager.EXTRA_STATE_OFFHOOK  -> "active"
                else -> "idle"
            }
            var changed = s != state
            if (s == "idle") {
                if (number.isNotEmpty() || name.isNotEmpty()) changed = true
                number = ""; name = ""
                AudioRelay.stop()
            } else if (!num.isNullOrEmpty() && num != number) {
                number = num; name = lookupName(num); changed = true
            }
            state = s
            if (changed) broadcast(callEvent())
        }
    }

    fun onSms(from: String, body: String) {
        io.execute {
            broadcast(JSONObject().put("ev","sms").put("from",from)
                .put("name",lookupName(from)).put("body",body))
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        instance = this
        pin = prefs(this).getString("pin","") ?: ""
        ensureChannels(this)
        startFg(this, nb(this,"status")
            .setContentTitle("SIM bridge running")
            .setContentText("Relaying calls and audio to your other phone")
            .setOngoing(true).setContentIntent(openApp(this)).build())
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"simbridge:host")
            wl?.acquire()
        }
        if (server == null) Thread {
            try {
                val ss = ServerSocket(); ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT_CTRL)); server = ss
                while (true) {
                    val s = ss.accept(); val c = HClient(s)
                    clients.add(c); Thread { c.loop() }.start()
                }
            } catch (_: Exception) {}
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        AudioRelay.stop()
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { it.close() }
        try { wl?.release() } catch (_: Exception) {}
        wl = null; io.shutdown()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        HostService.instance?.onPhoneState(
            i.getStringExtra(TelephonyManager.EXTRA_STATE),
            i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
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

// ── REMOTE PHONE SIDE (S24) ──────────────────────────────────────────────────

object ClientState {
    @Volatile var status  = "Not started"
    @Volatile var call    = "idle"
    @Volatile var number  = ""
    @Volatile var name    = ""
    @Volatile var audioOn = false
    val sms = CopyOnWriteArrayList<String>()
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
                    synchronized(w) { w.write(o.toString()); w.write("\n"); w.flush() }
                } catch (_: Exception) {}
            }
        }
    }

    @Volatile private var running = false
    private var sock: Socket? = null
    private var hostIp = ""
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
            val p    = prefs(this)
            val pin  = p.getString("pin","") ?: ""
            val saved = p.getString("hostIp","")?.trim().orEmpty()
            val candidates = listOfNotNull(saved.ifEmpty { null }, gateway()).distinct()
            if (candidates.isEmpty()) ClientState.status = "No Wi-Fi / IP to try"
            for (ip in candidates) {
                if (!running) break
                try {
                    ClientState.status = "Connecting to $ip ..."
                    val s = Socket(); s.connect(InetSocketAddress(ip, PORT_CTRL), 4000)
                    s.soTimeout = 10000; sock = s; hostIp = ip
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    send(JSONObject().put("cmd","hello").put("pin",pin))
                    var lastRx = System.currentTimeMillis()
                    while (running) {
                        var line: String? = null
                        try { line = r.readLine() }
                        catch (_: SocketTimeoutException) {
                            if (System.currentTimeMillis() - lastRx > 40000) break
                            send(JSONObject().put("cmd","ping")); continue
                        }
                        if (line == null) break
                        lastRx = System.currentTimeMillis()
                        handle(JSONObject(line))
                    }
                } catch (_: Exception) {
                } finally {
                    writer = null
                    AudioRelayClient.stop()
                    ClientState.audioOn = false
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
            if (ClientState.status != "Wrong PIN") ClientState.status = "Disconnected - retrying"
            ClientState.call = "idle"
            val nm = getSystemService(NotificationManager::class.java)!!
            nm.cancel(2); nm.cancel(3)
            try { Thread.sleep(3000) } catch (_: Exception) {}
        }
    }

    private fun handle(j: JSONObject) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        when (j.optString("ev")) {
            "hello"  -> ClientState.status = "Connected"
            "denied" -> ClientState.status = "Wrong PIN"
            "error"  -> ClientState.status = j.optString("msg")
            "call"   -> {
                val st      = j.optString("state")
                val num     = j.optString("number")
                val nameStr = j.optString("name")
                ClientState.call   = st
                ClientState.number = num
                ClientState.name   = nameStr
                val label = nameStr.ifEmpty { num.ifEmpty { "Unknown" } }
                when (st) {
                    "ringing" -> {
                        nm.cancel(3)
                        val n = nb(this,"ring")
                            .setContentTitle("Incoming call")
                            .setContentText(label)
                            .setCategory(Notification.CATEGORY_CALL)
                            .setOngoing(true).setOnlyAlertOnce(true)
                            .setFullScreenIntent(openApp(this), true)
                            .addAction(actionOf(this,"Answer","answer"))
                            .addAction(actionOf(this,"Reject","reject"))
                            .build()
                        n.flags = n.flags or Notification.FLAG_INSISTENT
                        nm.notify(2, n)
                    }
                    "active" -> {
                        nm.cancel(2)
                        // start audio relay automatically
                        if (!AudioRelayClient.running) {
                            AudioRelayClient.start(this, hostIp)
                            ClientState.audioOn = true
                            send(JSONObject().put("cmd","audio_start"))
                        }
                        nm.notify(3, nb(this,"status")
                            .setContentTitle("On a call — audio on S24")
                            .setContentText(label)
                            .setOngoing(true).setContentIntent(openApp(this))
                            .addAction(actionOf(this,"Hang up","hangup"))
                            .build())
                    }
                    else -> {
                        nm.cancel(2); nm.cancel(3)
                        AudioRelayClient.stop()
                        ClientState.audioOn = false
                        send(JSONObject().put("cmd","audio_stop"))
                    }
                }
            }
            "sms" -> {
                val from = j.optString("name").ifEmpty { j.optString("from") }
                val body = j.optString("body")
                ClientState.sms.add(0, "$from: $body")
                while (ClientState.sms.size > 30) ClientState.sms.removeAt(ClientState.sms.size - 1)
                nm.notify((System.currentTimeMillis() % 100000).toInt() + 100,
                    nb(this,"sms").setContentTitle(from).setContentText(body)
                        .setStyle(Notification.BigTextStyle().bigText(body))
                        .setAutoCancel(true).setContentIntent(openApp(this)).build())
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        instance = this; ensureChannels(this)
        startFg(this, nb(this,"status")
            .setContentTitle("SIM bridge remote")
            .setContentText("Linked to your SIM phone")
            .setOngoing(true).setContentIntent(openApp(this)).build())
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"simbridge:client")
            wl?.acquire()
        }
        if (!running) { running = true; Thread { connectLoop() }.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; instance = null
        AudioRelayClient.stop(); ClientState.audioOn = false
        try { sock?.close() } catch (_: Exception) {}
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.cancel(2); nm.cancel(3)
        try { wl?.release() } catch (_: Exception) {}
        wl = null; ClientState.status = "Stopped"
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action ?: return
        ClientService.send(JSONObject().put("cmd", a))
    }
}

// ── UI ───────────────────────────────────────────────────────────────────────

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var refresh: () -> Unit = {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        ensureChannels(this)
        val sv = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40,60,40,60) }
        sv.addView(root); setContentView(sv); rebuild()
    }

    override fun onResume()  { super.onResume();  tick() }
    override fun onPause()   { super.onPause();   h.removeCallbacksAndMessages(null) }

    private fun tick() { refresh(); h.postDelayed({ tick() }, 1000) }

    private fun tv(t: String, size: Float = 16f) = TextView(this).apply {
        text = t; textSize = size; setPadding(0,12,0,12) }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { f() } }

    private fun et(hint: String, v: String = "") = EditText(this).apply {
        this.hint = hint; setText(v) }

    private fun rebuild() {
        root.removeAllViews(); refresh = {}
        when (prefs(this).getString("role","")) {
            "host"   -> hostUi()
            "client" -> clientUi()
            else     -> chooser()
        }
    }

    private fun chooser() {
        root.addView(tv("SIM Bridge", 26f))
        root.addView(tv("Which phone is this?"))
        root.addView(btn("This phone has the SIM  (A07)") {
            prefs(this).edit().putString("role","host").apply(); rebuild() })
        root.addView(btn("This is the remote phone  (S24)") {
            prefs(this).edit().putString("role","client").apply(); rebuild() })
    }

    private fun changeRole() {
        stopService(Intent(this, HostService::class.java))
        stopService(Intent(this, ClientService::class.java))
        prefs(this).edit().remove("role").apply(); rebuild()
    }

    private fun askPerms() {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    @Suppress("BatteryLife")
    private fun askBattery() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))

    private fun hostUi() {
        val p = prefs(this)
        if (p.getString("pin","").isNullOrEmpty())
            p.edit().putString("pin",(100000+java.util.Random().nextInt(900000)).toString()).apply()
        root.addView(tv("SIM phone mode", 24f))
        root.addView(tv("PIN for other phone:"))
        root.addView(tv(p.getString("pin","") ?: "", 32f))
        val info = tv("")
        root.addView(info)
        root.addView(btn("1. Grant permissions")    { askPerms() })
        root.addView(btn("2. Allow background")     { askBattery() })
        root.addView(btn("3. Start bridge")         {
            startForegroundService(Intent(this, HostService::class.java)) })
        root.addView(btn("Stop bridge")             {
            stopService(Intent(this, HostService::class.java)) })
        root.addView(btn("Change role")             { changeRole() })
        refresh = {
            info.text = "Bridge: " + (if (HostService.instance != null) "RUNNING" else "stopped") +
                "\nAudio relay: " + (if (AudioRelay.running) "ACTIVE" else "idle") +
                "\n\nAddresses:\n" + localIps().joinToString("\n")
        }
    }

    private fun clientUi() {
        val p = prefs(this)
        root.addView(tv("Remote phone mode", 24f))
        val ip  = et("SIM phone IP (empty = auto on hotspot)", p.getString("hostIp","") ?: "")
        val pin = et("PIN from SIM phone", p.getString("pin","") ?: "")
        pin.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(ip); root.addView(pin)
        root.addView(btn("Connect") {
            p.edit().putString("hostIp", ip.text.toString().trim())
                .putString("pin", pin.text.toString().trim()).apply()
            if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO), 2)
            stopService(Intent(this, ClientService::class.java))
            startForegroundService(Intent(this, ClientService::class.java))
        })
        root.addView(btn("Allow background") { askBattery() })
        root.addView(btn("Disconnect")       { stopService(Intent(this, ClientService::class.java)) })

        val status   = tv("")
        val callInfo = tv("", 20f)
        root.addView(status); root.addView(callInfo)
        root.addView(btn("Answer")       { ClientService.send(JSONObject().put("cmd","answer")) })
        root.addView(btn("Hang up")      { ClientService.send(JSONObject().put("cmd","hangup")) })

        root.addView(tv("Dial", 18f))
        val num = et("Number"); num.inputType = InputType.TYPE_CLASS_PHONE
        root.addView(num)
        root.addView(btn("Call") {
            ClientService.send(JSONObject().put("cmd","dial").put("number",num.text.toString())) })

        root.addView(tv("Send SMS", 18f))
        val to   = et("To"); to.inputType = InputType.TYPE_CLASS_PHONE
        val body = et("Message")
        root.addView(to); root.addView(body)
        root.addView(btn("Send") {
            val t = to.text.toString().trim(); val m = body.text.toString()
            if (t.isNotEmpty() && m.isNotEmpty()) {
                ClientService.send(JSONObject().put("cmd","sms").put("to",t).put("body",m))
                ClientState.sms.add(0,"Me -> $t: $m"); body.setText("") }
        })

        root.addView(tv("Messages", 18f))
        val smsView = tv("")
        root.addView(smsView)
        root.addView(btn("Change role") { changeRole() })

        refresh = {
            status.text = "Status: " + ClientState.status
            val who = if (ClientState.name.isNotEmpty())
                "${ClientState.name} (${ClientState.number})"
            else ClientState.number.ifEmpty { "?" }
            callInfo.text = when (ClientState.call) {
                "ringing" -> "Ringing: $who"
                "active"  -> "Call: $who\nAudio: " +
                    if (ClientState.audioOn) "ON — speak into this phone" else "starting..."
                else      -> "Idle"
            }
            smsView.text = ClientState.sms.joinToString("\n\n")
        }
    }
}
