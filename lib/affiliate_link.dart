/// URL を検知して、設定済みのアフィリエイトID/タグを使ってアフィリエイトリンクへ
/// 自動変換するための純粋なロジック。Flutter に依存しないので単体テストしやすい。
library;

/// メッセージ中の URL 1件に対する変換結果。
class AffiliateLinkResult {
  const AffiliateLinkResult({required this.url, required this.isAffiliate});

  /// 表示・遷移に使う URL（変換できなければ元の URL のまま）。
  final String url;

  /// アフィリエイトリンクに変換できたかどうか。
  /// true の場合、UI 側で「[PR]」などの明示表示を行うために使う
  /// （ステルスマーケティング規制対応）。
  final bool isAffiliate;
}

/// チャット全体で共有するアフィリエイト設定。
///
/// 実際のアソシエイトタグ／アフィリエイトIDはユーザーごとに異なる個人情報のため
/// ソースコードにはハードコードせず、アプリの設定画面から入力してもらい
/// [AffiliateSettingsStore]（shared_preferences）に保存する。
class AffiliateLinkConverter {
  AffiliateLinkConverter._();

  /// Amazon アソシエイトの「トラッキングID」（例: yourname-22）。
  static String amazonAssociateTag = '';

  /// 楽天アフィリエイトの「アフィリエイトID」（例: 1234567.89012345）。
  static String rakutenAffiliateId = '';

  static final RegExp urlPattern = RegExp(r'https?://[^\s]+');

  /// メッセージ本文中の URL を、設定済みのアフィリエイトリンクに変換する。
  /// URL でない場合や設定が空の場合はそのまま返す。
  static AffiliateLinkResult convert(String rawUrl) {
    final uri = Uri.tryParse(rawUrl);
    if (uri == null || !uri.hasScheme) {
      return AffiliateLinkResult(url: rawUrl, isAffiliate: false);
    }
    final host = uri.host.toLowerCase();

    if (amazonAssociateTag.isNotEmpty && _isAmazonHost(host)) {
      final params = Map<String, String>.from(uri.queryParameters);
      params['tag'] = amazonAssociateTag;
      final converted = uri.replace(queryParameters: params).toString();
      return AffiliateLinkResult(url: converted, isAffiliate: true);
    }

    if (rakutenAffiliateId.isNotEmpty && _isRakutenHost(host)) {
      final encoded = Uri.encodeComponent(rawUrl);
      final converted =
          'https://hb.afl.rakuten.co.jp/hgc/$rakutenAffiliateId/?pc=$encoded&m=$encoded';
      return AffiliateLinkResult(url: converted, isAffiliate: true);
    }

    return AffiliateLinkResult(url: rawUrl, isAffiliate: false);
  }

  static bool _isAmazonHost(String host) {
    const domains = ['amazon.co.jp', 'amazon.com', 'amzn.to', 'amzn.asia'];
    return domains.any((d) => host == d || host.endsWith('.$d'));
  }

  static bool _isRakutenHost(String host) {
    // すでにアフィリエイト経由（hb.afl.rakuten.co.jp）のリンクは変換しない。
    if (host == 'hb.afl.rakuten.co.jp' || host.endsWith('.hb.afl.rakuten.co.jp')) {
      return false;
    }
    return host == 'rakuten.co.jp' || host.endsWith('.rakuten.co.jp');
  }
}
