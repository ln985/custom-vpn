package com.wzydqq.icu.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wzydqq.icu.AnnouncementManager
import com.wzydqq.icu.App
import com.wzydqq.icu.databinding.ActivityMainBinding
import com.wzydqq.icu.location.LocationStore
import com.wzydqq.icu.vpn.LocationVpnService
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.ssl.JKS
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var vpnActive = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "需要 VPN 权限才能使用位置伪装", Toast.LENGTH_LONG).show()
        }
    }

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
        updateVpnStatus()
    }

    private fun setupUI() {
        updateLocationDisplay()

        binding.btnSelectLocation.setOnClickListener {
            startActivity(Intent(this, LocationPickerActivity::class.java))
        }

        binding.btnToggleVpn.setOnClickListener {
            if (vpnActive) {
                LocationVpnService.stop(this)
                vpnActive = false
                Toast.makeText(this, "位置伪装已关闭", Toast.LENGTH_SHORT).show()
                updateVpnStatus()
            } else {
                if (!LocationStore.hasLocation(this)) {
                    Toast.makeText(this, "请先选择伪装位置", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prepareNetBare()
            }
        }

        binding.btnClearLocation.setOnClickListener {
            LocationVpnService.stop(this)
            vpnActive = false
            LocationStore.clear(this)
            updateLocationDisplay()
            updateVpnStatus()
            Toast.makeText(this, "位置已清除", Toast.LENGTH_SHORT).show()
        }

        binding.btnAnnouncement.setOnClickListener {
            startActivity(Intent(this, AnnouncementActivity::class.java))
        }
    }

    /**
     * 准备 NetBare：检查证书 -> VPN 权限 -> 启动
     */
    private fun prepareNetBare() {
        // 1. 检查并安装自签证书
        if (!JKS.isInstalled(this, App.JSK_ALIAS)) {
            try {
                JKS.install(this, App.JSK_ALIAS, App.JSK_PASSWORD)
                Toast.makeText(this, "请安装证书后重试", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "证书安装失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }

        // 2. 检查 VPN 权限
        val vpnIntent = NetBare.get().prepare()
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        LocationVpnService.start(this)
        vpnActive = true
        Toast.makeText(this, "位置伪装已开启 ✅", Toast.LENGTH_SHORT).show()
        updateVpnStatus()
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
        } else {
            binding.tvCurrentLocation.text = "尚未设置伪装位置\n点击下方按钮选择"
        }
    }

    private fun updateVpnStatus() {
        val location = LocationStore.get(this)
        if (vpnActive) {
            binding.tvStatus.text = "✅ VPN 位置伪装已开启"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnToggleVpn.text = "⏹️ 停止伪装"
        } else if (location != null) {
            binding.tvStatus.text = "⏸️ VPN 伪装未开启"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            binding.btnToggleVpn.text = "▶️ 开启 VPN 伪装"
        } else {
            binding.tvStatus.text = "⚪ 未设置伪装位置"
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
            binding.btnToggleVpn.text = "▶️ 开启 VPN 伪装"
        }
    }

    private fun loadAnnouncement() {
        lifecycleScope.launch {
            val config = AnnouncementManager.fetchConfig()
            if (config != null && config.enabled) {
                binding.btnAnnouncement.text = "📢 公告 (${config.notices.size})"
                if (config.forceUpdate && config.version.isNotEmpty()) {
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
                    if (currentVersion != config.version) {
                        Toast.makeText(this@MainActivity, "有新版本 ${config.version} 可用", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                binding.btnAnnouncement.text = "📢 公告"
            }
        }
    }
}
