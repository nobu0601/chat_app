# ARCHITECTURE.md — DESIGN.md に統合済み

設計は **[DESIGN.md](DESIGN.md)** に一本化した。アプリ全体の設計はそちらを読むこと。

この文書を残しているのは、ソース中のコメントに `ARCHITECTURE §7` のような
参照が残っているため。章番号の対応は下表のとおり。

| 旧 `ARCHITECTURE §` | 内容 | 現在の場所 |
|---|---|---|
| §1 | 全体像 | [DESIGN §2 全体構成](DESIGN.md#2-全体構成) |
| §2 | モジュール構成 | [DESIGN §2.1 パッケージ構成](DESIGN.md#21-パッケージ構成) |
| §3 | 状態機械 | [DESIGN §4 状態機械](DESIGN.md#4-状態機械) |
| §3.1 | 二重チャージ防止 | [DESIGN §4.1](DESIGN.md#41-二重チャージ防止最重要) |
| §3.2 | 「成功」の判定 | [DESIGN §3.4 見届けと検証](DESIGN.md#34-見届けと検証) |
| §4 | 判定ロジック | [DESIGN §5 チャージ判定](DESIGN.md#5-チャージ判定chargedecisionengine) |
| §5 | 残高取得チェーン | [DESIGN §6 残高の取得](DESIGN.md#6-残高の取得) |
| §6 | 残高テキストの解析 | [DESIGN §7](DESIGN.md#7-残高テキストの解析balancetextparser) |
| §7 | 自動操作の安全設計 | [DESIGN §8.4 安全ガード](DESIGN.md#84-安全ガードsafetyguard) |
| §8 | バックグラウンド起動制限 | [DESIGN §9](DESIGN.md#9-バックグラウンド起動制限への対応) |
| §9 | データモデル | [DESIGN §10 データモデル](DESIGN.md#10-データモデル) |
| §10 | セキュリティ | [DESIGN §12 セキュリティとプライバシー](DESIGN.md#12-セキュリティとプライバシー) |
| §11 | ログタグ | [DESIGN §12.2](DESIGN.md#122-ログに出さないもの) |
| §12 | 依存性の注入 | [DESIGN §2.2](DESIGN.md#22-依存性の注入) |

統合の際、この文書にあった次の記述は**実装と食い違っていた**ので DESIGN.md では直してある。

- 不正な遷移を「`IllegalTransition` として拒否」→ 実際は例外を投げず `Result.Rejected` を返す
- `ScreenClassifier` は「キーワードの重み付きスコア」→ 実際は優先順位付きのルール。
  スコア方式なのは `BalanceTextParser` のほう
- 「`UNKNOWN` なら停止」→ 遷移途中の空画面で毎回セッションが死んでいたため、
  いまは「何も押さずに待ち、6回連続で停止」（DESIGN §13.5）
- 「`PAYMENT_CONFIRM` に分類されたら自動操作を終了」→ 設定「決済の確定まで自動で押す」次第
- `balance_sample` の `confidence` カラム → 実装には存在しない
