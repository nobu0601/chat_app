# chat application
A chat application built with Flutter allows users to chat by entering their name and the name of a chat room.
To chat with others, they must use the same chat room name.

## アフィリエイト機能（収益化）

チャットメッセージに Amazon / 楽天の商品URLが含まれている場合、自動的に
アフィリエイトリンクへ変換して表示します（変換されたリンクには規制対応のため
`[PR]` 表示が付きます）。

**公開前に必ず設定してください**: 収益の宛先は `lib/affiliate_config.dart` の
定数で固定されます（利用者ごとに変更できる設定画面はあえて用意していません。
誰でも変更できてしまうと収益が横取りされてしまうためです）。

```dart
// lib/affiliate_config.dart
static const String amazonAssociateTag = 'あなたのAmazonアソシエイトID';
static const String rakutenAffiliateId = 'あなたの楽天アフィリエイトID';
```

- Amazon アソシエイトID: https://affiliate.amazon.co.jp/ で取得
- 楽天アフィリエイトID: https://affiliate.rakuten.co.jp/ で取得

いずれも空文字のままの場合は、そのサービスのリンク変換は行われません
（通常のリンクとして表示されます）。

### クリック実績の確認

入室画面右上の棒グラフアイコンから「アフィリエイト実績」画面を開くと、
Firestore の `affiliate_clicks` コレクションに記録されたクリック数を
サービス別に確認できます。

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Lab: Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Cookbook: Useful Flutter samples](https://docs.flutter.dev/cookbook)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.
