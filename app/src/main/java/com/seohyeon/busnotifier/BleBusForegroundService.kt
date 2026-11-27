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

    private fun findStoredApiKey(): String {
        val prefNames = listOf("bus_prefs", "BusNotifierPrefs", "${packageName}_preferences", "settings")
        for (name in prefNames) {
            val map = getSharedPreferences(name, Context.MODE_PRIVATE).all
            for ((_, value) in map) {
                val str = value?.toString()?.trim() ?: ""
                if (str.length >= 32 && !str.startsWith("206")) {
                    return str
                }
            }
        }
        return ""
    }

    private fun requestStationJson(serviceKeyParam: String, stationId: String): JSONObject? {
        return try {
            val baseUrl = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2"
            val apiUrl = "$baseUrl?serviceKey=$serviceKeyParam&stationId=$stationId&format=json"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 4000
                readTimeout = 4000
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            if (text.trim().startsWith("{")) JSONObject(text) else null
        } catch (_: Exception) {
            null
        }
    }

    // 521번 양방향(2개)과 522-2번이 동시에 정차하는 서현역.AK플라자(07169) 자동 탐색
    private fun resolveRealStationId(serviceKeyParam: String): String {
        val prefs = getSharedPreferences("bus_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("verified_station_id_v2", "") ?: ""
        if (cached.isNotEmpty()) return cached

        broadcastStatus("07169(양방향 521+522-2) 정류소 탐색 중...", null)
        val foundId = AtomicReference("")
        val pool = Executors.newFixedThreadPool(16)

        for (num in 206000001..206000800) {
            pool.submit {
                if (foundId.get().isNotEmpty()) return@submit
                val stId = num.toString()
                val json = requestStationJson(serviceKeyParam, stId) ?: return@submit
                val arr = json.optJSONObject("response")?.optJSONObject("msgBody")?.optJSONArray("busArrivalList") ?: return@submit

                var count521 = 0
                var has522_2 = false
                for (i in 0 until arr.length()) {
                    val rName = arr.getJSONObject(i).optString("routeName").trim()
                    if (rName == "521") count521++
                    if (rName == "522-2") has522_2 = true
                }
                if (count521 >= 2 && has522_2) {
                    foundId.compareAndSet("", stId)
                }
            }
        }
        pool.shutdown()
        while (!pool.isTerminated) {
            if (foundId.get().isNotEmpty()) {
                pool.shutdownNow()
                break
            }
            Thread.sleep(100)
