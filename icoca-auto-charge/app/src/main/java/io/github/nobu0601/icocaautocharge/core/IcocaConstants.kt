package io.github.nobu0601.icocaautocharge.core

import java.time.ZoneId

/**
 * モバイルICOCA の仕様に由来する定数（docs/PROJECT_RESEARCH.md §1.3 に根拠）。
 * これらは JR西日本の仕様であり、本アプリの設定で変更してはならない。
 */
object IcocaConstants {

    const val PACKAGE_NAME = "jp.co.westjr.android.icocaapp"
    const val OSAIFU_KEITAI_PACKAGE = "com.felicanetworks.mfm.main"

    /** カード内残額の上限。これを超えるチャージはできない。 */
    const val CARD_BALANCE_CAP_YEN = 20_000

    /** 1回あたりのチャージ上限。 */
    const val PER_CHARGE_CAP_YEN = 20_000

    /** チャージが利用できない時間帯（日本時間）。システムメンテナンス。 */
    const val MAINTENANCE_START_HOUR_JST = 2
    const val MAINTENANCE_END_HOUR_JST = 4

    val ZONE_JST: ZoneId = ZoneId.of("Asia/Tokyo")
}
