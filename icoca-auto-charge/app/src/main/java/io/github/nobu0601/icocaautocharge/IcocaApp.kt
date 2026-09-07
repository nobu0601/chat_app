package io.github.nobu0601.icocaautocharge

import android.app.Application
import io.github.nobu0601.icocaautocharge.core.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IcocaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val loc = ServiceLocator(this)
        locator = loc

        loc.notifications.ensureChannels()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                loc.cachedIcocaSignature = loc.flowState.currentIcocaSignature()
                    ?: loc.probe.detect().signatureSha256?.also {
                        loc.flowState.rememberIcocaSignatureIfAbsent(it)
                    }
                loc.history.pruneSamples(System.currentTimeMillis())
                loc.scheduler.reschedule(loc.settingsRepo.current())
            } catch (e: Exception) {
                SecureLog.e("application init failed", e)
            }
        }
    }

    companion object {
        /**
         * Worker / Receiver / AccessibilityService からアクセスするための参照。
         * これらはシステムが生成するためコンストラクタで依存を渡せない。
         */
        @Volatile
        var locator: ServiceLocator? = null
            private set
    }
}
