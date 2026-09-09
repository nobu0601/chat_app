package io.github.nobu0601.icocaautocharge.domain

/** 履歴に残る最終的な結果（指示書 §13）。 */
enum class ChargeStatus {
    DETECTED,
    PENDING,
    CHARGING,
    VERIFYING,
    SUCCESS,

    /** チャージ操作は完了したが、残高の再取得ができず裏取りできていない。 */
    SUCCESS_UNVERIFIED,
    FAILED,
    CANCELLED,
    ;

    /** 上限（日次・月次）の集計対象にするか。裏取りできていない分も安全側で数える。 */
    val countsTowardLimits: Boolean
        get() = this == SUCCESS || this == SUCCESS_UNVERIFIED
}

/** 何によってチャージ処理を進めたか（指示書 §13）。 */
enum class AutomationMethod {
    /** 自動操作なし。通知だけ出した。 */
    NONE,

    /** ユーザーが手作業でチャージした。 */
    MANUAL,

    /** 公開 Intent / Deep Link で画面へ遷移した。 */
    INTENT,

    /** ユーザー補助サービスで画面遷移・金額選択を補助した。 */
    ACCESSIBILITY,
}

/** 失敗理由。ユーザーにそのまま見せるので網羅的に持つ（指示書 §25）。 */
enum class ErrorReason {
    TIMEOUT,
    CANCELLED_BY_USER,
    ICOCA_APP_NOT_FOUND,
    ICOCA_APP_LAUNCH_FAILED,
    ACCESSIBILITY_DISABLED,
    BALANCE_UNAVAILABLE,
    CHARGE_SCREEN_NOT_FOUND,
    AMOUNT_MISMATCH,
    UNEXPECTED_SCREEN,
    AUTHENTICATION_REQUIRED,
    PAYMENT_DECLINED,
    NETWORK_ERROR,
    NOT_CHARGED,
    UI_STRUCTURE_CHANGED,
    PACKAGE_MISMATCH,
    SIGNATURE_MISMATCH,
    STEP_LIMIT_EXCEEDED,
    UNKNOWN,
}

/**
 * 残高をどの手段で得たか。数字が小さいほど信頼度が高い（指示書 §4 の優先順位）。
 *
 * [SIMULATED] だけは例外で、「信頼度」ではなく「テスト時にどれだけ優先して使いたいか」を表す。
 * Debug 画面の「残高を疑似設定」で明示的に上書きした値が、キャッシュ済みの実残高
 * （例: ユーザー補助が直前に読んだ ACCESSIBILITY の値）に負けてしまうと、
 * 低残高検知のテストが実質できなくなる。そのため全ソース中で最優先にしてある。
 * 実害が出ないのは、この値を返す `SimulatedBalanceSource` が
 * 「明示的に設定され、かつ一度も使われていない」ときしか利用可能を返さず、
 * 使われた瞬間に消費されるため（`FlowStateRepository.consumeDebugOverride`）。
 */
enum class BalanceSourceType(val priority: Int) {
    /** Debug 画面から注入した1回限りの疑似値。使うと消える。リリースビルドの UI からは到達できない。 */
    SIMULATED(-1),

    /** 公式 API。存在しないため未使用。 */
    OFFICIAL_API(0),

    /** 公式 Intent / Deep Link。現時点で公開されていない。 */
    INTENT(1),

    /** NFC / FeliCa。物理 ICOCA カード限定。 */
    NFC(2),

    /** ICOCA アプリ画面からユーザー補助で読み取り。 */
    ACCESSIBILITY(3),

    /** ユーザーの手入力。常に利用できる Fallback。 */
    MANUAL(4),
}
