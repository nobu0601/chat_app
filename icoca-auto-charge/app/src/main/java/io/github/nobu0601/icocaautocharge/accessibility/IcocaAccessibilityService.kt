package io.github.nobu0601.icocaautocharge.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.balance.BalanceTextParser
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * モバイルICOCA アプリの画面を読み、必要に応じてチャージ画面まで遷移を補助するサービス。
 *
 * ### 何をするか
 * - ICOCA アプリの画面から残高テキストを読む
 * - ユーザーが「チャージ」を選んだときだけ、チャージ画面までの遷移と金額選択を補助する
 *
 * ### 何をしないか（指示書 §2, §9, §11）
 * - 決済の実行。決済確認画面に着いたらユーザーに引き渡して終わる
 * - 認証（3Dセキュア・生体・パスワード）の自動入力。検知したら即停止する
 * - 座標指定のタップ。text / contentDescription / viewId でしかノードを掴まない
 * - ICOCA アプリ以外の監視。`accessibility_service_config.xml` の packageNames で
 *   ICOCA アプリだけに限定している
 * - 読み取った内容の永続化・送信。残高の抽出と画面判定にのみ使う
 */
class IcocaAccessibilityService : AccessibilityService() {

    private var session: AutomationSession? = null

    /** 初回検出時に記録した ICOCA アプリの署名（あるべき値）。 */
    private var expectedSignature: String? = null

    /** いまインストールされている ICOCA アプリの署名（実際の値）。 */
    private var actualSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.onServiceConnected(this)
        SecureLog.i(SecureLog.Tag.AUTOMATION, "accessibility service connected")
        refreshSignatures()
    }

    /**
     * 署名を読み直す。
     *
     * expected は初回に記録した値、actual はいまインストールされている値。
     * この2つを突き合わせるからこそ、別アプリへの差し替えを検知できる。
     * 同じ値を両側に渡すと検査が素通りしてしまう。
     */
    private fun refreshSignatures() {
        val locator = IcocaApp.locator ?: return
        expectedSignature = locator.cachedIcocaSignature
        actualSignature = runCatching { locator.probe.detect().signatureSha256 }
            .onFailure { SecureLog.e("failed to read ICOCA signature", it) }
            .getOrNull()
    }

    override fun onDestroy() {
        AccessibilityBridge.onServiceDisconnected()
        session = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // システムからの中断要求。進行中のセッションは安全側で畳む。
        session?.finish(
            AutomationSession.Outcome.Stopped(
                ErrorReason.UNEXPECTED_SCREEN,
                "システムにより中断されました",
            ),
        )
        session = null
    }

    /** チャージフローから自動操作を開始する。 */
    fun beginSession(chargeAmountYen: Int, dryRun: Boolean): AutomationSession {
        // セッション開始のたびに読み直す。前回の起動以降に ICOCA が更新されている可能性があるため。
        refreshSignatures()
        val s = AutomationSession(chargeAmountYen, System.currentTimeMillis(), dryRun)
        session = s
        SecureLog.i(
            SecureLog.Tag.AUTOMATION,
            "automation session started amount=$chargeAmountYen dryRun=$dryRun",
        )
        return s
    }

    fun currentSession(): AutomationSession? = session

    fun endSession() {
        session = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        // packageNames で絞ってはいるが、念のためここでも確認する
        if (pkg != IcocaConstants.PACKAGE_NAME) return

        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            handleScreen(root, pkg)
        } catch (e: Exception) {
            SecureLog.e("accessibility event handling failed", e)
            session?.finish(
                AutomationSession.Outcome.Stopped(ErrorReason.UNKNOWN, "内部エラーが発生しました"),
            )
        }
    }

    private fun handleScreen(root: AccessibilityNodeInfo, pkg: String) {
        val now = System.currentTimeMillis()
        val nodes = NodeFinder.walk(root)
        val texts = nodes.mapNotNull { NodeFinder.visibleText(it) }
        if (texts.isEmpty()) {
            SecureLog.d(SecureLog.Tag.AUTOMATION, "no readable text on ICOCA screen")
            return
        }

        val screen = ScreenClassifier.classify(texts)
        AccessibilityBridge.publishScreen(screen)

        readBalance(texts, now)

        if (AccessibilityBridge.dumpEnabled) {
            AccessibilityBridge.publishDump(
                ScreenDump(
                    capturedAt = now,
                    packageName = pkg,
                    screen = screen,
                    nodes = nodes.take(MAX_DUMP_NODES).map { it.toSummary() },
                ),
            )
        }

        val active = session ?: return
        if (active.isFinished) return
        advance(active, root, screen, pkg, now)
    }

    /** 残高テキストを拾って共有する。セッションの有無に関係なく行う。 */
    private fun readBalance(texts: List<String>, now: Long) {
        val yen = BalanceTextParser.extractBalanceFromLines(texts, preferLabeled = true) ?: return
        val reading = BalanceReading(yen, now, BalanceSourceType.ACCESSIBILITY)
        AccessibilityBridge.publishBalance(reading)
        SecureLog.d(SecureLog.Tag.BALANCE, "read balance from ICOCA screen: $yen")
    }

    /** セッションを1ステップ進める。 */
    private fun advance(
        session: AutomationSession,
        root: AccessibilityNodeInfo,
        screen: IcocaScreen,
        pkg: String,
        now: Long,
    ) {
        session.onScreen(screen, now)

        val guard = SafetyGuard(expectedSignature)
        val verdict = guard.check(
            SafetyGuard.Context(
                packageName = pkg,
                actualSignature = actualSignature,
                screen = screen,
                stepCount = session.stepCount,
                millisSinceProgress = session.millisSinceProgress(now),
            ),
        )

        when (verdict) {
            is SafetyGuard.Verdict.Stop -> {
                session.finish(AutomationSession.Outcome.Stopped(verdict.reason, verdict.message))
                return
            }
            is SafetyGuard.Verdict.Finish -> {
                val outcome = if (verdict.screen == IcocaScreen.COMPLETED) {
                    AutomationSession.Outcome.Completed
                } else {
                    // 決済確認。ここから先はユーザーの操作（指示書 §9）
                    AutomationSession.Outcome.HandedToUser(verdict.screen)
                }
                session.finish(outcome)
                SecureLog.i(SecureLog.Tag.PAYMENT, "handing control to the user at $screen")
                return
            }
            SafetyGuard.Verdict.Proceed -> Unit
        }

        when (screen) {
            IcocaScreen.MAIN -> clickIfFound(session, root, CHARGE_ENTRY_LABELS, now, "charge entry")
            IcocaScreen.CHARGE_ENTRY ->
                clickIfFound(session, root, CHARGE_ENTRY_LABELS + CHARGE_METHOD_LABELS, now, "charge method")
            IcocaScreen.CHARGE_AMOUNT -> clickAmount(session, root, guard, now)
            IcocaScreen.PROCESSING -> Unit // 待つ
            else -> Unit
        }
    }

    private fun clickIfFound(
        session: AutomationSession,
        root: AccessibilityNodeInfo,
        labels: List<String>,
        now: Long,
        what: String,
    ) {
        val node = NodeFinder.findClickableByExactText(root, labels)
        if (node == null) {
            SecureLog.d(SecureLog.Tag.AUTOMATION, "no clickable node for $what")
            return
        }
        performClick(session, node, "$what:${NodeFinder.visibleText(node)}", now)
    }

    /**
     * 金額ボタンを押す。
     *
     * **設定したチャージ金額と完全に一致するラベルのボタンしか押さない**（指示書 §11）。
     * 一致するものが無ければ何もしない。近い金額で代用したりはしない。
     */
    private fun clickAmount(
        session: AutomationSession,
        root: AccessibilityNodeInfo,
        guard: SafetyGuard,
        now: Long,
    ) {
        val variants = guard.amountLabelVariants(session.chargeAmountYen)
        val node = NodeFinder.findClickableByExactText(root, variants)
        if (node == null) {
            SecureLog.w(
                SecureLog.Tag.AUTOMATION,
                "amount button for ${session.chargeAmountYen} not found",
            )
            return
        }
        val label = NodeFinder.visibleText(node)
        if (!guard.verifyAmountLabel(label, session.chargeAmountYen)) {
            session.finish(
                AutomationSession.Outcome.Stopped(
                    ErrorReason.AMOUNT_MISMATCH,
                    "選択しようとした金額が設定と一致しません",
                ),
            )
            return
        }
        performClick(session, node, "amount:$label", now)
    }

    private fun performClick(
        session: AutomationSession,
        node: AccessibilityNodeInfo,
        what: String,
        now: Long,
    ) {
        if (session.dryRun) {
            session.plannedClicks += what
            session.onStep(now)
            SecureLog.i(SecureLog.Tag.AUTOMATION, "[dryRun] would click $what")
            return
        }
        val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
        session.onStep(now)
        SecureLog.i(SecureLog.Tag.AUTOMATION, "click $what -> $ok")
        if (!ok) {
            session.finish(
                AutomationSession.Outcome.Stopped(
                    ErrorReason.UI_STRUCTURE_CHANGED,
                    "ボタンを操作できませんでした",
                ),
            )
        }
    }

    private fun AccessibilityNodeInfo.toSummary() = NodeSummary(
        className = className?.toString(),
        viewId = viewIdResourceName,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        clickable = isClickable,
        editable = isEditable,
    )

    private companion object {
        /**
         * メイン画面からチャージへ進むボタンの想定ラベル。
         * **実機検証（TECHNICAL_FEASIBILITY §3.4）の結果に合わせて更新すること。**
         * 現時点では推測であり、一致しなければ何も押さずに止まるだけなので安全。
         */
        val CHARGE_ENTRY_LABELS = listOf("チャージ", "チャージする", "入金", "入金（チャージ）")

        val CHARGE_METHOD_LABELS = listOf("クレジットカード", "登録済みのカード", "銀行口座")

        const val MAX_DUMP_NODES = 120
    }
}
