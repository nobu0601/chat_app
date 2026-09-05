/// このアプリ全体で使うアフィリエイトID・タグの設定。
///
/// ここで指定したID宛てに、ユーザーがチャットに貼った商品リンクの
/// クリックに応じた成果報酬が発生します（＝アプリ運営者の収益）。
///
/// 特定のユーザーだけが自分のIDに書き換えられると収益が横取りされてしまうため、
/// 端末ごとの設定画面などでは変更できないようにし、ビルド時にこの定数へ
/// 直接設定する方式にしています。
///
/// 公開する前に、必ず自分自身の Amazon アソシエイト / 楽天アフィリエイトの
/// 登録情報に書き換えてください。空文字のままだと該当サービスのリンク変換は
/// 行われません。
class AffiliateConfig {
  AffiliateConfig._();

  /// Amazon アソシエイトの「トラッキングID」（例: 'yourname-22'）。
  /// https://affiliate.amazon.co.jp/ で取得できます。
  static const String amazonAssociateTag = '';

  /// 楽天アフィリエイトの「アフィリエイトID」（例: '1234567.89012345'）。
  /// https://affiliate.rakuten.co.jp/ で取得できます。
  static const String rakutenAffiliateId = '';
}
