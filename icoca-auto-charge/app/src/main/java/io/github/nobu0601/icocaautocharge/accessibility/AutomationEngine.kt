package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.LogRedactor
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * 自動操作の本体（改修指示 §1, §4）。
 *
 * ### 何が変わったのか
 *
 * 以前は「AccessibilityEvent が来る → 画面を判定する → 1手打つ → 次のイベントを待つ」
 * というイベント駆動だった。これは **イベントが来ない瞬間があると永久に止まる。**
 * 実機では次の2つで実際に止まった。
 *
 *  - 「チャージ」を押した直後、中身の無い画面が一瞬挟まる（イベントはそこで打ち止め）
 *  - すでに 5,000 が選ばれている金額画面で 5,000 を押しても、見た目が変わらず次が来ない
 *
 * いまはイベントに依存しない。**[tick] を一定周期で呼ぶだけ**で、
 * 毎回その時点の画面を見て判断する。イベントは「そろそろ見に行くといい」という
 * ヒントでしかなく、来なくても [tick] は回り続ける。
 *
 * ### なぜ [tick] が suspend でもループでもないのか
 *
 * 時間を引数で受け取る純粋な1手にしてあるので、**実機なしでテストから駆動できる。**
 * 「イベントが来ない」「同じ画面が15秒続く」といった、実機でしか起きないはずの
 * 状況を JVM のテストで再現して回帰に固定できる。
 * 300ms 待つループは [IcocaAccessibilityService] 側の薄い層に置いてある。
 *
 * ### 止める条件（改修指示 §23）
 *
 * 「止まらないこと」が目的ではない。**正常な待機では止まらず、本当に異常なときだけ止まる。**
 *
 * | 状況 | 判断 |
 * |---|---|
 * | 画面遷移中・イベント欠落・同一画面 | 継続 |
 * | 判別できない画面 | 原則待機（[AutomationTimeouts.UNKNOWN_WAIT_MILLIS] まで） |
 * | 本人認証 | ユーザーへ引き渡し（**失敗ではない**） |
 * | 明確なエラー画面 | 停止 |
 * | 状態ごとの timeout 超過 | Recovery → それでも駄目なら停止 |
 * | 安全条件の不一致 | 即停止 |
 */
class AutomationEngine(
    val session: AutomationSession,
    private val guard: SafetyGuard,
    /** いまインストールされている ICOCA の署名。初回検出時の値と突き合わせる。 */
    private val actualSignature: String?,
    private val recorder: AutomationRecorder = AutomationRecorder.None,
    private val throttle: ActionThrottle = ActionThrottle(),
) {

    /** 直前に SCREEN 行を出した時刻。300ms ごとに同じ行を出さないための間引き。 */
    private var lastScreenLoggedAt = 0L
    private var lastScreenLogged: IcocaScreen? = null

    /**
     * 1周期ぶん進める。
     *
     * @param snapshot いまの ICOCA 画面。ICOCA が前面にいなければ null。
     */
    fun tick(snapshot: ScreenSnapshot?, now: Long) {
        if (session.isFinished) return

        // セッション全体の最後の歯止め。個別の timeout をすべてすり抜けた場合に効く。
        if (session.millisSinceStart(now) > AutomationTimeouts.SESSION_MILLIS) {
            fail(ErrorReason.TIMEOUT, "自動操作が時間内に完了しませんでした", now)
            return
        }

        if (snapshot == null) {
            onIcocaNotForeground(now)
            return
        }

        record(snapshot, now)
        session.onScreenObserved(snapshot.screen, now)

        if (!passesHardGuards(snapshot, now)) return

        when (snapshot.screen) {
            // 設定に関係なく必ず止める。ここは何があっても動かさない（改修指示 §15）。
            IcocaScreen.AUTHENTICATION -> handToUser(
                snapshot.screen,
                "ICOCAの本人認証が必要です。ICOCAアプリを確認してください。",
                now,
            )
            IcocaScreen.ERROR -> fail(
                ErrorReason.PAYMENT_DECLINED,
                "ICOCAアプリがエラーを表示しています",
                now,
            )
            IcocaScreen.COMPLETED -> complete(now)
            IcocaScreen.UNKNOWN -> onUnknownScreen(now)
            else -> {
                reconcile(snapshot, now)
                act(snapshot, now)
                checkStateTimeout(now)
            }
        }
    }

    // ---------------------------------------------------------------- guards

    /**
     * 押す前に必ず通る、動かしてはいけない検査。
     *
     * ここが通らないなら、そもそも操作してよい相手ではない。
     */
    private fun passesHardGuards(snapshot: ScreenSnapshot, now: Long): Boolean {
        // 画面種別ごとの判断は混ぜない。混ぜると認証画面が「停止すべき異常」として
        // 返ってきてしまい、ユーザーへの引き渡しが失敗扱いになる（改修指示 §15）。
        val stop = guard.checkEnvironment(
            SafetyGuard.Context(
                packageName = snapshot.packageName,
                actualSignature = actualSignature,
                screen = snapshot.screen,
                stepCount = session.currentStep,
            ),
        ) ?: return true
        fail(stop.reason, stop.message, now)
        return false
    }

    // ------------------------------------------------------------- no ICOCA

    /**
     * ICOCA が前面にいない。
     *
     * 起動直後はこれが普通なので待つ。ホームに戻られた場合も、
     * 戻ってくるかもしれないので状態ごとの timeout まで待つ。
     */
    private fun onIcocaNotForeground(now: Long) {
        session.setStatus(AutomationStatus.WAITING, now)
        checkStateTimeout(now)
    }

    // -------------------------------------------------------------- unknown

    /**
     * 判別できない画面（改修指示 §7）。
     *
     * **これは正常系。** ICOCA が画面を組み立てている途中では、
     * 文字を2つしか持たない中身の無いツリーが返ってくる。
     * 以前はこれを即中止していたため、チャージ画面に着く前に毎回セッションが死んでいた。
     *
     * 回数ではなく時間で測る。何もクリックしないので、待っても危険は増えない。
     */
    private fun onUnknownScreen(now: Long) {
        val waited = session.unknownDurationMillis(now)
        if (waited <= AutomationTimeouts.UNKNOWN_WAIT_MILLIS) {
            if (session.status != AutomationStatus.WAITING) {
                session.setStatus(AutomationStatus.WAITING, now)
                recorder.log(AutomationLogKind.WAIT, "判別できない画面を待機中", now)
            }
            return
        }
        enterRecoveryOrFail(
            now,
            ErrorReason.UNEXPECTED_SCREEN,
            "判別できない画面が続いたため中止しました",
        )
    }

    // ------------------------------------------------------------- recovery

    /**
     * 観測した画面に合わせて状態を引き直す（改修指示 §16）。
     *
     * 想定と違う画面にいても、**現在画面がはっきり分かるならそこへ復帰する。**
     * 「支払いボタンを待っていたはずが、実は確認ダイアログが出ていた」のような
     * ずれを、待ち続けるのではなく合わせにいく。
     *
     * ここでやるのは再分類だけ。戻るボタン連打・HOME・強制終了・
     * 座標タップのような荒い手は**一切行わない**（改修指示 §17）。
     */
    private fun reconcile(snapshot: ScreenSnapshot, now: Long) {
        val consistent = statesFor(snapshot.screen)
        if (session.state in consistent) {
            // 想定どおり。Recovery 中だったなら抜ける。
            if (session.status != AutomationStatus.RUNNING) {
                session.setStatus(AutomationStatus.RUNNING, now)
            }
            return
        }
        val entry = entryStateFor(snapshot.screen) ?: return

        // 前に進む向き（画面のほうが先に行っている）なら、いつでも合わせにいく。
        // 途中の画面が出ずに飛ばされることは実機でも普通にある。
        //
        // 戻る向きはそうはいかない。ボタンを押した直後は、画面がまだ前のままなのが
        // 当たり前で、ここで引き戻すと「押す → 戻される → また押す」を
        // 周期ごとに繰り返して連打になる。実際その状態になっていた。
        // 戻すのは、待ちきれずに Recovery に入ったときだけにする（改修指示 §16）。
        val goingForward = entry.ordinal > session.state.ordinal
        if (!goingForward && session.status != AutomationStatus.RECOVERING) return

        recorder.log(
            AutomationLogKind.RECOVERY,
            "${session.state} → $entry（画面は ${snapshot.screen}）",
            now,
        )
        session.moveTo(entry, now)
        session.setStatus(AutomationStatus.RUNNING, now)
        throttle.onStepAdvanced()
    }

    /** その画面にいるとき、矛盾しない状態の集合。 */
    private fun statesFor(screen: IcocaScreen): Set<AutomationState> = when (screen) {
        IcocaScreen.MAIN -> setOf(
            AutomationState.WAITING_FOR_MAIN,
            AutomationState.MAIN_READY,
            AutomationState.CLICK_CHARGE,
        )
        IcocaScreen.CHARGE_ENTRY -> setOf(
            AutomationState.WAITING_FOR_CHARGE_ENTRY,
            AutomationState.CHARGE_ENTRY,
        )
        // 金額選択画面は「選ぶ」「押した直後」「支払いボタンを探す」が全部同じ画面。
        // 画面が変わらないからといって状態を巻き戻してはいけない。
        IcocaScreen.CHARGE_AMOUNT -> setOf(
            AutomationState.WAITING_FOR_AMOUNT,
            AutomationState.CHARGE_AMOUNT,
            AutomationState.SELECTING_AMOUNT,
            AutomationState.WAITING_FOR_PAYMENT_BUTTON,
            AutomationState.PAYMENT_READY,
        )
        IcocaScreen.PAYMENT_CONFIRM -> setOf(AutomationState.PAYMENT_CONFIRM)
        IcocaScreen.PROCESSING -> setOf(
            AutomationState.PROCESSING,
            AutomationState.WAITING_FOR_COMPLETION,
        )
        else -> emptySet()
    }

    /** その画面に着いたときに入るべき状態。 */
    private fun entryStateFor(screen: IcocaScreen): AutomationState? = when (screen) {
        IcocaScreen.MAIN -> AutomationState.MAIN_READY
        IcocaScreen.CHARGE_ENTRY -> AutomationState.CHARGE_ENTRY
        IcocaScreen.CHARGE_AMOUNT ->
            if (session.amountSelected) AutomationState.WAITING_FOR_PAYMENT_BUTTON
            else AutomationState.CHARGE_AMOUNT
        IcocaScreen.PAYMENT_CONFIRM -> AutomationState.PAYMENT_CONFIRM
        IcocaScreen.PROCESSING -> AutomationState.PROCESSING
        else -> null
    }

    // ----------------------------------------------------------------- act

    private fun act(snapshot: ScreenSnapshot, now: Long) {
        when (session.state) {
            AutomationState.MAIN_READY,
            AutomationState.WAITING_FOR_MAIN,
            -> clickAndAdvance(
                snapshot,
                NodeSpec.ExactText(CHARGE_ENTRY_LABELS),
                AutomationAction.CLICK_CHARGE_ENTRY,
                AutomationState.WAITING_FOR_CHARGE_ENTRY,
                now,
            )

            AutomationState.CHARGE_ENTRY -> clickAndAdvance(
                snapshot,
                NodeSpec.ExactText(CHARGE_ENTRY_LABELS + CHARGE_METHOD_LABELS),
                AutomationAction.CLICK_CHARGE_METHOD,
                AutomationState.WAITING_FOR_AMOUNT,
                now,
            )

            AutomationState.CHARGE_AMOUNT -> selectAmount(snapshot, now)

            // 金額を押した直後。画面は変わらないのが普通なので、次の周期で支払いボタンを探す。
            AutomationState.SELECTING_AMOUNT ->
                session.moveTo(AutomationState.WAITING_FOR_PAYMENT_BUTTON, now)

            AutomationState.WAITING_FOR_PAYMENT_BUTTON -> findPaymentButton(snapshot, now)

            AutomationState.PAYMENT_READY -> clickPaymentButton(snapshot, now)

            AutomationState.PAYMENT_CONFIRM -> confirmPayment(snapshot, now)

            // 通信待ち。画面が変わるのを待つだけで、こちらからは何もしない。
            AutomationState.PROCESSING,
            AutomationState.WAITING_FOR_COMPLETION,
            -> session.setStatus(AutomationStatus.WAITING, now)

            else -> Unit
        }
    }

    /** 見つかったら押して次の状態へ。見つからなければ待つ（timeout が面倒を見る）。 */
    private fun clickAndAdvance(
        snapshot: ScreenSnapshot,
        spec: NodeSpec,
        action: AutomationAction,
        next: AutomationState,
        now: Long,
    ) {
        val target = snapshot.access.find(spec)
        if (target == null) {
            waitFor(snapshot, action.name, now)
            return
        }
        if (!click(snapshot, target, action, now)) return
        session.moveTo(next, now)
        throttle.onStepAdvanced()
    }

    /**
     * 金額を選ぶ（改修指示 §11, §12）。
     *
     * 押した後にイベントを待たない。**押しても見た目が変わらないのを正常系として扱う。**
     *
     * 設定額のボタンが見つからない場合でも、すでに設定額が画面に出ているなら
     * 選び直す必要はないので、そのまま支払いボタン探しへ進む。
     * どちらでもないときは黙って待つ。ここで即失敗させると、
     * 画面がまだ描き終わっていないだけのケースを潰してしまう。
     */
    private fun selectAmount(snapshot: ScreenSnapshot, now: Long) {
        if (session.amountSelected) {
            session.moveTo(AutomationState.WAITING_FOR_PAYMENT_BUTTON, now)
            return
        }
        val variants = guard.amountLabelVariants(session.chargeAmountYen)
        // 入力欄を除外する。金額選択画面は上部の編集可能な欄と下のプリセットに
        // 同じ「5,000」が出るので、欄を押すとキーボードが出るだけで先に進まない。
        val target = snapshot.access.find(NodeSpec.ExactText(variants, excludeEditable = true))

        if (target != null) {
            // 押そうとしているラベルが設定額と一致しなければ、近い額で代用などはしない。
            if (!guard.verifyAmountLabel(target.label, session.chargeAmountYen)) {
                fail(ErrorReason.AMOUNT_MISMATCH, "選択しようとした金額が設定と一致しません", now)
                return
            }
            if (!click(snapshot, target, AutomationAction.CLICK_AMOUNT, now)) return
            session.markAmountSelected()
            session.moveTo(AutomationState.SELECTING_AMOUNT, now)
            throttle.onStepAdvanced()
            return
        }

        if (guard.isAmountVisibleOnScreen(snapshot.texts, session.chargeAmountYen)) {
            recorder.log(
                AutomationLogKind.WAIT,
                "設定額はすでに画面に出ているので選び直さない",
                now,
            )
            session.markAmountSelected()
            session.moveTo(AutomationState.WAITING_FOR_PAYMENT_BUTTON, now)
            return
        }

        waitFor(snapshot, "金額ボタン", now)
    }

    /** 支払いへ進むボタンを探す。見つかるまで周期的に探し続ける。 */
    private fun findPaymentButton(snapshot: ScreenSnapshot, now: Long) {
        val target = snapshot.access.find(NodeSpec.TextContains(PROCEED_TO_PAYMENT_NEEDLES))
        if (target == null) {
            waitFor(snapshot, "支払いへ進むボタン", now)
            return
        }
        session.moveTo(AutomationState.PAYMENT_READY, now)
        clickPaymentButton(snapshot, now)
    }

    /**
     * 支払いへ進むボタンを押す。
     *
     * **このボタンを押しても決済は確定しない。** 確認ダイアログが出るだけ。
     * 確定するかどうかは [confirmPayment] が別途判断する。
     */
    private fun clickPaymentButton(snapshot: ScreenSnapshot, now: Long) {
        val target = snapshot.access.find(NodeSpec.TextContains(PROCEED_TO_PAYMENT_NEEDLES))
        if (target == null) {
            waitFor(snapshot, "支払いへ進むボタン", now)
            return
        }

        // 押す直前に、何を掴んだのかを必ず残す（要件4）。
        // 実機で「ボタンは見えているのに押されない」が起きたとき、
        // どのノードをどう解決したのかが分からないと手の打ちようがない。
        recorder.log(
            AutomationLogKind.ACTION,
            "[PAYMENT_METHOD_BUTTON_FOUND] ${target.diagnostics ?: "(詳細なし)"}",
            now,
        )
        session.onMatch(target)

        val clicked = click(snapshot, target, AutomationAction.CLICK_PROCEED_TO_PAYMENT, now)
        if (!clicked) return

        // **performAction の戻り値 true は「チャージ操作が成功した」ではない**（要件6）。
        // アクセシビリティのアクションが受け付けられたというだけで、
        // ICOCA 側が画面を進めたかどうかは何も言っていない。
        // 次の周期で root を取り直し、画面が変わったかどうかで判断する（要件7, 8）。
        session.moveTo(AutomationState.PAYMENT_CONFIRM, now)
        session.setStatus(AutomationStatus.WAITING, now)
        recorder.log(
            AutomationLogKind.WAIT,
            "支払いボタンを押した。画面遷移を確認する（最大" +
                "${AutomationTimeouts.forState(AutomationState.PAYMENT_CONFIRM)?.div(1000)}秒）",
            now,
        )
        throttle.onStepAdvanced()
    }

    /**
     * 決済を確定する。**このアプリで唯一、お金が動く操作**（改修指示 §13）。
     *
     * 自動確定が OFF ならここでユーザーへ引き渡して終わる。
     * ON の場合も、7つの条件が**すべて**揃わなければ押さない。
     */
    private fun confirmPayment(snapshot: ScreenSnapshot, now: Long) {
        // **まず画面を確かめる。** 支払いボタンを押した直後は、状態が
        // PAYMENT_CONFIRM でも画面はまだ金額選択のままなのが普通。
        // ここを飛ばすと、確認ダイアログが出ていないのに
        // 「ご自身で確定してください」と引き渡してしまう（要件6, 8）。
        // performAction が true を返したことは、画面が進んだ証拠にならない。
        if (snapshot.screen != IcocaScreen.PAYMENT_CONFIRM) {
            waitFor(snapshot, "決済確認画面", now)
            return
        }

        if (!session.autoConfirmPayment) {
            handToUser(
                snapshot.screen,
                "チャージ内容をご確認のうえ、ご自身で確定してください。",
                now,
            )
            return
        }

        // 1,2: パッケージと署名は passesHardGuards が毎周期見ている。
        // 3: 画面が決済確認であることは上で確認済み。
        // 4: 画面上の金額が設定額と一致するか。
        if (!guard.isAmountVisibleOnScreen(snapshot.texts, session.chargeAmountYen)) {
            fail(
                ErrorReason.AMOUNT_MISMATCH,
                "決済画面に設定した金額が見当たらないため、確定しませんでした",
                now,
            )
            return
        }
        // 5: 確定ボタンのラベルが既知のものと完全一致するか。
        val target = snapshot.access.find(NodeSpec.ExactText(CONFIRM_LABELS))
        if (target == null) {
            // 確定ボタンが無いのに「〜でチャージ」があるなら、まだ確認ダイアログではない。
            // そのボタンは決済を確定しないので、押して確認ダイアログへ進む。
            val proceed = snapshot.access.find(NodeSpec.TextContains(PROCEED_TO_PAYMENT_NEEDLES))
            if (proceed != null) {
                clickPaymentButton(snapshot, now)
                return
            }
            waitFor(snapshot, "確定ボタン", now)
            return
        }
        // 6,7: SafetyGuard は上で通過済み。ActionThrottle は click() の中。
        if (!click(snapshot, target, AutomationAction.CLICK_CONFIRM_PAYMENT, now)) return
        session.moveTo(AutomationState.PROCESSING, now)
        throttle.onStepAdvanced()
    }

    // --------------------------------------------------------------- click

    /**
     * 押す。[ActionThrottle] を必ず通す。
     *
     * @return 実際に押した（またはドライランで押したことにした）なら true。
     *   スロットルに弾かれた場合も false を返すが、**これは失敗ではない**。
     */
    private fun click(
        snapshot: ScreenSnapshot,
        target: ClickTarget,
        action: AutomationAction,
        now: Long,
    ): Boolean {
        if (!throttle.allow(snapshot.screen, target.key, action, now)) return false

        val label = LogRedactor.redact(target.label ?: target.key)
        if (session.dryRun) {
            session.plannedClicks += "$action:$label"
            session.onAction(action, now, result = true)
            recorder.log(AutomationLogKind.ACTION, "[ドライラン] CLICK \"$label\"", now)
            return true
        }

        val ok = runCatching { snapshot.access.click(target) }.getOrDefault(false)
        session.onAction(action, now, ok)
        if (action == AutomationAction.CLICK_PROCEED_TO_PAYMENT) {
            // 戻り値をそのまま残す（要件5）。true でも「チャージできた」ではない。
            recorder.log(AutomationLogKind.ACTION, "[PAYMENT_METHOD_BUTTON_CLICK] result=$ok", now)
        }
        recorder.log(
            AutomationLogKind.ACTION,
            if (ok) "CLICK \"$label\"" else "CLICK 失敗 \"$label\"",
            now,
        )
        if (!ok) {
            // **1回の失敗でセッションを畳まない。**
            //
            // 実機では、ボタンを見つけられていても performAction が false を返した。
            // スクロール領域の外にいる、掴んでから押すまでにツリーが作り直された、
            // といった一時的な理由で false になる。ここで即中止すると、
            // 画面内に入れ直せば押せたものまで落としてしまう（実際そうなっていた）。
            //
            // 押し直せるようにして、次の周期に賭ける。
            // 無限に粘るわけではなく、状態ごとの timeout が区切る。
            throttle.onClickFailed(snapshot.screen, target.key, action)
            session.setStatus(AutomationStatus.WAITING, now)
        }
        return ok
    }

    // -------------------------------------------------------------- timing

    /** 探しものが見つからない。待機として記録し、timeout の判断に委ねる。 */
    private fun waitFor(snapshot: ScreenSnapshot, what: String, now: Long) {
        if (session.status != AutomationStatus.WAITING &&
            session.status != AutomationStatus.RECOVERING
        ) {
            session.setStatus(AutomationStatus.WAITING, now)
            val labels = LogRedactor.redact(
                snapshot.access.clickableLabels().joinToString(" / ").take(MAX_LABEL_CHARS),
            )
            recorder.log(AutomationLogKind.WAIT, "$what を待機中。押せるもの: $labels", now)
        }
    }

    /**
     * 状態ごとの timeout（改修指示 §8, §9）。
     *
     * 「同じ画面が続くこと」自体は異常としない。いま何を待っているかで上限を変える。
     * 超えたらまず Recovery（現在画面を見直す）へ。それでも駄目なら停止。
     */
    private fun checkStateTimeout(now: Long) {
        if (session.isFinished) return
        val limit = AutomationTimeouts.forState(session.state) ?: return
        if (session.millisInState(now) <= limit) return
        enterRecoveryOrFail(
            now,
            ErrorReason.UI_STRUCTURE_CHANGED,
            "ICOCAアプリの画面が想定と異なるため中止しました（${session.state}）",
        )
    }

    private fun enterRecoveryOrFail(now: Long, reason: ErrorReason, message: String) {
        if (session.status != AutomationStatus.RECOVERING) {
            session.setStatus(AutomationStatus.RECOVERING, now)
            recorder.log(AutomationLogKind.RECOVERY, "${session.state} で待ちすぎたため再確認", now)
            return
        }
        val since = session.recoveringSince ?: now
        if (now - since > AutomationTimeouts.RECOVERY_MILLIS) {
            recorder.log(AutomationLogKind.TIMEOUT, message, now)
            fail(reason, message, now)
        }
    }

    // -------------------------------------------------------------- finish

    private fun fail(reason: ErrorReason, message: String, now: Long) {
        recorder.log(AutomationLogKind.FAILURE, "$reason: $message", now)
        session.finish(AutomationSession.Outcome.Stopped(reason, message))
    }

    private fun handToUser(screen: IcocaScreen, message: String, now: Long) {
        recorder.log(AutomationLogKind.USER_ACTION_REQUIRED, message, now)
        session.finish(AutomationSession.Outcome.HandedToUser(screen, message))
    }

    private fun complete(now: Long) {
        recorder.log(AutomationLogKind.SUCCESS, "チャージ完了画面を確認", now)
        session.finish(AutomationSession.Outcome.Completed)
    }

    // -------------------------------------------------------------- record

    /**
     * 画面を履歴に残す。
     *
     * 300ms ごとに全部残すと、50件が15秒ぶんにしかならず履歴の意味がない。
     * 画面が変わったときと、変わらないまま1秒経ったときだけ残す。
     */
    private fun record(snapshot: ScreenSnapshot, now: Long) {
        val changed = snapshot.screen != lastScreenLogged
        if (!changed && now - lastScreenLoggedAt < SCREEN_LOG_INTERVAL_MILLIS) return
        lastScreenLogged = snapshot.screen
        lastScreenLoggedAt = now
        recorder.snapshot(
            SnapshotRecord.from(snapshot, session.state, session.currentStep, session.lastAction),
        )
        recorder.log(AutomationLogKind.SCREEN, snapshot.screen.name, now)
    }

    companion object {
        /** 同じ画面が続くときに SCREEN 行を出す間隔。 */
        const val SCREEN_LOG_INTERVAL_MILLIS = 1_000L

        private const val MAX_LABEL_CHARS = 200

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
         * 金額選択画面から確認へ進むボタンを見分ける語。
         *
         * 実機のラベルは「****9804でチャージ」で、前半にカード番号が入る。
         * **固定文字列で探してはいけない**（要件1）。見た目のボタンと
         * `AccessibilityNodeInfo` の構造は一致せず、実機では
         * 「****9804」と「でチャージ」が別ノードに割れていることがある。
         * 可変部分を避けて「でチャージ」だけを手がかりにし、
         * 末尾一致 → 部分一致 → 子孫連結の順で探す（[NodeFinder.findClickableByTextContains]）。
         *
         * **押しても決済は確定せず、確認ダイアログが出るだけ。**
         */
        val PROCEED_TO_PAYMENT_NEEDLES = listOf("でチャージ")

        /**
         * 決済を確定するボタンのラベル。
         *
         * 実機の「チャージ確認」ダイアログは「キャンセル」と「チャージする」の2択。
         * 「はい」「OK」のような汎用語は入れない。金額は別途確認しているが、
         * それでも汎用語で確定を押すのは危うい。
         * 一致しなければ押さずに待つだけなので、外れていても安全側に倒れる。
         */
        val CONFIRM_LABELS = listOf(
            "チャージする", "決済する", "支払う", "確定する", "確定",
            "この内容でチャージする", "チャージを実行", "実行する",
        )

        /** ICOCA アプリのパッケージ名（照合用の再掲）。 */
        const val ICOCA_PACKAGE = IcocaConstants.PACKAGE_NAME
    }
}
