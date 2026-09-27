package io.github.nobu0601.icocaautocharge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.balance.BalanceTextParser
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.LogRedactor
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * モバイルICOCA アプリの画面を読み、必要に応じてチャージ操作を補助するサービス。
 *
 * ### このクラスの役割は薄い（改修指示 §1, §5）
 *
 * 以前はここが全部やっていた。イベントを受けて、画面を分類して、安全性を判定して、
 * クリックまでコールバックの中で完結させていた。**それが止まる原因だった。**
 * イベントが1回来なければ、その先は永久に進まない。
 *
 * いまは3つしかしない。
 *
 *  1. 画面を読んで [ScreenSnapshot] にする（残高の抽出もここ）
 *  2. [AutomationEngine] を 300ms 周期で叩く
 *  3. イベントが来たら「画面が変わったかもしれない」と印を付けるだけ
 *
 * 判断は全部 [AutomationEngine] にある。
 *
 * ### 何をしないか（指示書 §2, §9, §11）
 * - 認証（3Dセキュア・生体・パスワード）の自動入力。検知したら即ユーザーへ引き渡す
 * - 座標指定のタップ。text / contentDescription / viewId でしかノードを掴まない
 * - ICOCA アプリ以外の監視。`accessibility_service_config.xml` の packageNames で限定
 * - 読み取った内容の永続化・送信
 */
class IcocaAccessibilityService : AccessibilityService(), GesturePerformer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private var engine: AutomationEngine? = null

    /** ロック解除を受け取るレシーバ。実行中に登録するのでここで持っておく。 */
    private var unlockReceiver: BroadcastReceiver? = null

    /** 残高を見に行っている最中のジョブ。二重に走らせない。 */
    private var probeJob: Job? = null

    /**
     * 直近のイベントで画面が変わったかもしれない、という印（改修指示 §5）。
     *
     * **これがイベントの唯一の役目。** 使わなくてもポーリングで進むので、
     * イベントの欠落・順序の入れ替わり・重複のどれにも影響されない。
     * 効果は「次の周期を待たずに1回早く見に行く」だけ。
     */
    private val screenChangedSignal = AtomicBoolean(false)

    /** 初回検出時に記録した ICOCA アプリの署名（あるべき値）。 */
    private var expectedSignature: String? = null

    /** いまインストールされている ICOCA アプリの署名（実際の値）。 */
    private var actualSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.onServiceConnected(this)
        SecureLog.i(SecureLog.Tag.AUTOMATION, "accessibility service connected")
        refreshSignatures()
        registerUnlockReceiver()
    }

    /**
     * 画面ロックの解除を受け取れるようにする。
     *
     * `ACTION_USER_PRESENT` はマニフェストに書いても届かないので、実行中に登録する。
     * このサービスはユーザー補助が有効な間ずっと生きているので、置き場所として都合がよい。
     * しかも残高を見るには ICOCA を開く必要があり、それができるのもこのサービスだけ。
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> onUnlocked()
                    // ロック画面を使っていない端末では USER_PRESENT が来ない。
                    // その場合だけ SCREEN_ON で拾う。ロック中は ICOCA の画面を
                    // 読めないので、鍵がかかっている間は何もしない。
                    Intent.ACTION_SCREEN_ON -> if (!isKeyguardLocked()) onUnlocked()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { unlockReceiver = receiver }
            .onFailure { SecureLog.e("failed to register the unlock receiver", it) }
    }

    private fun isKeyguardLocked(): Boolean = runCatching {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(true)

    /**
     * ロックが解けた。1日1回だけ残高を見に行く。
     *
     * 判断は [io.github.nobu0601.icocaautocharge.monitor.ChargeFlowCoordinator] が持つ。
     * ここは「解除された」という事実を渡すだけ。
     */
    private fun onUnlocked() {
        if (probeJob?.isActive == true) return
        val coordinator = IcocaApp.locator?.coordinator ?: return
        probeJob = scope.launch {
            // 解除直後はまだアニメーションの最中。少し置いてから開く。
            delay(UNLOCK_SETTLE_MILLIS)
            runCatching { coordinator.onUserPresent() }
                .onFailure { SecureLog.e("daily balance probe failed", it) }
        }
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
        unlockReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        unlockReceiver = null
        probeJob?.cancel()
        stopLoop()
        scope.cancel()
        AccessibilityBridge.onServiceDisconnected()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // システムからの中断要求。進行中のセッションは安全側で畳む。
        engine?.session?.finish(
            AutomationSession.Outcome.Stopped(
                io.github.nobu0601.icocaautocharge.domain.ErrorReason.UNEXPECTED_SCREEN,
                "システムにより中断されました",
            ),
        )
        stopLoop()
    }

    // --------------------------------------------------------------- probe

    /**
     * 残高を見るために ICOCA を開き、読み取れるまで待つ。
     *
     * ### なぜ開く必要があるのか
     *
     * **改札で使われたことは検知できない。** Android には自端末の FeliCa が
     * 使われたことを知る公開 API が無い（PROJECT_RESEARCH §2.2）。
     * 残高が減ったことを知る手段は「ICOCA を開いて画面を読む」以外に無い。
     *
     * 読み取り自体は [captureSnapshot] が勝手にやってくれるので、
     * ここでやるのは「開く」ことと「新しい値が出るまで待つ」ことだけ。
     *
     * @return 今回の起動で読めた残高。読めなければ null。
     */
    suspend fun probeBalance(timeoutMillis: Long = PROBE_TIMEOUT_MILLIS): BalanceReading? {
        val startedAt = System.currentTimeMillis()
        if (!launchIcoca()) {
            SecureLog.w(SecureLog.Tag.BALANCE, "balance probe could not launch ICOCA")
            return null
        }
        val deadline = startedAt + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            // セッションが無い間も、イベントのたびに captureSnapshot が走って残高を公開する。
            // 念のためこちらからも撮っておく（イベントが来ないことがあるため）。
            runCatching { captureSnapshot(System.currentTimeMillis()) }
            val reading = AccessibilityBridge.lastBalance.value
            // **今回の起動で読めた値だけを採用する。** 前回の残り物を掴むと、
            // 改札で減ったことに気づけないまま「まだ十分ある」と判断してしまう。
            if (reading != null && reading.observedAt >= startedAt) {
                SecureLog.i(SecureLog.Tag.BALANCE, "balance probe read a fresh value")
                return reading
            }
            delay(AutomationTimeouts.POLL_INTERVAL_MILLIS)
        }
        SecureLog.w(SecureLog.Tag.BALANCE, "balance probe timed out")
        return null
    }

    /** ICOCA を閉じてホームに戻す。残高が足りていて、何もする必要がなかったとき。 */
    fun returnHome(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)

    // ------------------------------------------------------------- session

    /** チャージフローから自動操作を開始する。 */
    fun beginSession(
        attemptId: Long,
        chargeAmountYen: Int,
        dryRun: Boolean,
        autoConfirmPayment: Boolean = false,
    ): AutomationSession {
        stopLoop()
        // セッション開始のたびに読み直す。前回の起動以降に ICOCA が更新されている可能性がある。
        refreshSignatures()

        val session = AutomationSession(
            sessionId = UUID.randomUUID().toString().take(8),
            attemptId = attemptId,
            startedAt = System.currentTimeMillis(),
            chargeAmountYen = chargeAmountYen,
            dryRun = dryRun,
            autoConfirmPayment = autoConfirmPayment,
        )
        val recorder = BridgeRecorder()
        engine = AutomationEngine(
            session = session,
            guard = SafetyGuard(expectedSignature, autoConfirmPayment),
            actualSignature = actualSignature,
            recorder = recorder,
        )

        AccessibilityBridge.resetRecords()
        recorder.log(
            AutomationLogKind.SCREEN,
            "開始 金額=$chargeAmountYen ドライラン=$dryRun 自動確定=$autoConfirmPayment",
            session.startedAt,
        )
        SecureLog.i(
            SecureLog.Tag.AUTOMATION,
            "automation session ${session.sessionId} started amount=$chargeAmountYen " +
                "dryRun=$dryRun autoConfirm=$autoConfirmPayment",
        )
        startLoop()
        return session
    }

    fun currentSession(): AutomationSession? = engine?.session

    fun endSession() {
        stopLoop()
        engine = null
        // **最後の状態は消さない。** 消すと Debug 画面の Matched Text /
        // Matched Node Clickable / Clickable Ancestor Depth が見られなくなり、
        // 止まった直後こそ必要な情報が失われる。次のセッション開始時に差し替わる。
    }

    // ---------------------------------------------------------------- loop

    /**
     * 自動操作のループ（改修指示 §4）。
     *
     * イベントではなくここが時計。300ms ごとに現在画面を取り直してエンジンを1手進める。
     * **300ms ごとにクリックするわけではない。** クリックは [ActionThrottle] が別途抑える。
     */
    private fun startLoop() {
        loop = scope.launch {
            while (isActive) {
                val session = engine?.session ?: break
                if (session.isFinished) {
                    publishStatus(session)
                    break
                }
                runCatching { step() }
                    .onFailure { SecureLog.e("automation tick failed", it) }
                delay(AutomationTimeouts.POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    private fun step() {
        val engine = this.engine ?: return
        val now = System.currentTimeMillis()
        // 印は読んだら倒す。使い道は「変化したかもしれない」という記録だけ。
        screenChangedSignal.set(false)

        val snapshot = captureSnapshot(now)
        AccessibilityBridge.publishForeground(snapshot != null)
        engine.tick(snapshot, now)
        publishStatus(engine.session)
    }

    private fun publishStatus(session: AutomationSession) {
        AccessibilityBridge.publishAutomation(
            AutomationStatusView.of(session, System.currentTimeMillis()),
        )
    }

    // ------------------------------------------------------------ snapshot

    /**
     * いまの画面を撮る。ICOCA が前面にいなければ null。
     *
     * セッションが無くても、残高を読むためだけに呼ばれることがある。
     */
    private fun captureSnapshot(now: Long): ScreenSnapshot? {
        val root: AccessibilityNodeInfo = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg != IcocaConstants.PACKAGE_NAME) return null

        val nodes = NodeFinder.walk(root)
        val texts = nodes.mapNotNull { NodeFinder.visibleText(it) }
        if (texts.isEmpty()) {
            // 中身がまだ無い画面。UNKNOWN として扱えるよう、空のまま返す。
            // ここで null を返すと「ICOCA が前面にいない」と誤解されてしまう。
            return ScreenSnapshot(
                capturedAt = now,
                packageName = pkg,
                screen = IcocaScreen.UNKNOWN,
                texts = emptyList(),
                nodes = emptyList(),
                access = NodeScreenAccess(root, this),
            )
        }

        val screen = ScreenClassifier.classify(texts)
        AccessibilityBridge.publishScreen(screen)
        readBalance(texts, now)

        val summaries = nodes.take(MAX_DUMP_NODES).map { it.toSummary() }
        // 自動操作中は設定に関係なくダンプを残す。実機で止まったとき、
        // そのときの画面構成が無いと原因が追えないため。
        if (AccessibilityBridge.dumpEnabled || engine?.session?.isFinished == false) {
            AccessibilityBridge.publishDump(ScreenDump(now, pkg, screen, summaries))
        }

        return ScreenSnapshot(
            capturedAt = now,
            packageName = pkg,
            screen = screen,
            texts = texts,
            nodes = summaries,
            access = NodeScreenAccess(root, this),
        )
    }

    /** 残高テキストを拾って共有する。セッションの有無に関係なく行う。 */
    private fun readBalance(texts: List<String>, now: Long) {
        val yen = BalanceTextParser.extractBalanceFromLines(texts, preferLabeled = true) ?: return
        AccessibilityBridge.publishBalance(
            BalanceReading(yen, now, BalanceSourceType.ACCESSIBILITY),
        )
        SecureLog.d(SecureLog.Tag.BALANCE, "read balance from ICOCA screen: $yen")
    }

    // --------------------------------------------------------------- event

    /**
     * イベントは印を付けるだけ（改修指示 §5）。
     *
     * ここで分類も判定もクリックもしない。**重い処理をイベント経路から外すのが要点。**
     * Compose 由来の大量イベントが来ても、印が立つだけで実害がない。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != IcocaConstants.PACKAGE_NAME) return
        screenChangedSignal.set(true)

        // セッションが無いときは、残高だけ読んでおく（ダッシュボード表示用）。
        if (engine == null || engine?.session?.isFinished == true) {
            runCatching { captureSnapshot(System.currentTimeMillis()) }
                .onFailure { SecureLog.e("passive screen read failed", it) }
        }
    }

    // ------------------------------------------------------------- utility

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

    /**
     * 画面を1回叩く（[GesturePerformer]）。
     *
     * `ACTION_CLICK` が通らないノード用の最後の手段。呼ばれるのは
     * [NodeScreenAccess] がテキストで特定したノードの bounds 中心に対してのみで、
     * 座標を決め打ちすることはない。
     *
     * `dispatchGesture` は非同期なので、完了を短く待って結果を返す。
     * **true でも「チャージできた」ではない。** 画面が変わったかは呼び出し側が確かめる。
     */
    override fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MILLIS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val done = CountDownLatch(1)
        var completed = false
        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        completed = true
                        done.countDown()
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        done.countDown()
                    }
                },
                null,
            )
        }.getOrDefault(false)

        if (!dispatched) {
            SecureLog.w(SecureLog.Tag.AUTOMATION, "gesture dispatch refused")
            return false
        }
        runCatching { done.await(GESTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return completed
    }

    private fun AccessibilityNodeInfo.toSummary() = NodeSummary(
        className = className?.toString(),
        viewId = viewIdResourceName,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        clickable = isClickable,
        editable = isEditable,
    )

    /** エンジンの記録を [AccessibilityBridge] に流す。 */
    private class BridgeRecorder : AutomationRecorder {
        override fun log(kind: AutomationLogKind, detail: String, nowMillis: Long) {
            val safe = LogRedactor.redact(detail)
            AccessibilityBridge.addLog(AutomationLogEntry(nowMillis, kind, safe))
            SecureLog.i(SecureLog.Tag.AUTOMATION, "$kind $safe")
        }

        override fun snapshot(record: SnapshotRecord) {
            AccessibilityBridge.addSnapshot(record)
        }
    }

    private companion object {
        const val MAX_DUMP_NODES = 120

        /** タップの押下時間。短すぎると無視されることがある。 */
        const val TAP_DURATION_MILLIS = 60L

        /** ジェスチャー完了を待つ上限。 */
        const val GESTURE_TIMEOUT_MILLIS = 1_500L

        /** 残高を見に行ったとき、読めるまで待つ上限。 */
        const val PROBE_TIMEOUT_MILLIS = 20_000L

        /** ロック解除から ICOCA を開くまでの間。解除直後はまだ画面が動いている。 */
        const val UNLOCK_SETTLE_MILLIS = 1_500L
    }
}
