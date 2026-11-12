package com.cypress.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cypress.watch.Prefs
import com.cypress.watch.ProtoLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The Bluetooth link to the watch: connect, write queue, and incoming packets (joined into whole replies). */
@SuppressLint("MissingPermission")
object Watch {
    val SVC_DATA: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val DATA_W: UUID = UUID.fromString("000034f1-0000-1000-8000-00805f9b34fb")
    val DATA_N: UUID = UUID.fromString("000034f2-0000-1000-8000-00805f9b34fb")
    val SVC_CMD: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
    val CMD_N: UUID = UUID.fromString("000035f2-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class Link { OFF, SCANNING, CONNECTING, READY }

    data class State(
        val link: Link = Link.OFF,
        val name: String? = null,
        val battery: Int? = null,
        val charging: Boolean = false,
        val firmware: String? = null,
        val model: String? = null,
        val screenW: Int? = null,
        val screenH: Int? = null,
        val stepsToday: Int? = null,
        val busy: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    fun update(f: (State) -> State) { _state.value = f(_state.value) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ctx: Context
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    var mtu = 23; private set
    val maxPacket get() = (mtu - 3).coerceAtLeast(20)

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var writeDone = CompletableDeferred<Unit>()
    private var wantConnected = false
    private var retry = 0

    /** Packet handlers: called for every packet. Group handlers: called with a whole multi-packet reply. */
    val packetListeners = java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()
    val groupListeners = java.util.concurrent.CopyOnWriteArrayList<(cmd: Int, mode: Int, field: Int, packets: List<ByteArray>) -> Unit>()
    var onReady: (() -> Unit)? = null

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        scope.launch {
            for (p in queue) {
                val g = gatt; val ch = writeChar
                if (g == null || ch == null) continue
                writeDone = CompletableDeferred()
                ProtoLog.tx(p)
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = p; g.writeCharacteristic(ch) }
                }
                if (!ok) { delay(60); continue }
                withTimeoutOrNull(400) { writeDone.await() }
                delay(8)
            }
        }
    }

    fun send(p: ByteArray) { queue.trySend(p) }
    fun send(ps: List<ByteArray>) { ps.forEach { queue.trySend(it) } }
    val ready get() = state.value.link == Link.READY

    // ---------- scanning ----------
    data class Found(val address: String, val name: String, val rssi: Int)
    val found = MutableStateFlow<List<Found>>(emptyList())
    private var scanCb: ScanCallback? = null

    fun startScan() {
        val sc = adapter()?.bluetoothLeScanner ?: run { update { it.copy(error = "Bluetooth is off") }; return }
        stopScan()
        found.value = emptyList()
        // watches already paired with the phone show up too, even when they aren't advertising
        adapter()?.bondedDevices?.forEach { d -> val n = d.name ?: return@forEach; if (looksLikeWatch(n)) add(Found(d.address, n, -50)) }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: r.device.name ?: return
                add(Found(r.device.address, n, r.rssi))
            }
        }
        scanCb = cb
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        update { it.copy(link = if (it.link == Link.READY) it.link else Link.SCANNING) }
        main.postDelayed({ stopScan() }, 20000)
    }

    private fun add(f: Found) {
        val l = found.value.filter { it.address != f.address } + f
        found.value = l.sortedWith(compareByDescending<Found> { looksLikeWatch(it.name) }.thenByDescending { it.rssi })
    }

    fun looksLikeWatch(n: String) = n.startsWith("W60", true) || n.startsWith("NAL", true) || n.contains("watch", true)

    fun stopScan() {
        scanCb?.let { runCatching { adapter()?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
        if (state.value.link == Link.SCANNING) update { it.copy(link = Link.OFF) }
    }

    private fun adapter(): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ---------- connecting ----------
    fun connect(address: String? = Prefs.address, name: String? = null) {
        if (address == null) return
        stopScan()
        if (name != null) { Prefs.address = address; Prefs.deviceName = name }
        wantConnected = true
        val dev = adapter()?.getRemoteDevice(address) ?: return
        gatt?.close()
        update { it.copy(link = Link.CONNECTING, name = Prefs.deviceName ?: name, error = null) }
        ProtoLog.info("connecting to $address")
        gatt = dev.connectGatt(ctx, retry > 2, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(forget: Boolean = false) {
        wantConnected = false
        gatt?.disconnect(); gatt?.close(); gatt = null; writeChar = null
        if (forget) { Prefs.address = null; Prefs.deviceName = null }
        update { State() }
    }

    private val pending = ArrayDeque<BluetoothGattDescriptor>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ProtoLog.info("link state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                retry = 0
                main.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
                update { it.copy(link = if (wantConnected) Link.CONNECTING else Link.OFF) }
                if (wantConnected) {
                    retry++
                    g.close(); if (gatt == g) gatt = null
                    main.postDelayed({ if (wantConnected) connect() }, (2000L * retry).coerceAtMost(30000))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val data = g.getService(SVC_DATA)
            writeChar = data?.getCharacteristic(DATA_W)
            if (writeChar == null) {
                update { it.copy(error = "This doesn't look like a GloryFit Pro watch (no 56FF service)") }
                ProtoLog.info("services: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
                return
            }
            g.requestMtu(247)
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) m else 23
            ProtoLog.info("mtu=$mtu")
            pending.clear()
            for ((s, c) in listOf(SVC_DATA to DATA_N, SVC_CMD to CMD_N)) {
                val ch = g.getService(s)?.getCharacteristic(c) ?: continue
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { pending.addLast(it) }
            }
            nextDescriptor(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { nextDescriptor(g) }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { incoming(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { incoming(value) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeDone.complete(Unit) }
    }

    private fun nextDescriptor(g: BluetoothGatt) {
        val d = pending.removeFirstOrNull()
        if (d == null) {
            update { it.copy(link = Link.READY, name = Prefs.deviceName ?: g.device.name, error = null) }
            ProtoLog.info("ready")
            onReady?.invoke()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
    }

    // ---------- incoming ----------
    private val groups = HashMap<String, MutableList<ByteArray>>()

    @Synchronized
    private fun incoming(p: ByteArray) {
        ProtoLog.rx(p)
        if (p.size < 3 || p[0] != Proto.H) return
        packetListeners.toList().forEach { runCatching { it(p) }.onFailure { e -> ProtoLog.info("handler error: $e") } }
        if (p.size < 5) return
        val cmd = p[1].toInt() and 0xff; val mode = p[2].toInt() and 0xff; val f = p[3].toInt() and 0xff; val idx = p[4].toInt() and 0xff
        // "01 CMD MODE fd .." also closes a group (some replies put the terminator one byte earlier)
        val key = "$cmd/$mode/$f"
        if (f == 0xfd) {
            val k = groups.keys.firstOrNull { it.startsWith("$cmd/$mode/") } ?: return
            flush(k); return
        }
        // only multi-packet replies are collected: "get all"/"set all" (mode byte doubled), history, faces
        if (!(mode == f || cmd == 0xc6 || (cmd == 0xe3 && mode == 0xaa))) return
        val list = groups.getOrPut(key) { mutableListOf() }
        list += p
        if (idx == 0xfd) { flush(key); return }
        // if the closing packet never comes, hand over what arrived after a quiet moment
        val n = list.size
        main.postDelayed({ synchronized(this) { if (groups[key]?.size == n) flush(key) } }, 2500)
    }

    private fun flush(key: String) {
        val list = groups.remove(key) ?: return
        val (cmd, mode, f) = key.split("/").map { it.toInt() }
        val data = list.filter { (it[4].toInt() and 0xff) != 0xfd }
        groupListeners.toList().forEach { runCatching { it(cmd, mode, f, data) }.onFailure { e -> ProtoLog.info("group handler error: $e") } }
    }

    /** Payload of a multi-packet reply: bytes after "01 CMD MODE FIELD IDX" of each packet, joined. */
    fun joined(packets: List<ByteArray>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        packets.forEach { if (it.size > 5) o.write(it, 5, it.size - 5) }
        return o.toByteArray()
    }
}
