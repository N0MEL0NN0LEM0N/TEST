package com.seohyeon.busnotifier

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

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
        private const val STATION_ID = "206000299"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isProcessing = false
    private var lastSentTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("IO14 버튼 신호 대기 중..."))
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
            broadcastStatus("보드 신호 스캔 중...", null)
        } catch (e: Exception) {
            broadcastStatus("BLE 스캔 오류: ${e.message}", null)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val now = System.currentTimeMillis()
            if (isProcessing || now - lastSentTime < 25_000L) return

            isProcessing = true
            broadcastStatus("보드 감지됨! 버스 정보 조회 중...", null)

            Thread {
                val payload = fetchBusArrivalData()
                sendToBoard(result.device, payload)
            }.start()
        }
    }

    private fun fetchBusArrivalData(): String {
        val prefs = getSharedPreferences("bus_prefs", Context.MODE_PRIVATE)
        val rawKey = prefs.getString("api_key", "")?.trim() ?: ""
        if (rawKey.isEmpty()) return "522-2:NOKEY,-|521:NOKEY,-"

        return try {
            val serviceKeyParam = if (rawKey.contains("%")) rawKey else URLEncoder.encode(rawKey, "UTF-8")
            val baseUrl = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2"
            val apiUrl = "$baseUrl?serviceKey=$serviceKeyParam&stationId=$STATION_ID&format=json"

            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
            }

            val responseText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            if (responseText.contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")) {
                return "522-2:KEY_ERR,-|521:KEY_ERR,-"
            }

            val root = JSONObject(responseText)
            val msgBody = root.optJSONObject("response")?.optJSONObject("msgBody") ?: return "522-2:-,-|521:-,-"
            val busArray = msgBody.optJSONArray("busArrivalList") ?: return "522-2:-,-|521:-,-"

            var info522 = "-,-"
            var info521 = "-,-"

            for (i in 0 until busArray.length()) {
                val item = busArray.getJSONObject(i)
                val routeName = item.optString("routeName").trim()
                val destName = item.optString("routeDestName").trim()
                val staOrder = item.optInt("staOrder", -1)
                val t1 = item.optString("predictTime1").ifEmpty { "-" }
                val t2 = item.optString("predictTime2").ifEmpty { "-" }

                if (routeName == "522-2") {
                    info522 = "$t1,$t2"
                } else if (routeName == "521") {
                    val isImae = !destName.contains("양현") && (destName.contains("이매") || staOrder < 30)
                    if (isImae) {
                        info521 = "$t1,$t2"
                    }
                }
            }
            "522-2:$info522|521:$info521"
        } catch (e: Exception) {
            "522-2:ERR,-|521:ERR,-"
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendToBoard(device: BluetoothDevice, payload: String) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    isProcessing = false
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_RX_UUID)
                if (rxChar != null) {
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(rxChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        rxChar.value = bytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(rxChar)
                    }
                } else {
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                lastSentTime = System.currentTimeMillis()
                broadcastStatus("전송 완료! 보드 30초 표시 중...", payload)
                updateNotification("최근 전송: $payload")
                handler.postDelayed({ gatt.disconnect() }, 400)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun broadcastStatus(status: String, data: String?) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_LOG, status)
            if (data != null) putExtra(EXTRA_DATA, data)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("서현역 AK플라자 (07169) 버스 알리미")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "버스 알리미 서비스", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        if (isScanning) {
            try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            isScanning = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
