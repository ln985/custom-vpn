package com.wzydqq.icu.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wzydqq.icu.AnnouncementManager
import com.wzydqq.icu.databinding.ActivityMainBinding
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.location.SelectedLocation
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadAnnouncement()
    }

    override fun onResume() {
        super.onResume()
        updateLocationDisplay()
    }

    private fun setupUI() {
        // 当前位置显示
        updateLocationDisplay()

        // 选择位置按钮
        binding.btnSelectLocation.setOnClickListener {
            startActivity(Intent(this, LocationPickerActivity::class.java))
        }

        // 清除位置按钮
        binding.btnClearLocation.setOnClickListener {
            LocationStore.clear(this)
            updateLocationDisplay()
            Toast.makeText(this, "位置已清除", Toast.LENGTH_SHORT).show()
        }

        // 公告按钮
        binding.btnAnnouncement.setOnClickListener {
            startActivity(Intent(this, AnnouncementActivity::class.java))
        }
    }

    private fun updateLocationDisplay() {
        val location = LocationStore.get(this)
        if (location != null) {
            binding.tvCurrentLocation.text = buildString {
                append("📍 当前伪装位置\n\n")
                append("省份: ${location.provinceName}\n")
                append("城市: ${location.cityName}\n")
                append("区县: ${location.districtName}\n")
                append("坐标: ${location.lat}, ${location.lng}\n")
                append("区划代码: ${location.adcode}")
            }
            binding.tvStatus.text = "✅ 位置伪装已开启"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvCurrentLocation.text = "尚未设置伪装位置\n点击下方按钮选择"
            binding.tvStatus.text = "⚪ 位置伪装未开启"
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    private fun loadAnnouncement() {
        lifecycleScope.launch {
            val config = AnnouncementManager.fetchConfig()
            if (config != null && config.enabled) {
                binding.btnAnnouncement.text = "📢 公告 (${config.notices.size})"
                if (config.forceUpdate && config.version.isNotEmpty()) {
                    // Check version and prompt update
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
                    if (currentVersion != config.version) {
                        Toast.makeText(
                            this@MainActivity,
                            "有新版本 ${config.version} 可用",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                binding.btnAnnouncement.text = "📢 公告"
            }
        }
    }
}
