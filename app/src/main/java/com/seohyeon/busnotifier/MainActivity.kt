package com.seohyeon.busnotifier

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var stationIdInput: EditText
    private lateinit var statusText: TextView
    private lateinit var lastLogText: TextView
    private lateinit var toggleBtn: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            startBleService()
        } else {
            statusText.text = "필수 권한(블루투스/알림/위치)을 모두 허용해야 백그라운드 감지가 작동합니다."
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(BleBusForegroundService.EXTRA_STATUS) ?: return
            val log = intent.getStringExtra(BleBusForegroundService.EXTRA_LOG) ?: ""
            statusText.text = status
            if (log.isNotEmpty()) {
                lastLogText.text = log
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("bus_prefs", Context.MODE_PRIVATE)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "서현역 AK플라자 (07169)\nBLE 버스 알리미"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 32)
        }

        val apiLabel = TextView(this).apply {
            text = "공공데이터포털 인증키 (Decoding 키)"
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 8, 0, 8)
        }

        apiKeyInput = EditText(this).apply {
            hint = "경기도_버스도착정보 조회 Decoding 키 입력"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 28, 32, 28)
            setText(prefs.getString("api_key", ""))
        }

        val stationLabel = TextView(this).apply {
            text = "경기도 버스 정류소 ID (기본: 서현역.AK플라자 206000299)"
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 24, 0, 8)
        }

        stationIdInput = EditText(this).apply {
            hint = "206000299"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 28, 32, 28)
            setText(prefs.getString("station_id", "206000299"))
        }

        val saveAndStartBtn = Button(this).apply {
            text = "설정 저장 및 백그라운드 감지 시작"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 32, 0, 32)
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                val stId = stationIdInput.text.toString().trim().ifEmpty { "206000299" }
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "API 인증키를 먼저 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("api_key", key)
                    .putString("station_id", stId)
                    .apply()
                checkPermissionsAndStartService()
            }
        }

        toggleBtn = Button(this).apply {
            text = "백그라운드 감지 중지"
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, BleBusForegroundService::class.java))
                statusText.text = "상태: 서비스 중지됨"
            }
        }

        val batteryBtn = Button(this).apply {
            text = "배터리 최적화 제외 설정 (화면 꺼짐 감지 필수)"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                requestIgnoreBatteryOptimization()
            }
        }

        statusText = TextView(this).apply {
            text = "상태: 대기 중 (설정을 저장하고 시작을 누르세요)"
            textSize = 16f
            setTextColor(Color.parseColor("#81C784"))
            setPadding(0, 36, 0, 12)
        }

        lastLogText = TextView(this).apply {
            text = "최근 전송 기록: 없음"
            textSize = 14f
            setTextColor(Color.parseColor("#CFD8DC"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 32, 32, 32)
        }

        val spacer1 = Space(this).apply { minimumHeight = 24 }
        val spacer2 = Space(this).apply { minimumHeight = 16 }
        val spacer3 = Space(this).apply { minimumHeight = 16 }

        container.addView(title)
        container.addView(apiLabel)
        container.addView(apiKeyInput)
        container.addView(stationLabel)
        container.addView(stationIdInput)
        container.addView(spacer1)
        container.addView(saveAndStartBtn)
        container.addView(spacer2)
        container.addView(toggleBtn)
        container.addView(spacer3)
        container.addView(batteryBtn)
        container.addView(statusText)
        container.addView(lastLogText)

        root.addView(container)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BleBusForegroundService.ACTION_STATUS_UPDATE)
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun checkPermissionsAndStartService() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleService()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun startBleService() {
        val intent = Intent(this, BleBusForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "상태: 백그라운드 IO14 버튼 신호 감지 중..."
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "이미 배터리 최적화에서 제외되어 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
