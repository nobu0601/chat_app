package io.github.nobu0601.icocaautocharge.monitor

import io.github.nobu0601.icocaautocharge.accessibility.AccessibilityBridge
import io.github.nobu0601.icocaautocharge.accessibility.AutomationSession
import io.github.nobu0601.icocaautocharge.balance.BalanceRepository
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.core.TimeProvider
import io.github.nobu0601.icocaautocharge.data.db.ChargeHistoryEntity
import io.github.nobu0601.icocaautocharge.data.repo.HistoryRepository
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings
import io.github.nobu0601.icocaautocharge.data.settings.FlowStateRepository
import io.github.nobu0601.icocaautocharge.data.settings.SettingsRepository
import io.github.nobu0601.icocaautocharge.domain.AutomationMethod
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.ChargeDecision
import io.github.nobu0601.icocaautocharge.domain.ChargeDecisionEngine
import io.github.nobu0601.icocaautocharge.domain.ChargeState
import io.github.nobu0601.icocaautocharge.domain.ChargeStateMachine
import io.github.nobu0601.icocaautocharge.domain.ChargeStatus
import io.github.nobu0601.icocaautocharge.domain.ErrorReason
import io.github.nobu0601.icocaautocharge.icoca.IcocaAppProbe
import io.github.nobu0601.icocaautocharge.icoca.IcocaLauncher
import io.github.nobu0601.icocaautocharge.notify.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * チャージフローの司令塔（ARCHITECTURE §1）。
 *
 * **状態を変える処理はすべてここを通る。** 呼び出し元（Worker / Activity / Service）は
 * ここに依頼するだけで、自分では state を書き換えない。
 * 二重チャージ防止の要は「判断の場所が1つしかないこと」なので、この一点集中は意図的なもの。
 *
 * さらに [mutex] で直列化しているため、Worker と UI が同時に走っても
 * 試行が2つ生まれることはない。
 */
class ChargeFlowCoordinator(
    private val settingsRepo: SettingsRepository,
    private val flowState: FlowStateRepository,
    private val history: HistoryRepository,
    private val balanceRepo: BalanceRepository,
    private val notifications: Notifications,
    private val launcher: IcocaLauncher,
    private val probe: IcocaAppProbe,
    private val conditions: DeviceConditions,
    private val time: TimeProvider = TimeProvider.System,
) {

    private val stateMachine = ChargeStateMachine()
    private val mutex = Mutex()

    data class CheckResult(
        val balance: BalanceReading?,
        val decision: ChargeDecision,
        val startedAttempt: Boolean,
        /**
         * 通知を待たずに ICOCA アプリを起動し、自動操作を始めたか。
         * true のとき、呼び出し元は [superviseAndVerify] で見届ける責任を負う。
         */
        val autoStarted: Boolean = false,
    )

    /**
     * 残高を確認し、必要ならチャージ処理を開始する。
     *
     * WorkManager からも「今すぐ確認」からも呼ばれる入口。
     *
     * 既定では通知を出すところまでで、ICOCA アプリの起動はユーザーが通知を
     * タップした先の Activity が行う（バックグラウンド起動制限のため）。
     * ただし設定で「チャージ前確認」を OFF にしている場合は、
     * ユーザー補助サービス経由でここから直接起動する（[tryAutoStart]）。
     */
    suspend fun runCheck(trigger: String): CheckResult = mutex.withLock {
        val now = time.nowMillis()
        SecureLog.i(SecureLog.Tag.MONITOR, "check start trigger=$trigger")

        rememberIcocaSignature()
        expireStaleAttempt(now)

        val outcome = balanceRepo.refresh()
        val balance = outcome.reading
        val settings = settingsRepo.current()
        val attempt = flowState.currentAttempt()

        val decision = ChargeDecisionEngine.decide(
            ChargeDecisionEngine.Input(
                settings = settings,
                balance = balance,
                nowMillis = now,
                currentAttempt = attempt,
                lastTerminalAtMillis = flowState.currentLastTerminalAt(),
                chargedTodayYen = history.chargedTodayYen(now),
                chargedThisMonthYen = history.chargedThisMonthYen(now),
                isUnmeteredNetwork = conditions.isUnmeteredNetwork(),
                isCharging = conditions.isCharging(),
            ),
        )

        val skipReason = (decision as? ChargeDecision.Skip)?.reason?.name
        flowState.saveCheckedAt(now, skipReason)
        runCatching { history.pruneSamples(now) }

        if (decision !is ChargeDecision.Proceed) {
            SecureLog.i(SecureLog.Tag.MONITOR, "check skipped: $skipReason")
            return@withLock CheckResult(balance, decision, startedAttempt = false)
        }

        val started = beginAttempt(
            balanceYen = decision.balanceYen,
            threshold = settings.thresholdYen,
            amount = decision.amountYen,
            balanceSource = balance?.source,
            now = now,
        )
        val autoStarted = started && tryAutoStart(settings, now)
        CheckResult(balance, decision, started, autoStarted)
    }

    /**
     * ユーザーの操作を待たずに ICOCA アプリを開き、自動操作を始める。
     *
     * 次の条件がすべて揃ったときだけ動く。ひとつでも欠けたら何もせず false を返し、
     * 通常どおり「通知をタップして開始する」経路に委ねる。
     *
     *  - 設定で「チャージ前確認」が OFF
     *  - 自動操作が有効で、同意済み
     *  - ユーザー補助サービスが生きている（起動はこのサービス経由でしかできない）
     *  - ICOCA アプリがインストールされている
     */
    private suspend fun tryAutoStart(settings: AppSettings, now: Long): Boolean {
        if (settings.confirmBeforeCharge) return false
        if (!settings.automationEnabled || !settings.automationConsented) return false
        if (!launcher.isInstalled()) return false
        val service = AccessibilityBridge.service() ?: run {
            SecureLog.i(
                SecureLog.Tag.AUTOMATION,
                "auto start requested but the accessibility service is not running",
            )
            return false
        }

        val attempt = flowState.currentAttempt() ?: return false
        val pending = stateMachine.transition(attempt, ChargeState.CHARGE_PENDING, now)
        if (pending !is ChargeStateMachine.Result.Accepted) return false
        flowState.saveAttempt(pending.attempt)
        updateHistory(pending.attempt.historyId) { it.copy(status = ChargeStatus.PENDING) }

        // ICOCA を開く前にセッションを張る。開いてからでは最初の画面遷移を取りこぼす。
        service.beginSession(
            chargeAmountYen = settings.chargeAmountYen,
            dryRun = AccessibilityBridge.dryRun,
            autoConfirmPayment = settings.autoConfirmPayment,
        )
        if (!service.launchIcoca()) {
            // 起動できなかった。セッションだけ畳む。
            // 状態は CHARGE_PENDING のままでよい。通知の「チャージ」から
            // startCharge() を呼べば、そこから普通に始められる。
            service.endSession()
            SecureLog.w(SecureLog.Tag.AUTOMATION, "auto start failed; falling back to the notification")
            return false
        }
        notifications.cancelLowBalance()
        SecureLog.i(SecureLog.Tag.MONITOR, "charge flow auto-started without a user tap")
        return true
    }

    /**
     * 低残高を検知して試行を作る。通知もここで出す。
     *
     * @return 試行を作ったか。既に進行中なら false（二重チャージ防止その1）。
     */
    private suspend fun beginAttempt(
        balanceYen: Int,
        threshold: Int,
        amount: Int,
        balanceSource: io.github.nobu0601.icocaautocharge.domain.BalanceSourceType?,
        now: Long,
    ): Boolean {
        val current = flowState.currentAttempt()
        val result = stateMachine.detect(current, balanceYen, threshold, amount, now)
        if (result is ChargeStateMachine.Result.Rejected) {
            SecureLog.i(SecureLog.Tag.MONITOR, "attempt not started: ${result.reason}")
            return false
        }
        val attempt = (result as ChargeStateMachine.Result.Accepted).attempt

        val historyId = history.insert(
            ChargeHistoryEntity(
                timestamp = now,
                balanceBefore = balanceYen,
                threshold = threshold,
                chargeAmount = amount,
                status = ChargeStatus.DETECTED,
                balanceSource = balanceSource,
            ),
        )
        flowState.saveAttempt(attempt.copy(historyId = historyId))

        SecureLog.i(
            SecureLog.Tag.MONITOR,
            "charge detected balance=$balanceYen threshold=$threshold amount=$amount id=$historyId",
        )
        notifications.showLowBalance(balanceYen, threshold, amount)
        return true
    }

    /**
     * ユーザーが「チャージ」を選んだときに呼ぶ。
     *
     * **必ず前面の Activity から呼ぶこと。** ここで ICOCA アプリを起動するため、
     * バックグラウンドから呼ぶと Android にブロックされる（PROJECT_RESEARCH §2.6）。
     */
    suspend fun startCharge(): StartResult = mutex.withLock {
        val now = time.nowMillis()
        val attempt = flowState.currentAttempt()
            ?: return@withLock StartResult.Failed("チャージ対象の処理がありません")

        if (attempt.state != ChargeState.CHARGE_DETECTED &&
            attempt.state != ChargeState.CHARGE_PENDING
        ) {
            return@withLock StartResult.Failed("いまは開始できる状態ではありません（${attempt.state}）")
        }

        if (!launcher.isInstalled()) {
            fail(ErrorReason.ICOCA_APP_NOT_FOUND, "モバイルICOCAアプリが見つかりません", now)
            return@withLock StartResult.Failed("モバイルICOCAアプリが見つかりません")
        }

        val pending = stateMachine.transition(
            attempt,
            ChargeState.CHARGE_PENDING,
            now,
            userConfirmed = true,
        )
        if (pending is ChargeStateMachine.Result.Accepted) {
            flowState.saveAttempt(pending.attempt)
            updateHistory(pending.attempt.historyId) {
                it.copy(status = ChargeStatus.PENDING, userConfirmed = true)
            }
        }

        val settings = settingsRepo.current()
        val useAutomation = settings.automationEnabled &&
            settings.automationConsented &&
            AccessibilityBridge.isRunning()

        // 自動操作を使う場合は、ICOCA を起動する前にセッションを張っておく。
        // 起動後に張ると最初の画面遷移を取りこぼす。
        if (useAutomation) {
            AccessibilityBridge.service()?.beginSession(
                chargeAmountYen = settings.chargeAmountYen,
                dryRun = AccessibilityBridge.dryRun,
                autoConfirmPayment = settings.autoConfirmPayment,
            )
        }

        return@withLock when (val r = launcher.launchMain()) {
            is IcocaLauncher.Result.Launched -> {
                notifications.cancelLowBalance()
                StartResult.Launched(
                    amountYen = settings.chargeAmountYen,
                    automation = useAutomation,
                )
            }
            is IcocaLauncher.Result.Failed -> {
                fail(r.reason, "モバイルICOCAアプリを起動できませんでした", now)
                StartResult.Failed("モバイルICOCAアプリを起動できませんでした")
            }
        }
    }

    sealed interface StartResult {
        data class Launched(val amountYen: Int, val automation: Boolean) : StartResult
        data class Failed(val message: String) : StartResult
    }

    /**
     * ICOCA アプリ側の操作を見届ける。[ChargeFlowService] から呼ばれる。
     *
     * 自動操作が有効なら、セッションの結果が出るまで待つ。
     * 無効なら「ユーザーが自分でチャージするのを待つ」だけなので、
     * 一定時間後に残高の再確認へ進む。
     */
    suspend fun superviseAndVerify(automation: Boolean) {
        val outcome = if (automation) waitForAutomation() else null
        if (outcome is AutomationSession.Outcome.Stopped) {
            mutex.withLock {
                val now = time.nowMillis()
                val attempt = flowState.currentAttempt() ?: return@withLock
                moveToCharging(attempt, now, AutomationMethod.ACCESSIBILITY)
                fail(outcome.reason, outcome.message, now)
            }
            notifications.showError(outcome.message)
            AccessibilityBridge.service()?.endSession()
            return
        }

        mutex.withLock {
            val now = time.nowMillis()
            val attempt = flowState.currentAttempt() ?: return@withLock
            moveToCharging(
                attempt,
                now,
                if (automation) AutomationMethod.ACCESSIBILITY else AutomationMethod.MANUAL,
            )
        }

        // 決済と本人認証はユーザーの操作。完了を待ってから残高を確認する。
        delay(VERIFY_DELAY_MILLIS)
        AccessibilityBridge.service()?.endSession()
        verifyNow()
    }

    /** 自動操作セッションの決着を待つ。決着しなければ null。 */
    private suspend fun waitForAutomation(): AutomationSession.Outcome? {
        val deadline = time.nowMillis() + AUTOMATION_TIMEOUT_MILLIS
        while (time.nowMillis() < deadline) {
            val session = AccessibilityBridge.service()?.currentSession()
            val outcome = session?.outcome
            if (outcome != null) {
                SecureLog.i(SecureLog.Tag.AUTOMATION, "automation finished: $outcome")
                return outcome
            }
            delay(POLL_INTERVAL_MILLIS)
        }
        SecureLog.i(SecureLog.Tag.AUTOMATION, "automation timed out; falling back to manual verify")
        return null
    }

    private suspend fun moveToCharging(
        attempt: io.github.nobu0601.icocaautocharge.domain.ChargeAttempt,
        now: Long,
        method: AutomationMethod,
    ) {
        if (attempt.state != ChargeState.CHARGE_PENDING) return
        val r = stateMachine.transition(attempt, ChargeState.CHARGING, now, automationMethod = method)
        if (r is ChargeStateMachine.Result.Accepted) {
            flowState.saveAttempt(r.attempt)
            updateHistory(r.attempt.historyId) {
                it.copy(status = ChargeStatus.CHARGING, automationMethod = method)
            }
        }
    }

    /**
     * 残高を再取得して成否を判定する（ARCHITECTURE §3.2）。
     *
     * 残高が取れないときは **失敗にも成功にもせず** `SUCCESS_UNVERIFIED` にする。
     * 「取れなかった＝失敗」にすると、実際にはチャージ済みなのに
     * クールダウン明けに再チャージしてしまう恐れがあるため。
     */
    suspend fun verifyNow() = mutex.withLock {
        val now = time.nowMillis()
        val attempt = flowState.currentAttempt() ?: return@withLock
        if (attempt.state != ChargeState.CHARGING && attempt.state != ChargeState.VERIFYING) {
            return@withLock
        }

        if (attempt.state == ChargeState.CHARGING) {
            val r = stateMachine.transition(attempt, ChargeState.VERIFYING, now)
            if (r is ChargeStateMachine.Result.Accepted) flowState.saveAttempt(r.attempt)
        }
        val verifying = flowState.currentAttempt() ?: return@withLock

        val after = balanceRepo.refresh().reading
        val before = verifying.balanceAtDetection
        val expected = (before ?: 0) + verifying.chargeAmount

        when {
            after == null -> finishUnverified(verifying, now)
            before != null && after.balanceYen >= expected -> {
                succeed(verifying, after.balanceYen, now)
            }
            before != null && after.balanceYen == before -> {
                // 残高が動いていない＝チャージされていない
                terminateFailed(verifying, ErrorReason.NOT_CHARGED, "残高が変化していません", now)
                notifications.showError("チャージが行われていないようです（残高が変わっていません）")
            }
            else -> finishUnverified(verifying, now)
        }
    }

    private suspend fun succeed(
        attempt: io.github.nobu0601.icocaautocharge.domain.ChargeAttempt,
        afterYen: Int,
        now: Long,
    ) {
        val r = stateMachine.transition(attempt, ChargeState.SUCCESS, now)
        if (r is ChargeStateMachine.Result.Accepted) flowState.saveAttempt(r.attempt)
        updateHistory(attempt.historyId) {
            it.copy(status = ChargeStatus.SUCCESS, balanceAfter = afterYen, completedAt = now)
        }
        SecureLog.i(
            SecureLog.Tag.PAYMENT,
            "charge verified: before=${attempt.balanceAtDetection} after=$afterYen",
        )
        notifications.showSuccess(attempt.balanceAtDetection, afterYen, attempt.chargeAmount)
    }

    private suspend fun finishUnverified(
        attempt: io.github.nobu0601.icocaautocharge.domain.ChargeAttempt,
        now: Long,
    ) {
        val r = stateMachine.transition(attempt, ChargeState.SUCCESS, now)
        if (r is ChargeStateMachine.Result.Accepted) flowState.saveAttempt(r.attempt)
        updateHistory(attempt.historyId) {
            it.copy(status = ChargeStatus.SUCCESS_UNVERIFIED, completedAt = now)
        }
        SecureLog.i(SecureLog.Tag.PAYMENT, "charge finished but balance could not be verified")
        notifications.showSuccess(null, null, attempt.chargeAmount)
    }

    /** 通知の「後で」。ユーザーの意思なので失敗ではなくキャンセルとして残す。 */
    suspend fun onUserPostponed() = mutex.withLock {
        val now = time.nowMillis()
        val attempt = flowState.currentAttempt() ?: return@withLock
        if (!attempt.state.isActive) return@withLock
        val r = stateMachine.transition(
            attempt,
            ChargeState.FAILED,
            now,
            errorReason = ErrorReason.CANCELLED_BY_USER,
        )
        if (r is ChargeStateMachine.Result.Accepted) flowState.saveAttempt(r.attempt)
        updateHistory(attempt.historyId) {
            it.copy(
                status = ChargeStatus.CANCELLED,
                errorReason = ErrorReason.CANCELLED_BY_USER,
                completedAt = now,
            )
        }
        SecureLog.i(SecureLog.Tag.MONITOR, "user postponed; cooldown starts")
    }

    /** ユーザー操作で強制的に IDLE へ戻す（Debug 画面）。 */
    suspend fun resetState() = mutex.withLock {
        flowState.clearAttempt()
        SecureLog.i(SecureLog.Tag.MONITOR, "state reset by user")
    }

    /** 放置された試行を終端に落とす（ARCHITECTURE §3.1-4）。 */
    private suspend fun expireStaleAttempt(now: Long) {
        val attempt = flowState.currentAttempt() ?: return
        val r = stateMachine.timeoutIfStale(attempt, now) ?: return
        if (r is ChargeStateMachine.Result.Accepted) {
            flowState.saveAttempt(r.attempt)
            updateHistory(attempt.historyId) {
                it.copy(
                    status = ChargeStatus.FAILED,
                    errorReason = ErrorReason.TIMEOUT,
                    completedAt = now,
                )
            }
            SecureLog.w(SecureLog.Tag.MONITOR, "attempt timed out and was released")
        }
    }

    private suspend fun fail(reason: ErrorReason, message: String, now: Long) {
        val attempt = flowState.currentAttempt() ?: return
        terminateFailed(attempt, reason, message, now)
    }

    private suspend fun terminateFailed(
        attempt: io.github.nobu0601.icocaautocharge.domain.ChargeAttempt,
        reason: ErrorReason,
        message: String,
        now: Long,
    ) {
        val r = stateMachine.transition(attempt, ChargeState.FAILED, now, errorReason = reason)
        if (r is ChargeStateMachine.Result.Accepted) flowState.saveAttempt(r.attempt)
        updateHistory(attempt.historyId) {
            it.copy(status = ChargeStatus.FAILED, errorReason = reason, completedAt = now, note = message)
        }
        SecureLog.w(SecureLog.Tag.ERROR, "attempt failed: $reason / $message")
    }

    private suspend fun updateHistory(
        id: Long,
        transform: (ChargeHistoryEntity) -> ChargeHistoryEntity,
    ) {
        if (id == 0L) return
        val row = history.byId(id) ?: return
        runCatching { history.update(transform(row)) }
            .onFailure { SecureLog.e("failed to update history id=$id", it) }
    }

    /** ICOCA アプリの署名を初回だけ覚える。以後のなりすまし検知に使う（指示書 §11）。 */
    private suspend fun rememberIcocaSignature() {
        val info = probe.detect()
        val sig = info.signatureSha256 ?: return
        flowState.rememberIcocaSignatureIfAbsent(sig)
    }

    private companion object {
        /** 自動操作の決着を待つ上限。 */
        const val AUTOMATION_TIMEOUT_MILLIS = 3 * 60 * 1000L

        /** ユーザーが決済を終えるのを待ってから残高を見に行くまでの時間。 */
        const val VERIFY_DELAY_MILLIS = 90 * 1000L

        const val POLL_INTERVAL_MILLIS = 500L
    }
}
