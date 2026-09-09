package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.data.settings.FlowStateRepository
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType

/**
 * Debug 画面の「残高を疑似設定」による、**1回限り**の残高上書き。
 *
 * ### なぜこれが必要か
 * ユーザー補助サービスが直前に ICOCA アプリの画面を読んでいると、その実際の残高が
 * [AccessibilityBalanceSource] 経由でチェーンに残っている。素朴に優先度で並べると、
 * この「本物の残高」が Debug の疑似値より勝ってしまい、低残高検知のテストが
 * 実質できなくなる（テスト用に3,000円未満を入れても、実際の残高が使われてしまう）。
 *
 * そのためこのソースは [BalanceSourceType.SIMULATED]（全ソース中で最優先）として動き、
 * 明示的に値が設定されている間だけ [isAvailable] が true を返す。
 *
 * ### なぜ「使ったら消える」のか
 * [read] は値を返すと同時に [FlowStateRepository.consumeDebugOverride] で
 * その値を消す。消さずに残しておくと、チャージ処理後の残高確認（`verifyNow`）でも
 * 同じ古いテスト値が返り続け、「本当にチャージされたか」を正しく検証できなくなる。
 * 1回のテストは「疑似設定 → 検知の確認」までで完結させ、
 * その後の自動操作・残高確認は必ず実際のソースを使う設計にしている。
 */
class SimulatedBalanceSource(private val flowState: FlowStateRepository) : BalanceSource {

    override val type = BalanceSourceType.SIMULATED

    override val description: String = "疑似値（Debug・1回使うと消えます）"

    override suspend fun isAvailable(): Boolean = flowState.hasDebugOverride()

    override suspend fun read(): BalanceReading? = flowState.consumeDebugOverride()
}
