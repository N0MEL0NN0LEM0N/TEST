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

        private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CHAR_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val STATION_ID = "206000299" // 서현역.AK플라자 (07169)
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
        val notification = buildNotification("IO14 버튼 신호 대기 중...")
        startForeground(NOTIF_ID, notification)
        startBleScan()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            broadcastStatus("보드 신호 스캔 중...", null)
        } catch (
