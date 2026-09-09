package io.github.nobu0601.icocaautocharge.data.settings

import io.github.nobu0601.icocaautocharge.core.IcocaConstants

/**
 * ユーザー設定（指示書 §7, §15）。Android に依存しない純粋なデータ。
 * 既定値は指示書に書かれている値をそのまま採用している。
 */
data class AppSettings(
    val monitoringEnabled: Boolean = true,
    val thresholdYen: Int = 3_000,
    val chargeAmountYen: Int = 5_000,
    val dailyLimitYen: Int = 10_000,
    val monthlyLimitYen: Int = 30_000,
    val minChargeIntervalHours: Int = 6,
    /**
     * ICOCA アプリを開く前に、通知をタップして確認するか。
     *
     * OFF にすると、残高低下を検知した時点で自動的に ICOCA アプリが前面に出る。
     * 使用中に割り込まれることになるので、既定は ON。
     */
    val confirmBeforeCharge: Boolean = true,
    /**
     * 決済の「確定」まで自動で押すか。
     *
     * **お金が動く操作を無人で行う設定。** 既定は OFF。
     * ON でも、本人認証（3Dセキュア・生体認証・パスワード）を検知したら必ず停止する。
     * 認証の自動突破は、この設定に関係なく一切行わない。
     */
    val autoConfirmPayment: Boolean = false,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /** 残高チェックの希望間隔。WorkManager の下限 15 分より短くはできない。 */
    val checkIntervalHours: Int = 6,
    /** 自動操作を使うか。ユーザー補助が有効でなければ意味を持たない。 */
    val automationEnabled: Boolean = false,
    /** 自動操作の説明に同意したか（Google Play の prominent disclosure 要件）。 */
    val automationConsented: Boolean = false,
    /** これより古い残高は「不明」として扱う。 */
    val balanceMaxAgeHours: Int = 24,
) {

    val minChargeIntervalMillis: Long get() = minChargeIntervalHours * 3_600_000L
    val balanceMaxAgeMillis: Long get() = balanceMaxAgeHours * 3_600_000L

    /** 設定として矛盾がないか。UI の保存時に使う。 */
    fun validate(): List<String> = buildList {
        if (thresholdYen !in 0..IcocaConstants.CARD_BALANCE_CAP_YEN) {
            add("チャージ開始残高は 0〜${IcocaConstants.CARD_BALANCE_CAP_YEN} 円の範囲で設定してください")
        }
        if (chargeAmountYen !in 1..IcocaConstants.PER_CHARGE_CAP_YEN) {
            add("チャージ金額は 1〜${IcocaConstants.PER_CHARGE_CAP_YEN} 円の範囲で設定してください")
        }
        if (dailyLimitYen < chargeAmountYen) {
            add("1日の最大チャージ額がチャージ金額を下回っています")
        }
        if (monthlyLimitYen < dailyLimitYen) {
            add("月間最大チャージ額が1日の上限を下回っています")
        }
        if (minChargeIntervalHours < 1) {
            add("最低チャージ間隔は1時間以上にしてください")
        }
        if (checkIntervalHours < 1) {
            add("残高チェック間隔は1時間以上にしてください")
        }
    }
}
