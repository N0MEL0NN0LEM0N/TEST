package com.seohyeon.busnotifier

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class BleBusForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "BusNotifierChannel"
        const val NOTIF_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.seohyeon.busnotifier.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DATA = "data"
        const val EXTRA_LOG = "log"
        private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CHAR_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isProcessing = false
    private var lastSentTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "버스 알리미", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("IO14 버튼 신호 대기 중..."))
        startBleScan()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            sendStatus("보드 신호 스캔 중...", null)
        } catch (e: Exception) {
            sendStatus("BLE 오류: ${e.message}", null)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val now = System.currentTimeMillis()
            if (isProcessing || now - lastSentTime < 25_000L) return
            isProcessing = true
            sendStatus("보드 감지됨! 조회 중...", null)
            Thread { sendToBoard(result.device, fetchBusData()) }.start()
        }
    }

    private fun getApiKey(): String {
        for (name in listOf("bus_prefs", "BusNotifierPrefs", "${packageName}_preferences", "settings")) {
            for ((_, v) in getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val s = v?.toString()?.trim() ?: ""
                if (s.length >= 32 && !s.startsWith("206")) return s
            }
        }
        return ""
    }

    private fun getJson(keyParam: String, stId: String): JSONObject? {
        return try {
            val u = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2?serviceKey=$keyParam&stationId=$stId&format=json"
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 4000; readTimeout = 4000
            }
            val txt = c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            c.disconnect()
            if (txt.trim().startsWith("{")) JSONObject(txt) else null
        } catch (_: Exception) { null }
    }

    private fun findStationId(keyParam: String): String {
        val prefs = getSharedPreferences("bus_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("verified_station_id_v2", "") ?: ""
        if (cached.isNotEmpty()) return cached

        sendStatus("07169 정류소 ID 자동 탐색 중...", null)
        val found = AtomicReference("")
        val pool = Executors.newFixedThreadPool(16)
        for (num in 206000001..206000800) {
            pool.submit {
                if (found.get().isNotEmpty()) return@submit
                val stId = num.toString()
                val arr = getJson(keyParam, stId)?.optJSONObject("response")?.optJSONObject("msgBody")?.optJSONArray("busArrivalList") ?: return@submit
                var c521 = 0; var has522 = false
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i).optString("routeName").trim()
                    if (r == "521") c521++
                    if (r == "522-2") has522 = true
                }
                if (c521 >= 2 && has522) found.compareAndSet("", stId)
            }
        }
        pool.shutdown()
        while (!pool.isTerminated) {
            if (found.get().isNotEmpty()) { pool.shutdownNow(); break }
            Thread.sleep(100)
        }
        return found.get().also {
            if (it.isNotEmpty()) {
                prefs.edit().putString("verified_station_id_v2", it).apply()
                sendStatus("07169 ID 확정: $it", null)
            }
        }
    }

    private fun fetchBusData(): String {
        val rawKey = getApiKey()
        if (rawKey.isEmpty()) return "522-2:NOKEY,-|521:NOKEY,-"
        return try {
            val keyParam = if (rawKey.contains("%")) rawKey else URLEncoder.encode(rawKey, "UTF-8")
            val stId = findStationId(keyParam)
            if (stId.isEmpty()) return "522-2:NOID,-|521:NOID,-"

            val arr = getJson(keyParam, stId)?.optJSONObject("response")?.optJSONObject("msgBody")?.optJSONArray("busArrivalList")
                ?: return "522-2:-,-|521:-,-"

            var b522 = "-,-"; var b521 = "-,-"
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val r = it.optString("routeName").trim()
                val d = it.optString("routeDestName").trim()
                val ord = it.optInt("staOrder", -1)
                val t1 = it.optString("predictTime1").ifEmpty { "-" }
                val t2 = it.optString("predictTime2").ifEmpty { "-" }
                if (r == "522-2") b522 = "$t1,$t2"
                else if (r == "521" && !d.contains("양현") && (d.contains("이매") || ord < 30)) b521 = "$t1,$t2"
            }
            "522-2:$b522|521:$b521"
        } catch (_: Exception) { "522-2:ERR,-|521:ERR,-" }
    }

    @SuppressLint("MissingPermission")
    private fun sendToBoard(device: BluetoothDevice, payload: String) {
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
                if (ns == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) { g.close(); isProcessing = false }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val rx = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_RX_UUID) ?: return g.disconnect()
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                else { @Suppress("DEPRECATION") rx.value = bytes; @Suppress("DEPRECATION") g.writeCharacteristic(rx) }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                lastSentTime = System.currentTimeMillis()
                sendStatus("전송 완료! ($payload)", payload)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif("최근: $payload"))
                handler.postDelayed({ g.disconnect() }, 400)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(this, false, cb)
    }

    private fun sendStatus(st: String, data: String?) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName); putExtra(EXTRA_STATUS, st); putExtra(EXTRA_LOG, st)
            if (data != null) putExtra(EXTRA_DATA, data)
        })
    }

    private fun buildNotif(txt: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("서현역 AK플라자 (07169) 버스 알리미").setContentText(txt)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        if (isScanning) { try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}; isScanning = false }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
// END
