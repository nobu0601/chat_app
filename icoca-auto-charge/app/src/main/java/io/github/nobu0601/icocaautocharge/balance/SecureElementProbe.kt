package io.github.nobu0601.icocaautocharge.balance

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.se.omapi.SEService
import io.github.nobu0601.icocaautocharge.core.SecureLog
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * OMAPI（Open Mobile API）でセキュアエレメントに届くかを **調べるだけ** の診断機能。
 *
 * 目的は `TECHNICAL_FEASIBILITY.md §3.6` の裏取り、すなわち
 * **「正規の方法では自端末のモバイルICOCAに触れない」ことを実機で確認する**こと。
 *
 * SE のアクセス制御（GlobalPlatform SE Access Control）により、
 * ICOCA のアプレットが第三者アプリにアクセス権を与えることはない。
 * ここでは Reader の一覧を取得するだけで、**アプレットを選択しようとはしない**。
 */
class SecureElementProbe(private val context: Context) {

    data class Report(
        val supported: Boolean,
        val connected: Boolean,
        val readers: List<String>,
        val note: String,
    )

    suspend fun probe(): Report {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Report(false, false, emptyList(), "この端末のAndroidバージョンではOMAPIを利用できません")
        }
        val executor = Executors.newSingleThreadExecutor()
        var service: SEService? = null
        return try {
            // 接続完了は isConnected を見て判断する（コールバックは握るだけ）
            service = SEService(context, executor) {
                SecureLog.d(SecureLog.Tag.BALANCE, "SEService connected")
            }
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MILLIS
            while (!service.isConnected && SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_INTERVAL_MILLIS)
            }
            if (!service.isConnected) {
                Report(
                    supported = true,
                    connected = false,
                    readers = emptyList(),
                    note = "セキュアエレメントに接続できませんでした" +
                        "（想定どおり。外部アプリからモバイルICOCAへは到達できません）",
                )
            } else {
                Report(
                    supported = true,
                    connected = true,
                    readers = service.readers.map { it.name },
                    note = "Readerの列挙まではできますが、ICOCAのアプレットはSE側のアクセス制御により開けません。" +
                        "本アプリはアプレットの選択を試みません。",
                )
            }
        } catch (e: Exception) {
            SecureLog.e("OMAPI probe failed", e)
            Report(true, false, emptyList(), "調査中にエラーが発生しました: ${e.javaClass.simpleName}")
        } finally {
            runCatching { service?.shutdown() }
            executor.shutdown()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
