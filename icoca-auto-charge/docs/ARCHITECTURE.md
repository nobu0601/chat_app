# ARCHITECTURE.md — 設計

## 1. 全体像

```
┌─────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose)                                        │
│  Dashboard / Settings / History / Debug                      │
└───────────────┬─────────────────────────────────────────────┘
                │ StateFlow
┌───────────────▼─────────────────────────────────────────────┐
│  ChargeFlowCoordinator   ← 単一の司令塔（すべての判断はここ） │
│    ├─ ChargeDecisionEngine  (閾値 / 上限 / クールダウン判定)  │
│    ├─ ChargeStateMachine    (二重チャージ防止の中核)          │
│    └─ SafetyGuard           (自動操作の安全条件)              │
└───┬──────────┬──────────┬──────────┬──────────┬─────────────┘
    │          │          │          │          │
┌───▼────┐ ┌───▼────┐ ┌───▼─────┐ ┌──▼─────┐ ┌──▼──────────┐
│Balance │ │Settings│ │ History │ │Notifi- │ │ Icoca       │
│Repo    │ │Repo    │ │ Repo    │ │cations │ │ Launcher    │
│(chain) │ │DataStore│ │ (Room) │ │        │ │ + Probe     │
└───┬────┘ └────────┘ └─────────┘ └────────┘ └─────────────┘
    │
    ├─ IntentBalanceSource        (優先度1: 公式Intent — 現状 未提供)
    ├─ NfcBalanceSource           (優先度2: 物理ICOCAカードのみ)
    ├─ AccessibilityBalanceSource (優先度3: ICOCA画面のテキスト)
    └─ ManualBalanceSource        (優先度4: 手動入力 — 常に成立)

┌──────────────────────────────────────────────────────────────┐
│  IcocaAccessibilityService (ユーザーが設定で有効化した時のみ)  │
│    ScreenClassifier → AutomationPlan → NodeFinder → click     │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  BalanceCheckWorker (WorkManager / 最短15分・既定6時間)        │
└──────────────────────────────────────────────────────────────┘
```

## 2. モジュール構成

| パッケージ | 責務 |
|---|---|
| `core` | ログ（タグ・機密マスク）、時刻抽象、共通型 |
| `data.settings` | `AppSettings` と DataStore 永続化 |
| `data.db` | Room（履歴 / 残高サンプル / 状態） |
| `data.repo` | 履歴リポジトリ、上限集計 |
| `domain` | 判定ロジック（純粋関数・Android 非依存 → Unit Test 対象） |
| `balance` | 残高取得ソースのチェーン |
| `icoca` | ICOCA アプリの検出・起動・公開コンポーネント調査 |
| `accessibility` | AccessibilityService と画面解析・自動操作 |
| `monitor` | WorkManager・スケジューラ・フロー司令塔 |
| `notify` | 通知チャンネル・通知の組み立て・アクション |
| `ui` | Compose 画面 |

**`domain` は Android SDK に一切依存しない**（`ChargeDecisionEngine`, `ChargeStateMachine`,
`LimitCalculator`, `BalanceTextParser` は純粋 Kotlin）。これにより Robolectric なしで JVM Unit Test が回る。

## 3. 状態機械（指示書 §21）

```
                     ┌──────────────────────────────┐
                     │            IDLE              │
                     └──────────────┬───────────────┘
              残高 < 閾値 かつ 上限/クールダウンOK │
                     ┌──────────────▼───────────────┐
                     │       CHARGE_DETECTED        │  通知を出した
                     └──────────────┬───────────────┘
                     ユーザーが「チャージ」をタップ │
                     ┌──────────────▼───────────────┐
                     │       CHARGE_PENDING         │  ICOCAアプリ起動待ち
                     └──────────────┬───────────────┘
                        ICOCAアプリが前面に来た      │
                     ┌──────────────▼───────────────┐
                     │          CHARGING            │  画面遷移/金額選択中
                     └──────────────┬───────────────┘
                        完了画面 or 一定時間経過      │
                     ┌──────────────▼───────────────┐
                     │         VERIFYING            │  残高の再取得
                     └───────┬──────────────┬───────┘
                             │              │
                 ┌───────────▼──┐    ┌──────▼───────┐
                 │   SUCCESS    │    │    FAILED    │
                 └───────┬──────┘    └──────┬───────┘
                         └────────┬─────────┘
                            クールダウン開始
                                  ▼
                                IDLE
```

**遷移は `ChargeStateMachine.transition()` の一箇所でのみ行い、
不正な遷移（例: IDLE → CHARGING）は `IllegalTransition` として拒否する。**
状態は `DataStore` に永続化するため、プロセス death / 端末再起動をまたいでも復元される。

### 3.1 二重チャージ防止（§21 最重要）

4 段構えで防ぐ。

1. **非終端状態の一意性**: `IDLE` / `SUCCESS` / `FAILED` 以外の状態が存在する間、
   新しい `CHARGE_DETECTED` を作らない。
2. **attempt の同一性判定**: 進行中の attempt は `balanceAtDetection` を持つ。
   監視が同じ残高を再観測しても、`attempt.balanceAtDetection == observedBalance` なら
   「同じ低残高を見ているだけ」と判断して発火しない。
3. **クールダウン**: 終端状態に落ちてから `minChargeIntervalHours`（既定6時間）は再発火しない。
   ユーザーがキャンセルした場合（FAILED）も同じクールダウンが効く。
4. **タイムアウトでの強制終端**: `CHARGE_PENDING` / `CHARGING` が
   `ATTEMPT_TIMEOUT`（30分）を超えたら `FAILED(TIMEOUT)` に落として state を解放する。
   これがないと、ユーザーが途中で離脱した場合に永久に `IDLE` に戻らずアプリが機能停止する。

### 3.2 「成功」の判定

`VERIFYING` では残高を再取得し、
- `newBalance >= balanceBefore + chargeAmount` → `SUCCESS`
- 残高が取得できない → `SUCCESS_UNVERIFIED`（履歴には残すが、残高上昇は未確認と明記）
- 残高が変わっていない → `FAILED(NOT_CHARGED)`

## 4. 判定ロジック（`ChargeDecisionEngine`）

チャージすべきかを、以下の順で評価し **最初に失敗した理由を返す**（デバッグしやすさのため）。

| # | 条件 | 失敗理由 |
|---|---|---|
| 1 | 監視が ON | `MONITORING_DISABLED` |
| 2 | 残高が取得できている | `BALANCE_UNKNOWN` |
| 3 | `balance < threshold` | `ABOVE_THRESHOLD` |
| 4 | state が IDLE / 終端 | `ATTEMPT_IN_PROGRESS` |
| 5 | 前回終端からクールダウン経過 | `COOLDOWN` |
| 6 | 当日のチャージ合計 + 金額 ≤ 日次上限 | `DAILY_LIMIT` |
| 7 | 当月のチャージ合計 + 金額 ≤ 月次上限 | `MONTHLY_LIMIT` |
| 8 | `balance + chargeAmount ≤ 20,000`（ICOCA残高上限） | `WOULD_EXCEED_CARD_CAP` |
| 9 | `chargeAmount ≤ 20,000`（ICOCA 1回上限） | `EXCEEDS_PER_CHARGE_CAP` |
| 10 | メンテナンス時間帯（2:00–4:00 JST）でない | `MAINTENANCE_WINDOW` |
| 11 | Wi-Fi 条件（設定時） | `REQUIRES_WIFI` |
| 12 | 充電中条件（設定時） | `REQUIRES_CHARGING` |

すべて通れば `ChargeDecision.Proceed(chargeAmount)`。

日次・月次の集計は **Asia/Tokyo のカレンダー日 / 月**で行う（`LimitCalculator`）。

## 5. 残高取得チェーン（`BalanceRepository`）

`BalanceSource` インタフェースを優先度順に試し、最初に成功したものを採用する。

```kotlin
interface BalanceSource {
    val type: BalanceSourceType   // priority を持つ
    suspend fun isAvailable(): Boolean
    suspend fun read(): BalanceReading?   // null = 取得不可
}
```

| 優先度 | 実装 | 状態 |
|---|---|---|
| 1 | `IntentBalanceSource` | **スタブ**。公開 Intent が存在しないため常に `null`。`IcocaAppProbe` が公開 Intent を発見したらここに実装を足す拡張点 |
| 3 | `AccessibilityBalanceSource` | ICOCA アプリ前面時に画面テキストから抽出 |
| 4 | `ManualBalanceSource` | ユーザー入力値 / NFC 読み取り値 / Debug の疑似値。常に成立する Fallback |

**NFC は自動チェーンに入れていない。** 物理カードの読み取りはユーザーが端末にカードを
かざして初めて成立するため、バックグラウンドのチェーンから呼べる形にならない。
`FelicaBalanceReader` を `MainActivity` のリーダーモードから使い、
読めた値を `BalanceRepository.submit()` でチェーンの外から流し込む形にしている。

読み取り結果には **鮮度（`observedAt`）** を持たせ、
`BalanceReading.isStale(maxAge)` で古すぎる値による誤判定を防ぐ。
既定の最大鮮度は 24 時間。手動入力値は「乗車すれば減る」ため過信しない設計。

## 6. 残高テキストの解析（`BalanceTextParser`）

ICOCA アプリの残高表記ゆれに対応する。**誤検知が最も危険なので保守的に作る。**

- 受理する形: `¥2,840` / `2,840円` / `残高 2,840` / `2840` / `￥2,840`（全角）
- **必ず 0〜20,000 の範囲内であること**（ICOCA のカード内残額上限）。範囲外は棄却
- ラベル（`残高` / `SF` / `チャージ残高`）と同じノード・親・兄弟にある数値を優先
- ラベルが無い場合は **`preferLabeled = true` なら採用しない**（既定）。
  チャージ金額（5,000円）や運賃を残高と誤認することを防ぐため
- カード番号らしき長い数字列（8桁以上）は残高候補から除外

## 7. 自動操作の安全設計（指示書 §11, §26）

`SafetyGuard` がすべてのクリック前に以下を検査し、一つでも欠ければ **停止**する。

| ガード | 内容 |
|---|---|
| パッケージ確認 | 操作対象のウィンドウが `jp.co.westjr.android.icocaapp` であること。違えば即停止 |
| 署名確認 | ICOCA アプリの署名ハッシュが初回検出時と同一であること（別アプリのなりすまし対策） |
| 画面分類 | `ScreenClassifier` が既知の画面として分類できること。`UNKNOWN` なら停止 |
| 金額一致 | 押そうとしているノードのテキストが**設定したチャージ金額と一致**すること |
| 決済直前 | `PAYMENT_CONFIRM` に分類されたら **自動操作を終了しユーザーへ引き渡す** |
| 認証画面 | `AUTHENTICATION`（3Dセキュア / 生体 / パスワード）を検知したら**即停止**。入力は一切しない |
| エラー画面 | `ERROR` を検知したら停止して履歴に記録 |
| 座標タップ | `dispatchGesture` は使わない。`performAction(ACTION_CLICK)` のみ |
| ステップ上限 | 1回のフローで最大 12 ステップ。超えたら停止（無限ループ防止） |
| 無変化タイムアウト | 同一画面で 15 秒以上進展がなければ停止 |
| ドライラン | Debug 設定で「押す予定」をログ出力するだけにできる |

`ScreenClassifier` は **キーワードの重み付きスコア**で画面を判定する。
`AUTHENTICATION` と `ERROR` は**最優先で判定**する（誤って操作を続行しないため）。

## 8. バックグラウンド起動制限への対応（§19 / 調査 2.6）

**やらないこと**: WorkManager から直接 `startActivity` で ICOCA を起動する（BAL でブロックされる）。

**やること**:

```
BalanceCheckWorker (background)
   └─ 判定 → 通知を出すだけ
        └─ ユーザーが通知の「チャージ」をタップ
             └─ PendingIntent.getActivity → ChargeConfirmActivity（自アプリ・前面）
                  └─ ここは前面なので startActivity(ICOCA) が許可される
```

通知アクションは **`PendingIntent.getActivity` を直接使う**。
`BroadcastReceiver` を経由すると Android 12+ の通知トランポリン制限に抵触するため。

## 9. データモデル（Room）

### `charge_history`

| カラム | 型 | 内容 |
|---|---|---|
| `id` | Long PK | |
| `timestamp` | Long | 検知時刻（epoch millis） |
| `completedAt` | Long? | 終端時刻 |
| `balanceBefore` | Int? | 検知時の残高（不明なら null） |
| `balanceAfter` | Int? | 完了後に再取得した残高 |
| `threshold` | Int | そのとき適用された閾値 |
| `chargeAmount` | Int | チャージ予定額 |
| `status` | TEXT | `DETECTED`/`PENDING`/`CHARGING`/`SUCCESS`/`SUCCESS_UNVERIFIED`/`FAILED`/`CANCELLED` |
| `errorReason` | TEXT? | 失敗理由（列挙値） |
| `automationMethod` | TEXT | `ACCESSIBILITY`/`INTENT`/`MANUAL`/`NONE` |
| `userConfirmed` | INTEGER | ユーザーが確認操作をしたか |
| `balanceSource` | TEXT | 残高をどの手段で取ったか |
| `note` | TEXT? | 補足 |

### `balance_sample`

| カラム | 型 |
|---|---|
| `id` / `observedAt` / `balanceYen` / `source` / `confidence` |

残高の推移をダッシュボードに出すため、また「同じ残高の再観測」判定のために保持する。
保持期間は 180 日（古いものは起動時に削除）。

## 10. セキュリティ（§17, §24）

- **保存しない**: カード番号、セキュリティコード、パスワード、認証情報、生体情報
- `SecureLog` がログ出力前に以下をマスクする:
  - 12〜19 桁の連続数字（カード番号らしきもの） → `****`
  - `password` / `pin` / `cvv` / `暗証` を含む行 → 値をマスク
- AccessibilityService が取得したテキストは **残高抽出と画面分類にのみ使い、永続化しない**
  （Debug 画面のダンプのみ、メモリ上に直近 1 件だけ保持し、アプリ終了で消える）
- ネットワーク権限は要求しない（`INTERNET` を宣言しない）
- `allowBackup="false"` / `dataExtractionRules` で履歴の端末外流出を防ぐ

## 11. ログタグ（§24）

`ICOCA_MONITOR` / `ICOCA_BALANCE` / `ICOCA_AUTOMATION` / `ICOCA_PAYMENT` / `ICOCA_ERROR`

`SecureLog.d(Tag.MONITOR, "...")` の形で使う。マスク処理は `SecureLog` が一括で行う。

## 12. 依存性の注入

Hilt / Dagger は使わず、`ServiceLocator`（`IcocaApp` が保持する単純なシングルトン）で解決する。
理由: KSP のプロセッサを増やさずビルドの脆さを減らすため。
`domain` 層は純粋関数なので DI 不要、`data` 層は 4 つのシングルトンのみ。
