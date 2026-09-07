package io.github.nobu0601.icocaautocharge.icoca

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.SecureLog
import java.security.MessageDigest

/**
 * モバイルICOCA アプリを **読み取り専用で** 調べる（TECHNICAL_FEASIBILITY §3.1, §3.2）。
 *
 * 目的は2つ。
 *  1. インストールされているか、バージョンはいくつかを知る
 *  2. 「公開されている（exported かつ intent-filter を持つ）」コンポーネントを列挙し、
 *     チャージ画面へ遷移できる公式 Intent が存在するかを実機で確かめる
 *
 * **非公開コンポーネントを直接起動することはしない**（指示書 §2 / PROJECT_RESEARCH §1.5）。
 * ここで得られるのは PackageManager が誰にでも返す公開情報だけで、
 * ICOCA アプリに変更を加えることは一切ない。
 */
class IcocaAppProbe(private val context: Context) {

    data class AppInfo(
        val installed: Boolean,
        val versionName: String?,
        val versionCode: Long?,
        val signatureSha256: String?,
        val enabled: Boolean,
    )

    data class ExportedComponent(
        val className: String,
        val actions: List<String>,
        val categories: List<String>,
        val schemes: List<String>,
    )

    fun detect(): AppInfo = try {
        val pm = context.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                IcocaConstants.PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(IcocaConstants.PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        AppInfo(
            installed = true,
            versionName = pkg.versionName,
            versionCode = pkg.longVersionCode,
            signatureSha256 = pkg.signingInfo?.let { info ->
                val sigs = if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
            },
            enabled = pkg.applicationInfo?.enabled ?: true,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        SecureLog.d(SecureLog.Tag.MONITOR, "ICOCA app not installed: ${e.message}")
        AppInfo(installed = false, versionName = null, versionCode = null, signatureSha256 = null, enabled = false)
    } catch (e: Exception) {
        SecureLog.e("failed to probe ICOCA app", e)
        AppInfo(installed = false, versionName = null, versionCode = null, signatureSha256 = null, enabled = false)
    }

    /**
     * 公開されている Activity を列挙する。
     *
     * PackageManager の queryIntentActivities は「その Intent を処理すると宣言している
     * exported なコンポーネント」しか返さないため、非公開の内部画面は出てこない。
     */
    fun listExportedActivities(): List<ExportedComponent> {
        val pm = context.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN),
            Intent(Intent.ACTION_VIEW),
            Intent(Intent.ACTION_SEND),
        )
        val found = LinkedHashMap<String, ExportedComponent>()
        for (probe in probes) {
            probe.setPackage(IcocaConstants.PACKAGE_NAME)
            val results: List<ResolveInfo> = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(probe, 0)
                }
            } catch (e: Exception) {
                SecureLog.e("queryIntentActivities failed", e)
                emptyList()
            }
            for (ri in results) {
                val name = ri.activityInfo?.name ?: continue
                val existing = found[name]
                val action = probe.action?.let { listOf(it) } ?: emptyList()
                found[name] = ExportedComponent(
                    className = name,
                    actions = ((existing?.actions ?: emptyList()) + action).distinct(),
                    categories = (existing?.categories ?: emptyList()) +
                        (probe.categories?.toList() ?: emptyList()),
                    schemes = existing?.schemes ?: emptyList(),
                )
            }
        }
        return found.values.toList()
    }

    /**
     * よくある URL スキームを総当たりして、ICOCA アプリが受け取るものがあるかを見る。
     *
     * 起動はしない。`queryIntentActivities` で「解決できるか」を確かめるだけ。
     */
    fun probeUrlSchemes(): List<String> {
        val pm = context.packageManager
        val candidates = listOf(
            "icoca", "icocaapp", "mobileicoca", "westjr", "jrwest", "wester",
        )
        return candidates.filter { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://"))
            intent.setPackage(IcocaConstants.PACKAGE_NAME)
            val list = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            } catch (e: Exception) {
                emptyList()
            }
            list.isNotEmpty()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }
}
