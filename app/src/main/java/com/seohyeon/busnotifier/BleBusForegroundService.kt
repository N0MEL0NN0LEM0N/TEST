package com.seohyeon.busnotifier

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class BleBusForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "seohyeon_bus_ble_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.seohyeon.busnotifier.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"

        const val DEVICE_NAME = "T-Display-Bus"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isConnecting = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("IO14 버튼 신호 대기 중 (화면 꺼짐 감지 활성)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        startBleScan()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            broadcastUpdate("오류: 블루투스가 꺼져 있습니다.", "")
            return
        }
        if (isScanning || isConnecting) return

        // 화면이 꺼져 있어도 Android OS가 하드웨어 레벨에서 매칭 패킷을 깨워주도록 ScanFilter 필수 적용
        val filter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        try {
            isScanning = true
            scanner.startScan(listOf(filter), settings, scanCallback)
            broadcastUpdate("상태: 백그라운드 IO14 버튼 신호 감지 중...", "")
        } catch (e: Exception) {
            isScanning = false
            broadcastUpdate("스캔 시작 실패: ${e.message}", "")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isConnecting) return
            isConnecting = true
            stopBleScan()

            acquireTemporaryWakeLock()
            updateNotification("보드 깨어남 감지! 버스 정보 조회 및 전송 중...")
            broadcastUpdate("상태: IO14 버튼 감지! 보드 연결 중...", "")

            bluetoothGatt = result.device.connectGatt(
                this@BleBusForegroundService,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.postDelayed({ startBleScan() }, 3000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanupConnection(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                fetchBusAndWrite(gatt)
            } else {
                cleanupConnection(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // 전송 완료 후 보드가 30초 디스플레이 후 슬립 들어가도록 연결 종료 및 재스캔 대기
            mainHandler.postDelayed({
                gatt.disconnect()
            }, 500)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchBusAndWrite(gatt: BluetoothGatt) {
        serviceScope.launch {
            val prefs = getSharedPreferences("bus_prefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            val stationId = prefs.getString("station_id", "206000299") ?: "206000299"

            val payload = fetchGyeonggiBusInfo(apiKey, stationId)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())

            val service = gatt.getService(SERVICE_UUID)
            val rxChar = service?.getCharacteristic(CHAR_RX_UUID)

            if (rxChar != null) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        rxChar,
                        bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    rxChar.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(rxChar)
                }

                if (success) {
                    updateNotification("전송 완료 ($timeStr): $payload")
                    broadcastUpdate(
                        "상태: 전송 완료! 보드 30초 표시 중...",
                        "[$timeStr] 전송 데이터: $payload"
                    )
                } else {
                    cleanupConnection(gatt)
                }
            } else {
                cleanupConnection(gatt)
            }
        }
    }

    private fun fetchGyeonggiBusInfo(apiKey: String, stationId: String): String {
        if (apiKey.isBlank()) return "522-2:NOKEY,-|521:NOKEY,-"
        return try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val url = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2" +
                    "?serviceKey=$encodedKey&stationId=$stationId&format=json"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return "522-2:-,-|521:-,-"

            val json = JSONObject(body)
            val msgBody = json.getJSONObject("response").getJSONObject("msgBody")
            val list = msgBody.getJSONArray("busArrivalList")

            var bus522 = "-,-"
            var bus521 = "-,-"

            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val routeName = item.optString("routeName", "")
                val t1 = item.optString("predictTime1", "-").ifEmpty { "-" }
                val t2 = item.optString("predictTime2", "-").ifEmpty { "-" }

                if (routeName == "522-2") bus522 = "$t1,$t2"
                if (routeName == "521") bus521 = "$t1,$t2"
            }
            "522-2:$bus522|521:$bus521"
        } catch (e: Exception) {
            "522-2:ERR,-|521:ERR,-"
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupConnection(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        isConnecting = false
        releaseTemporaryWakeLock()

        // 보드가 30초 동안 켜져 있으므로 5초 후 다음 버튼 누름 대기를 위해 스캔 재개
        mainHandler.postDelayed({
            updateNotification("IO14 버튼 신호 대기 중 (화면 꺼짐 감지 활성)")
            startBleScan()
        }, 5000)
    }

    private fun acquireTemporaryWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BusNotifier::BleWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(15_000L)
        }
    }

    private fun releaseTemporaryWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "서현역 버스 BLE 백그라운드 감지",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("서현역 AK플라자 (07169) 버스 알리미")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun broadcastUpdate(status: String, log: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_LOG, log)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopBleScan()
        releaseTemporaryWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
