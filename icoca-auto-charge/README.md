# ICOCA自動チャージ支援（icoca-auto-charge）

モバイルICOCA の残高を監視し、設定した金額を下回ったら通知して、
ワンタップで公式 ICOCA アプリのチャージへ進めるための Android アプリです。

対象端末: **Google Pixel 8a（日本国内モデル / おサイフケータイ対応）**

---

## ⚠️ 最初に読んでください — このアプリにできないこと

調査の結果、**「完全な自動チャージ」は技術的にも規約的にも実現できません。** 理由は次のとおりです。

| できないこと | 理由 |
|---|---|
| 外部アプリからモバイルICOCAの残高を直接読む | 端末内蔵セキュアエレメントへのアクセスが閉じている。OMAPI はアクセス制御で拒否され、モバイルFeliCa の API は FeliCa Networks とのライセンス契約が必要 |
| ICOCA の公式 API でチャージする | JR西日本は一般開発者向けの API / SDK を公開していない |
| チャージ画面へ公式 Deep Link で直行する | 公開された URL スキーム・Intent の情報が存在しない |
| 決済を自動で確定する | 3Dセキュア・生体認証・パスワードの自動突破は行わない（設計上の禁止事項） |

詳細と根拠は [`docs/PROJECT_RESEARCH.md`](docs/PROJECT_RESEARCH.md) にまとめています。

**このアプリが実際にやること:**

```
普段どおりICOCAを使う
  ↓
定期チェック（既定6時間ごと）で残高を確認
  ↓
3,000円未満を検知
  ↓
安全チェック（上限・クールダウン・二重チャージ防止・メンテナンス時間帯）
  ↓
通知「ICOCA残高が少なくなっています」［チャージ］［後で］
  ↓ ユーザーがタップ
ICOCA公式アプリを起動
  ↓ （ユーザー補助が有効な場合のみ）チャージ画面・金額選択まで補助
  ↓
決済の確認と本人認証は必ずユーザー自身が操作
  ↓
残高を再取得して成否を判定
  ↓
履歴を保存
```

---

## 現在の状態

| 項目 | 状態 |
|---|---|
| 設計・実装 | 完了 |
| Unit Test（純粋ロジック層 70件） | **✅ 全件成功**（変異テストでテストの有効性も確認済み） |
| コンパイル（Compose UI 以外の全 Kotlin） | **✅ エラー0件 / 256クラス** |
| コンパイル（Compose UI 7ファイル） | **未検証** |
| Gradle ビルド（依存解決込み） | **未実行** |
| Android Lint | **未実行** |
| 実機テスト（Pixel 8a） | **未実施** |

> このプロジェクトは Android SDK に到達できないクラウド環境で実装されました。
> `dl.google.com` / `maven.google.com` がネットワークポリシーで遮断されているためです。
>
> ただし `repo1.maven.org` には到達できたため、そこから
> Kotlin コンパイラ・JUnit・**Android 16 のフレームワーク jar**（Robolectric の `android-all`）を取得し、
> AndroidX 部分は最小スタブで補って、**Compose UI 以外のすべての Kotlin を実際にコンパイル
> （エラー0件・256クラス）し、Unit Test 70件を実行して全件成功を確認しています。**
> 詳細と限界は [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) §0。
>
> **未検証なのは Compose UI 7ファイルのコンパイル・Gradle の依存解決・Lint・実機動作です。**
> 下の手順で一度ビルドしてください。

---

## ビルドとインストール

### 必要なもの

- Android Studio（Ladybug 以降推奨） または JDK 17 + Android SDK (API 36)
- Pixel 8a（USB デバッグを有効化）

### 手順

```bash
cd icoca-auto-charge

# SDK の場所を指定する（Android Studio で開く場合は自動生成される）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 1) コンパイル
./gradlew :app:assembleDebug

# 2) Unit Test（JVM。実機不要）
./gradlew :app:testDebugUnitTest

# 3) Android Lint
./gradlew :app:lintDebug
# 結果: app/build/reports/lint-results-debug.html

# 4) 実機へインストール
./gradlew :app:installDebug
```

### ビルドが通らないとき

依存のバージョンは [`gradle/libs.versions.toml`](gradle/libs.versions.toml) に集約しています。
まずここの数字だけを調整してください（AGP / Kotlin / KSP はセットで上げる必要があります）。

---

## 初回セットアップ（端末側）

1. アプリを起動する
2. 通知の許可を求められたら **許可** する（許可しないと残高低下をお知らせできません）
3. モバイルICOCA アプリをインストール・セットアップしておく
4. `設定` タブでチャージ開始残高・チャージ金額・上限を確認する
5. （任意）自動操作を使う場合:
   - `設定` → 「自動操作（ユーザー補助）」を ON → 説明を読んで同意
   - Android の `設定 > ユーザー補助 > インストール済みアプリ > ICOCA自動チャージ支援` を ON

> 自動操作は **無くてもアプリは成立します**。
> ユーザー補助が使えない場合は「通知 → ワンタップで ICOCA を開く」運用になります。

---

## Pixel 8a での実機検証手順

**未実施です。** 検証の手順・記入欄は [`docs/TECHNICAL_FEASIBILITY.md`](docs/TECHNICAL_FEASIBILITY.md)、
テスト項目は [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) にあります。

検証を助けるため、**アプリ内に Debug 画面（開発ビルドのみ）** を用意しました。
Android Studio も adb も無しで、ほとんどの項目が端末単体で確認できます。

| Debug 画面の機能 | 何が分かるか |
|---|---|
| ICOCAアプリ検出 | インストールの有無、バージョン、署名 |
| exported コンポーネントを列挙 | チャージ画面へ行ける公式 Intent が存在するか |
| 画面ダンプを記録 | ユーザー補助で ICOCA の画面テキストが読めるか（**最重要**） |
| 自動操作ドライラン | クリックせずに「押す予定」だけをログに出す |
| NFC残高読み取り | 物理 ICOCA カードが読めるか |
| OMAPI 調査 | セキュアエレメントに到達できないことの確認 |
| 残高を疑似設定 | 2,999 / 3,000 / 3,001 / 0 円の境界テスト |
| Stateをリセット | 二重チャージ防止のテスト後の後始末 |

### ログの確認

```bash
adb logcat -s ICOCA_MONITOR ICOCA_BALANCE ICOCA_AUTOMATION ICOCA_PAYMENT ICOCA_ERROR

# バックグラウンド起動がブロックされていないかの確認
adb logcat | grep -i "Background activity launch"
```

---

## 安全のための設計

- **二重チャージ防止を4段構えにしている**（進行中の一意性 / 同一残高の再観測判定 / クールダウン / タイムアウト解放）。
  状態は端末に永続化しているので、プロセス終了や再起動をまたいでも二重に発火しません。
- **決済の直前で必ず止まります。** 決済確認・3Dセキュア・生体認証・パスワードの画面を検知したら、
  自動操作を終了してユーザーに操作を引き渡します。認証を自動入力することはありません。
- **座標タップをしません。** ノードの `text` / `contentDescription` / `viewIdResourceName` でしか要素を掴みません。
  ICOCA アプリの UI が変わった場合は、誤操作せずに安全に停止します。
- **金額が一致しないボタンは押しません。** 設定した金額と完全一致するラベルのみを対象にします。
- **監視対象は ICOCA アプリだけ。** ユーザー補助の設定で `packageNames` を絞っており、他のアプリの画面は見ません。
- **ネットワーク通信をしません。** `INTERNET` 権限を宣言していません。
- **カード情報・認証情報を保存もログ出力もしません。** ログは出力前に自動でマスクします。

## 権限

| 権限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | 残高低下・結果の通知 |
| `RECEIVE_BOOT_COMPLETED` | 再起動後に監視を張り直す |
| `ACCESS_NETWORK_STATE` | 「Wi-Fi時のみ」の判定 |
| `FOREGROUND_SERVICE` / `..._SPECIAL_USE` | チャージ処理中の可視化 |
| `NFC` | 物理 ICOCA カードの読み取り（任意） |
| ユーザー補助 | 自動操作（任意・ユーザーが設定画面で明示的に有効化） |

`INTERNET`・`SYSTEM_ALERT_WINDOW`・`PACKAGE_USAGE_STATS`・通知読み取りは**要求しません**。

---

## Google Play への公開について

**公開は現実的ではありません。**

- 本アプリは障がい支援ツールではないため `isAccessibilityTool="true"` を宣言できません
  （虚偽申告は Play Protect の警告対象）。宣言しないアプリには明示的な開示と同意が必須で、
  2026年1月28日以降、審査も厳格化されています。
- さらに **Android 17 の「高度な保護モード」では、支援ツール以外のユーザー補助権限が自動的に剥奪されます。**
  Pixel 8a は Android 17 の配信対象です。

したがって **個人利用のサイドロード**を前提としています。
また、自動操作が将来使えなくなっても、通知 Fallback だけで運用が続けられる構造にしています。

---

## ドキュメント

| ファイル | 内容 |
|---|---|
| [`docs/PROJECT_RESEARCH.md`](docs/PROJECT_RESEARCH.md) | モバイルICOCA / Android 側の事前調査と、各手段の可否判定 |
| [`docs/TECHNICAL_FEASIBILITY.md`](docs/TECHNICAL_FEASIBILITY.md) | 実機検証の手順と記入欄（**未実施**） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 状態機械・判定ロジック・安全設計 |
| [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) | Unit Test / Lint / 実機テスト21項目 |

## ライセンスと注意

個人利用を目的とした実装です。
モバイルICOCA は西日本旅客鉄道株式会社のサービスであり、本アプリは同社とは無関係です。
利用にあたっては ICOCA の利用規約を確認してください。
