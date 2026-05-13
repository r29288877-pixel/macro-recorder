package com.macrorecorder.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartOverlay: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartOverlay = findViewById(R.id.btnStartOverlay)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)

        btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartOverlay.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("需要無障礙服務")
                    .setMessage("請先到設定 → 無障礙 → 已安裝的 App → Macro Recorder，並開啟服務。")
                    .setPositiveButton("去設定") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要顯示在其他應用程式上方")
                    .setMessage("請先授予「顯示在其他應用程式上方」權限。")
                    .setPositiveButton("去設定") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            val intent = Intent(this, OverlayService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "浮動面板已啟動！可以切換到遊戲了", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun updateStatusUI() {
        val accessOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        tvAccessibilityStatus.text = if (accessOk) "✅ 無障礙服務：已開啟" else "❌ 無障礙服務：未開啟"
        tvOverlayStatus.text = if (overlayOk) "✅ 浮動視窗：已授權" else "❌ 浮動視窗：未授權"

        btnGrantAccessibility.isEnabled = !accessOk
        btnGrantOverlay.isEnabled = !overlayOk
        btnStartOverlay.isEnabled = accessOk && overlayOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.id.contains(packageName) }
    }
}
