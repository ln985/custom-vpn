package com.wzydqq.icu

import android.app.Application
import android.content.Context
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareUtils
import com.github.megatronking.netbare.ssl.JKS

class App : Application() {

    companion object {
        const val JSK_ALIAS = "LocationChanger"
        const val JSK_PASSWORD = "LocationChanger"

        lateinit var instance: App
            private set
    }

    private lateinit var jks: JKS

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 创建自签证书（用于 HTTPS MITM）
        jks = JKS(this, JSK_ALIAS, JSK_PASSWORD.toCharArray(), JSK_ALIAS, JSK_ALIAS,
                JSK_ALIAS, JSK_ALIAS, JSK_ALIAS)

        // 初始化 NetBare
        NetBare.get().attachApplication(this, BuildConfig.DEBUG)
    }

    fun getJKS(): JKS = jks

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Android Q+ 需要 unseal 反射限制
        if (NetBareUtils.isAndroidQ()) {
            try {
                val clazz = Class.forName("me.weishu.reflection.Reflection")
                val method = clazz.getDeclaredMethod("unseal", Context::class.java)
                method.invoke(null, base)
            } catch (e: Exception) {
                // 可选：不强制要求
            }
        }
    }
}
