# PROJECT_RESEARCH.md — モバイルICOCA 自動チャージ支援アプリ 事前調査

- 作成日: 2026-09-07
- 対象端末: Google Pixel 8a（日本国内モデル / おサイフケータイ対応）
- 調査方法: 公開情報（JR西日本公式、Android 公式ドキュメント、Google Play ポリシー、報道）による机上調査
- **重要な前提**: 本ドキュメントの「Android 側」の記述は Android 公式ドキュメントで裏付けが取れたもの。
  「ICOCA 側」の内部仕様（Deep Link・UI 構造）は**公開されていないため、机上では確定できない**。
  実機で確認する手順を `TECHNICAL_FEASIBILITY.md` に用意し、そこに結果を記入する運用とする。

---

## 0. 調査サマリ（結論先出し）

| # | 調査項目 | 結論 | 根拠 |
|---|---|---|---|
| 1 | ICOCA 公式 API / SDK は存在するか | **存在しない**（一般開発者向けの公開なし） | JR西日本はデベロッパーポータル・公開 API を提供していない |
| 2 | 外部アプリからモバイルICOCA残高を直接取得できるか | **不可能** | SE へのアクセスが OMAPI のアクセス制御 / FeliCa Networks 契約で閉じている |
| 3 | NFC/FeliCa API で自端末のモバイルICOCA残高を読めるか | **不可能** | 自端末の NFC リーダーモードで自端末内蔵 SE は読めない（アーキテクチャ上） |
| 4 | NFC/FeliCa API で「物理」ICOCAカードの残高を読めるか | **可能**（別カード扱い） | `NfcF` + FeliCa 標準コマンド。ただしモバイルICOCAの残高ではない |
| 5 | ICOCA アプリを Intent で起動できるか | **ほぼ確実に可能** | `PackageManager.getLaunchIntentForPackage()` は任意のアプリに対して有効 |
| 6 | チャージ画面への Deep Link / Intent は公開されているか | **公開情報なし**（実機で exported コンポーネントを列挙して確認する） | 公式ドキュメント・URLスキーム情報が存在しない |
| 7 | AccessibilityService で ICOCA 画面のテキストを取得できるか | **実機検証が必須**（技術的には可能だが、金融アプリ側の対策で失敗しうる） | Android API 上は可能。ICOCA 側の実装次第 |
| 8 | 完全自動チャージ（決済まで無人）は可能か | **不可能かつ実装しない** | 3Dセキュア/生体認証は突破禁止（指示書 §2, §9） |
| 9 | モバイルICOCA 自身にオートチャージ機能はあるか | **ない**（Android/iPhone とも） | JR西日本公式は「クイックチャージ・オートチャージはできない」旨を案内 |

**→ 本アプリの現実的な最終到達点は「残高監視 → 閾値割れ検知 → 安全確認 → 通知 →（ワンタップで）ICOCA アプリ起動 →
可能ならチャージ画面まで自動遷移 →金額選択 → 決済はユーザー確認 → 結果確認 → 履歴保存」。**
決済の自動実行は行わない。

---

## 1. ICOCA 側の調査

### 1.1 モバイルICOCA for Android の現在の仕様

| 項目 | 内容 |
|---|---|
| アプリ名 | モバイルICOCA |
| パッケージ名 | `jp.co.westjr.android.icocaapp` |
| 提供元 | 西日本旅客鉄道株式会社（JR西日本） |
| 提供開始 | 2023年3月22日 |
| 必要条件 | おサイフケータイ対応（FeliCa 搭載）Android 端末。Pixel 8a（国内モデル）は対応 |
| 主な機能 | 残高表示、チャージ、定期券、WESTER ポイント連携、利用履歴、機種変更、払戻 |

**Pixel 8a について**: 日本国内で販売された Pixel 8a は FeliCa を搭載しており、おサイフケータイ／モバイルICOCA に対応する。
海外モデルの Pixel は SE に FeliCa がプロビジョニングされておらず、非対応（改造前提の手法は本プロジェクトの対象外）。

### 1.2 残高確認方法

1. モバイルICOCA アプリのメイン画面に残高が表示される（アプリ内表示）
2. おサイフケータイ アプリ（`com.felicanetworks.mfm.main`）の対応サービス一覧から残高を参照
3. 改札機・チャージ機・レジ等の外部リーダー

いずれも**「画面に表示される」だけで、外部アプリへ値を渡す公式な口は存在しない**。

### 1.3 チャージ方法

| 方法 | 概要 | 自動化余地 |
|---|---|---|
| クレジットカード（アプリ内） | アプリに登録済みカードで即時チャージ | 金額選択までは自動化余地あり。決済確認・3Dセキュアは不可 |
| 銀行口座（アプリ内） | 登録口座から即時チャージ | 同上 |
| 現金（コンビニ / 駅チャージ端末） | コードを発行し店頭で支払い | 自動化不可（物理操作） |

**制約（重要 — アプリのロジックに反映する）**

- 1回あたりのチャージ上限: **20,000円**
- 1日あたりのチャージ上限: **20,000円**
- **カード内残額の上限: 20,000円**（超えるチャージは不可）
- 深夜帯（概ね **2:00〜4:00**）はシステムメンテナンスでチャージ不可
- **オートチャージ機能はモバイルICOCAには存在しない**（＝本アプリの存在意義）

### 1.4 外部アプリからの起動可否

- `PackageManager.getLaunchIntentForPackage("jp.co.westjr.android.icocaapp")` によるランチャー起動は
  Android の標準機能であり、**追加の権限も相手アプリの協力も不要**（`<queries>` 宣言は必要）。
- ただし**バックグラウンドからの起動は Android の BAL 制限でブロックされる**（後述 2.6）。
  → 本アプリは「通知のアクションをユーザーがタップ」を起点にする設計とする。

### 1.5 Deep Link / チャージ画面への Intent

- 公開された URL スキーム・App Link・Intent の仕様は**確認できなかった**（公式ドキュメント・技術記事とも存在しない）。
- **非公開の内部 Activity を直接叩く行為は行わない。** 内部 Activity は `exported=false` が通常であり、
  仮に exported であっても、意図しない状態遷移・利用規約上のリスクがあるため、
  本アプリは **「`exported=true` かつ Intent フィルタで公開されているもののみ」** を対象とする。
- 実機で列挙する仕組みをアプリ内に実装した（`IcocaAppProbe`）。結果は Debug 画面に表示され、
  `TECHNICAL_FEASIBILITY.md` に転記する運用。

### 1.6 公式 API / SDK の存在

- JR西日本は一般開発者向けの ICOCA 残高取得 / チャージ API を公開していない。
- WESTER のポイント・会員系についても公開デベロッパーポータルは存在しない。
- **結論: 優先順位1（公式 API/SDK）は利用不可。**

---

## 2. Android 側の調査

### 2.1 NFC API / FeliCa API

- `android.nfc` の `NfcF` で FeliCa カードとの通信は可能（Polling / Read Without Encryption）。
- ICOCA を含む交通系 IC カードの**履歴・残高は Read Without Encryption で読める領域**にあり、
  サービスコード `0x090F`（履歴）でアクセスできる。これは暗号解読ではなく、
  **カード側が無認証で読み出しを許可している公開領域**である（指示書 §2 に抵触しない）。
- **ただし読めるのは「外部にかざした物理カード」のみ。**

### 2.2 自端末の内蔵 SE（モバイルICOCA）が読めない理由

1. NFC コントローラのリーダーモードは**外部のタグ／カード**を対象とする。
   自端末の SE は同じコントローラの内側にあり、リーダーモードのターゲットにならない。
2. ホスト側から SE へアクセスする正規経路は **OMAPI（`android.se.omapi.SEService`）**。
   OMAPI は GlobalPlatform の Secure Element Access Control により、
   **アプレット（AID）ごとに「どのパッケージ名 / 署名のアプリがアクセスしてよいか」がSE内のアクセスルールで制御される。**
   ICOCA のアプレットが第三者アプリにアクセス権を与えることはない。
3. おサイフケータイ端末の FeliCa 領域は **モバイルFeliCaクライアント（`com.felicanetworks.mfc`）** 経由でのみ扱われ、
   その MFC API を利用するには **FeliCa Networks とのライセンス契約**が必要（個人開発者は利用できない）。

→ **自作アプリからモバイルICOCAの残高を直接読むことは、正規の方法では不可能。**
（不可能を突破する手段＝SE の不正アクセス／暗号解析は指示書 §2 で明確に禁止されており、実装しない。）

### 2.3 Host Card Emulation (HCE)

- `HostApduService` はこちらが**カードとして振る舞う**ための API。残高の読み取りには使えない。
- FeliCa の HCE-F（`HostNfcFService`）も同様に「自分がカードになる」機能であり、本用途に無関係。
- **本アプリでは使用しない。**

### 2.4 AccessibilityService

Android 公式ドキュメントで確認できた事実:

- マニフェストで `android.permission.BIND_ACCESSIBILITY_SERVICE` を要求し、
  `android.accessibilityservice.AccessibilityService` アクションの intent-filter を持つ `<service>` として宣言する。
- `res/xml` の設定で `android:canRetrieveWindowContent="true"` を指定すると UI 階層を読める。
- `android:packageNames` で**監視対象アプリを限定できる**（本アプリでは ICOCA アプリのみに限定 → 安全設計）。
- `AccessibilityNodeInfo` から `text` / `contentDescription` / `viewIdResourceName` が取得できる。
- `performAction(ACTION_CLICK)` でクリック可能ノードを押せる。
- `performGlobalAction(GLOBAL_ACTION_BACK)` で戻る操作ができる。
- `dispatchGesture()` は座標指定のジェスチャ。**指示書 §10/§26 により本アプリでは原則使用しない。**
- **ユーザーが Android の「設定 > ユーザー補助」から明示的に有効化しない限り動作しない。**

### 2.5 UsageStatsManager / NotificationListenerService

| API | 用途 | 本アプリでの採否 |
|---|---|---|
| `UsageStatsManager` | 前面アプリの推定、利用統計 | **不採用**。`PACKAGE_USAGE_STATS` は特別権限で、AccessibilityService があれば前面アプリは判別できるため不要（最小権限の原則 §18） |
| `NotificationListenerService` | 他アプリの通知本文の読み取り | **不採用**。ICOCA アプリが残高を通知する仕様は確認できず、全通知を読める非常に強い権限のため過剰 |

### 2.6 Activity 起動 / Intent / Deep Link / バックグラウンド起動制限

Android 公式ドキュメント（Background Activity Launch）で確認できた事実:

- Android 10 (API 29) 以降、バックグラウンドからの Activity 起動は原則ブロックされる。
- 許可される主な例外:
  - アプリが可視のウィンドウを持っている
  - **システムが送った PendingIntent 由来（＝通知をユーザーがタップした場合）**
  - `SYSTEM_ALERT_WINDOW` 権限が付与されている
  - 特権を持つ Service にバインドされている
- Android 14 (API 34) 以降: PendingIntent の**送信側**が
  `setPendingIntentBackgroundActivityStartMode()` で明示的に opt-in する必要がある。
- Android 15 (API 35) 以降: PendingIntent の**作成側**も opt-in が必要。
- Android 16 以降は `StrictMode.detectBlockedBackgroundActivityLaunch()` で検出可能。

→ **設計上の帰結**: WorkManager のバックグラウンド実行から直接 ICOCA アプリを起動してはならない。
必ず「通知 → ユーザーがアクションをタップ → 自アプリの Activity → ICOCA アプリ起動」という
ユーザー起点のチェーンを通す。本アプリはこの方式で実装している。
`SYSTEM_ALERT_WINDOW` は要求しない（過剰権限の回避 §18）。

### 2.7 WorkManager / AlarmManager / Foreground Service

- `PeriodicWorkRequest` の**最小繰り返し間隔は 15 分**（JobScheduler と同じ）。
- flex interval により「周期の後半 N 分の間に実行」という指定が可能。
- `Constraints` で `NetworkType.UNMETERED`（Wi-Fi 相当）、`setRequiresCharging(true)`、
  `setRequiresBatteryNotLow(true)` を指定できる。**設定画面の「Wi-Fi時のみ / 充電中のみ」はこれで実現する。**
- Doze / App Standby Bucket により、実際の実行は指定より遅延しうる。**厳密な定刻実行は保証されない。**
- `setExactAndAllowWhileIdle` を使う AlarmManager は `SCHEDULE_EXACT_ALARM` 特別権限が要り、
  本用途（残高監視）に定刻性は不要なため**不採用**。
- チャージフロー実行中のみ Foreground Service（`specialUse`）でユーザーに可視化する。

### 2.8 Android 16 / 17 と Google Play ポリシーの制約（最重要）

- **Google Play**: 2026年8月31日以降、新規・更新アプリは **targetSdk 36（Android 16）以上**が必須。
- **AccessibilityService API の利用ポリシー**:
  - `isAccessibilityTool="true"` を宣言できるのは
    **障がいのある利用者を支援する目的のサービスのみ**。
  - 本アプリは残高監視の自動化が目的であり、**`isAccessibilityTool` は宣言できない（`false` にする）**。
    虚偽申告は Play Protect の警告対象。
  - `isAccessibilityTool` を宣言しないアプリは、**アプリ内での prominent disclosure（明示的な説明）と
    ユーザー同意**が必須。→ 本アプリでは設定画面に同意フローを実装した。
  - 2026年1月28日以降、AccessibilityService を使うアプリの審査が厳格化されている。
- **Android 17 + Advanced Protection Mode（最重要リスク）**:
  - Android 17 では、**Advanced Protection Mode が有効な端末で、
    `isAccessibilityTool="true"` でないアプリのユーザー補助権限が自動的に剥奪される**。
    有効化も不可になる。
  - Pixel 8a は Android 17 の配信対象。**将来的に自動操作機能が使えなくなる可能性が高い。**

→ **設計上の帰結**:
1. **AccessibilityService は「あれば動く追加機能」として設計し、無くても本体が成立する構造にする**
   （指示書 §27 の Fallback を最低保証とする）。
2. Google Play への公開は現実的でない。**個人利用のサイドロード配布を前提**とする。
3. アプリ内で Advanced Protection Mode によるリスクをユーザーに明示する。

### 2.9 バッテリー / バックグラウンド制約

- 常駐監視・高頻度ポーリングは禁止（指示書 §19）。本アプリの既定チェック間隔は **6時間**、最短でも 15 分。
- 電池最適化の除外は**要求しない**（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` は Play ポリシー上も慎重が必要）。
  代わりに「チェックが遅延しうる」ことを UI に明示する。

---

## 3. 自動操作（AccessibilityService）に関する調査

### 3.1 技術的に可能なこと（Android API として）

| 操作 | API | 可否 |
|---|---|---|
| ICOCA アプリの前面検知 | `AccessibilityEvent.packageName` | 可能 |
| 画面変更検知 | `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` | 可能 |
| テキスト取得 | `AccessibilityNodeInfo.text` / `contentDescription` | 可能（相手が公開していれば） |
| ボタン検索 | `findAccessibilityNodeInfosByText()` / `ByViewId()` | 可能 |
| クリック | `performAction(ACTION_CLICK)` | 可能（`isClickable` なノードに限る） |
| 金額入力 | `ACTION_SET_TEXT` | 可能（`isEditable` なノードに限る） |
| 戻る | `performGlobalAction(GLOBAL_ACTION_BACK)` | 可能 |
| アプリ起動 | AccessibilityService から `startActivity` | 可能な場合があるが**本アプリでは使わない**（ユーザー起点に統一） |

### 3.2 実機検証が必須で、机上では確定できないこと

以下は **ICOCA アプリの実装次第**であり、推測で「できる」と判断してはならない項目:

1. ICOCA アプリのノードに `text` / `contentDescription` が設定されているか
   （金融アプリでは意図的に空にしている場合がある）
2. `viewIdResourceName` が難読化されていないか（R8 のリソース難読化で `res/xxx` になる場合がある）
3. ICOCA アプリが AccessibilityService の有効を検知して動作を拒否しないか
   （金融アプリでは「ユーザー補助が有効です」と警告して機能を止める実装が実在する）
4. チャージ画面の金額ボタンが `Button` として公開されているか、Canvas 描画で不可視か
5. 決済確認・3Dセキュアの WebView がノードを公開するか

→ 検証手順とチェックリストを `TECHNICAL_FEASIBILITY.md` に用意した。**結果が出るまで「可能」と記載しない。**

### 3.3 禁止事項の遵守

指示書 §2 の禁止事項に対する本プロジェクトの方針:

| 禁止事項 | 本プロジェクトの対応 |
|---|---|
| ICOCA の暗号解析 | 一切行わない。暗号領域に触れるコードは存在しない |
| FeliCa セキュア領域の不正読み書き | 行わない。OMAPI / MFC も使用しない |
| 内部データ改変 | 行わない |
| 決済認証の回避 | 決済確認画面を検知したら**必ず停止**する実装（`SafetyGuard`） |
| 3Dセキュア突破 | 検知したら停止しユーザーに引き渡す。自動入力しない |
| カード情報の取得・保存 | 一切扱わない。テキスト取得時にカード番号様の文字列はマスクして破棄 |
| 通信の傍受・改ざん | 行わない。ネットワーク権限すら ICOCA に対して使わない |
| root 前提 | しない |
| セキュリティ機構の回避 | しない。剥奪されたら素直に Fallback へ落ちる |
| 規約抵触の可能性が高い方法 | 非公開 Intent の直接起動をしない。座標タップをしない |

---

## 4. 残高取得手段の判定（指示書 §5）

| | 手段 | 判定 | 理由 |
|---|---|---|---|
| A | 公式 API から残高取得 | **不可能** | 公開 API が存在しない |
| B | 公式 Intent 等から残高取得 | **不可能（現時点）** | 残高を返す公開 Intent の情報が存在しない。`IcocaAppProbe` で実機列挙して再判定 |
| C | NFC / FeliCa API | **条件付きで可能** | 物理 ICOCA カードのみ可。**自端末のモバイルICOCAは不可** |
| D | AccessibilityService で画面から残高文字列を取得 | **実機検証が必要** | 技術的には可能。ICOCA アプリの実装次第。規約上はグレー（ユーザー自身の画面を自分のために読む用途） |
| E | 通知・ウィジェット等から取得 | **不可能（現時点）** | ICOCA アプリが残高ウィジェット / 残高通知を提供している確証が得られなかった |
| F | その他 Android 公開 API | **不可能** | OMAPI はアクセス制御で拒否。MFC はライセンス契約が必要 |
| G | 手動入力（Fallback） | **常に可能** | ユーザーが残高を入力／確認する。**最低保証の手段として実装** |

**採用する優先順位チェーン**: `B(probe) → C(物理カード) → D(Accessibility) → G(手動)`

---

## 5. 参考資料

- モバイルICOCA for Android（JR西日本 公式）: https://www.jr-odekake.net/icoca/mobileicoca/
- モバイルICOCA（Google Play）: https://play.google.com/store/apps/details?id=jp.co.westjr.android.icocaapp
- Android: Create your own accessibility service — https://developer.android.com/guide/topics/ui/accessibility/service
- Android: Restrictions on starting activities from the background — https://developer.android.com/guide/components/activities/background-starts
- Android: Define your work requests (WorkManager) — https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- Android: NFC basics — https://developer.android.com/guide/topics/connectivity/nfc/nfc
- AOSP: OMAPI vendor stable interface — https://source.android.com/docs/security/features/open-mobile-api
- AOSP: Secure NFC — https://source.android.com/docs/core/connect/secure-nfc
- Google Play: Use of the AccessibilityService API — https://support.google.com/googleplay/android-developer/answer/10964491
- Google Play: Target API level requirements — https://developer.android.com/google/play/requirements/target-sdk
- FeliCa Networks モバイルFeliCaプラットフォーム — https://www.felicanetworks.co.jp/mfelica_pf.html
- ソニー FeliCa SDK — https://www.sony.co.jp/Products/felica/business/products/sdk/
