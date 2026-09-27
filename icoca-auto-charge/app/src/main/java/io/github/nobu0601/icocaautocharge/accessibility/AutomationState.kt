package io.github.nobu0601.icocaautocharge.accessibility

/**
 * 自動操作エンジンの内部状態（改修指示 §10）。
 *
 * ### なぜ [io.github.nobu0601.icocaautocharge.domain.ChargeState] と分けるのか
 *
 * `ChargeState` は DataStore に永続化され、**二重チャージ防止の土台**になっている。
 * 非終端状態の一意性・遷移表・30分タイムアウトがそこにぶら下がっているため、
 * ここに「金額選択を待っている」のような細かい状態を混ぜると遷移表が破綻する。
 *
 * そこで2層に分けた。
 *
 * | | 所有者 | 永続化 | 粒度 |
 * |---|---|---|---|
 * | `ChargeState` | `ChargeFlowCoordinator` | する | 粗い（7種） |
 * | `AutomationState` | `AutomationEngine` | しない | 細かい（この列挙） |
 *
 * 境界で写像する。自動操作が何段階に分かれていようと、
 * 外から見た試行は「保留 → 実行中 → 検証中」のままでよい。
 */
enum class AutomationState {
    IDLE,
    CHARGE_DETECTED,
    CHARGE_PENDING,
    LAUNCHING_ICOCA,
    WAITING_FOR_MAIN,
    MAIN_READY,
    CLICK_CHARGE,
    WAITING_FOR_CHARGE_ENTRY,
    CHARGE_ENTRY,
    WAITING_FOR_AMOUNT,
    CHARGE_AMOUNT,
    SELECTING_AMOUNT,
    WAITING_FOR_PAYMENT_BUTTON,
    PAYMENT_READY,
    PAYMENT_CONFIRM,
    PROCESSING,
    WAITING_FOR_COMPLETION,
    COMPLETED,
    VERIFYING,
    SUCCESS,
    FAILED,
    USER_ACTION_REQUIRED,
    ;

    /** これ以上エンジンが進める余地がない状態か。 */
    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED ||
            this == USER_ACTION_REQUIRED || this == COMPLETED
}

/** 自動操作セッション全体の進行状況（改修指示 §3）。 */
enum class AutomationStatus {
    IDLE,

    /** 画面を見て手を動かしている。 */
    RUNNING,

    /** **正常な待機。** 画面遷移中やイベント待ちはここ。異常ではない。 */
    WAITING,

    /** 想定と違う画面にいるので、現在画面を見直して正しい状態へ戻そうとしている。 */
    RECOVERING,

    /** 本人認証など、ユーザーにしかできない操作に到達した。 */
    USER_ACTION_REQUIRED,

    SUCCESS,
    FAILED,
    CANCELLED,
    ;

    /** エンジンのループを回し続けるべきか。 */
    val isRunning: Boolean
        get() = this == RUNNING || this == WAITING || this == RECOVERING
}

/** 1回のクリック操作の種類。[ActionThrottle] の同一性判定に使う。 */
enum class AutomationAction {
    CLICK_CHARGE_ENTRY,
    CLICK_CHARGE_METHOD,
    CLICK_AMOUNT,
    CLICK_PROCEED_TO_PAYMENT,
    CLICK_CONFIRM_PAYMENT,
}

/** 操作ログの種別（改修指示 §20）。 */
enum class AutomationLogKind {
    SCREEN,
    ACTION,
    WAIT,
    RECOVERY,
    TIMEOUT,
    USER_ACTION_REQUIRED,
    SUCCESS,
    FAILURE,
}
