package io.github.nobu0601.icocaautocharge.accessibility

/**
 * 画面を実際に叩ける相手（改修: 座標タップの解禁）。
 *
 * ### なぜこれが必要になったか
 *
 * 当初の方針は「座標タップは行わない」だった。text / contentDescription / viewId で
 * ノードを特定し、`ACTION_CLICK` だけで操作する、という約束である。
 *
 * だが実機の ICOCA では、この約束のままでは**チャージを完了できない**ことが分かった。
 * 「****9804でチャージ」は `isClickable=false` の `TextView` で、祖先も含めて
 * `ACTION_CLICK` を受け付けるノードが画面上に1つも存在しない。
 * Android の `View.performAccessibilityActionInternal` は `ACTION_CLICK` を受けても
 * `isClickable()` が false なら何もせず false を返すため、
 * アクション一覧に CLICK が載っていても押せない（docs/TECHNICAL_FEASIBILITY.md §0.8）。
 *
 * 「すべて自動であること」が利用者の必須要件であり、利用者自身の端末・アプリ・カードに
 * 対する操作であることから、**利用者の明示的な判断で**座標タップを解禁した。
 *
 * ### 座標タップでも守っていること
 *
 * - **固定座標は使わない。** 叩くのは、テキストで特定したノードの bounds の中心だけ。
 *   レイアウトが変われば叩く位置も一緒に動くので、「別の場所を押す」事故を避けられる
 * - `ACTION_CLICK` を先に試し、**拒否されたときだけ**この経路に落ちる
 * - 本人認証の画面では、そもそもここへ到達する前にセッションが終わる
 * - 金額の確認・ラベルの完全一致といった、押してよいかの判断は一切変えていない。
 *   変えたのは「どう押すか」だけで、「押すかどうか」ではない
 */
interface GesturePerformer {
    /**
     * 指定した点を1回叩く。
     *
     * @return 実際にジェスチャーが完了したら true。
     *   **true でも「チャージ操作が成功した」ではない。** 画面が変わったかは別途確認する。
     */
    fun tap(x: Float, y: Float): Boolean
}
