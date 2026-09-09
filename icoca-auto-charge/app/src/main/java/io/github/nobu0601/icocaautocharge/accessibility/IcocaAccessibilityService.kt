package io.github.nobu0601.icocaautocharge.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
    fun beginSession(
        chargeAmountYen: Int,
        dryRun: Boolean,
        autoConfirmPayment: Boolean = false,
    ): AutomationSession {
        // セッション開始のたびに読み直す。前回の起動以降に ICOCA が更新されている可能性があるため。
        refreshSignatures()
        val s = AutomationSession(
            chargeAmountYen = chargeAmountYen,
            startedAt = System.currentTimeMillis(),
            dryRun = dryRun,
            autoConfirmPayment = autoConfirmPayment,
        )
        session = s
        SecureLog.i(
            SecureLog.Tag.AUTOMATION,
            "automation session started amount=$chargeAmountYen dryRun=$dryRun " +
                "autoConfirm=$autoConfirmPayment",
        )
        return s
    }

    /**
     * ICOCA アプリを前面に出す。
     *
     * 通常、バックグラウンドから他アプリの Activity を起動することは Android にブロックされる。
     * ユーザー補助サービスはシステムにバインドされているため、この経路なら起動できる。
     * ただし OS のバージョンや保護設定によっては拒否されうるので、
     * 失敗しても例外を投げず false を返し、呼び出し側が通知にフォールバックできるようにする。
     */
    fun launchIcoca(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(IcocaConstants.PACKAGE_NAME)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            SecureLog.i(SecureLog.Tag.AUTOMATION, "launched ICOCA from the accessibility service")
            true
        } catch (e: Exception) {
            SecureLog.e("failed to launch ICOCA from the accessibility service", e)
            false
        }
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
        advance(active, root, texts, screen, pkg, now)
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
        texts: List<String>,
        screen: IcocaScreen,
        pkg: String,
        now: Long,
    ) {
        session.onScreen(screen, now)

        val guard = SafetyGuard(expectedSignature, session.autoConfirmPayment)
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
            // ここに来るのは autoConfirmPayment が有効なときだけ。
            // 無効なら SafetyGuard が Finish を返して、上の when で終わっている。
            IcocaScreen.PAYMENT_CONFIRM -> clickConfirm(session, root, texts, guard, now)
            IcocaScreen.PROCESSING -> Unit // 待つ
            else -> Unit
        }
    }

    /**
     * 決済の確定ボタンを押す。**このアプリで唯一、お金が動く操作。**
     *
     * 押す前に2つ確認し、どちらか欠けたら押さずに止める。
     *  1. 画面に設定どおりの金額が表示されていること
     *  2. 確定ボタンのラベルが既知のものと完全一致すること
     *
     * 見つからないときは、次に直せるようクリック可能なラベルをログに残す。
     */
    private fun clickConfirm(
        session: AutomationSession,
        root: AccessibilityNodeInfo,
        texts: List<String>,
        guard: SafetyGuard,
        now: Long,
    ) {
        if (!guard.isAmountVisibleOnScreen(texts, session.chargeAmountYen)) {
            session.finish(
                AutomationSession.Outcome.Stopped(
                    ErrorReason.AMOUNT_MISMATCH,
                    "決済画面に設定した金額が見当たらないため、確定しませんでした",
                ),
            )
            return
        }
        val node = NodeFinder.findClickableByExactText(root, CONFIRM_LABELS)
        if (node == null) {
            // 押さずに待てば、進展しないまま STALL_TIMEOUT で安全に終わる。
            // どのラベルを足すべきか分かるよう、押せるものを控えておく。
            val clickable = NodeFinder.walk(root)
                .filter { it.isClickable }
                .mapNotNull { NodeFinder.visibleText(it) }
            SecureLog.w(
                SecureLog.Tag.PAYMENT,
                "confirm button not found; clickable labels on this screen: $clickable",
            )
            return
        }
        performClick(session, node, "confirm:${NodeFinder.visibleText(node)}", now)
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
        // 実機の金額選択画面は、金額ボタンと支払いボタンが同じ画面にある。
        // 金額を押しただけでは進まないので、押し終えたら次は支払いへ進む。
        if (session.amountSelected) {
            proceedToPayment(session, root, now)
            return
        }

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
        session.markAmountSelected()
    }

    /**
     * 金額選択画面から支払いの確認へ進む。
     *
     * 実機のボタンは「****9804でチャージ」のようにカード番号が入るため、
     * 末尾一致で探す。**このボタンを押しても決済は確定せず、確認ダイアログが出るだけ。**
     * 確定するかどうかは、そのダイアログで [clickConfirm] が判断する。
     *
     * ラベルにカード番号の下4桁が含まれるので、ログには出さない（指示書 §17, §24）。
     */
    private fun proceedToPayment(
        session: AutomationSession,
        root: AccessibilityNodeInfo,
        now: Long,
    ) {
        val node = NodeFinder.findClickableByTextSuffix(root, PROCEED_TO_PAYMENT_SUFFIXES)
        if (node == null) {
            SecureLog.w(SecureLog.Tag.AUTOMATION, "payment button not found on the amount screen")
            return
        }
        performClick(session, node, "proceed to payment", now)
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
         * メイン画面からチャージへ進むボタンのラベル。
         * 実機は「チャージ」ちょうど（2026-09-09 / Pixel 8a で確認）。残りは保険。
         */
        val CHARGE_ENTRY_LABELS = listOf("チャージ", "チャージする", "入金", "入金（チャージ）")

        /**
         * 支払い方法を選ぶ画面があった場合のラベル。
         * 実機ではカードが選択済みで、この画面は出ずに金額選択へ直行する。
         */
        val CHARGE_METHOD_LABELS = listOf("クレジットカード", "登録済みのカード", "銀行口座")

        /**
         * 金額選択画面から支払い確認へ進むボタンの末尾。
         *
         * 実機は「****9804でチャージ」で、前半にカード番号が入るため末尾で照合する
         * （2026-09-09 / Pixel 8a で確認）。
         * **このボタンを押しても決済は確定せず、確認ダイアログが出るだけ。**
         */
        val PROCEED_TO_PAYMENT_SUFFIXES = listOf("でチャージ")

        /**
         * 決済を確定するボタンのラベル。
         *
         * 実機の「チャージ確認」ダイアログは「キャンセル」と「チャージする」の2択で、
         * 「チャージする」を 2026-09-09 / Pixel 8a で確認済み。残りは他バージョン向けの保険。
         *
         * 「はい」「OK」のような汎用語は入れない。金額が出ていることは別途確認しているが、
         * それでも汎用語で確定を押すのは危うい。
         * 一致しなければ押さずに止まるだけなので、外れていても安全側に倒れる。
         */
        val CONFIRM_LABELS = listOf(
            "チャージする", "決済する", "支払う", "確定する", "確定",
            "この内容でチャージする", "チャージを実行", "実行する",
        )

        const val MAX_DUMP_NODES = 120
    }
}
