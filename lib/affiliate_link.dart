/// URL を検知して、アプリ運営者のアフィリエイトID/タグを使って
/// アフィリエイトリンクへ自動変換するための純粋なロジック。
/// Flutter に依存しないので単体テストしやすい。
library;

import 'affiliate_config.dart';

/// リンク変換対象のサービス種別。クリック計測の集計に使う。
enum AffiliateService { amazon, rakuten }

/// メッセージ中の URL 1件に対する変換結果。
class AffiliateLinkResult {
  const AffiliateLinkResult({
    required this.url,
    required this.isAffiliate,
    this.service,
  });

  /// 表示・遷移に使う URL（変換できなければ元の URL のまま）。
  final String url;

  /// アフィリエイトリンクに変換できたかどうか。
  /// true の場合、UI 側で「[PR]」などの明示表示を行うために使う
  /// （ステルスマーケティング規制対応）。
  final bool isAffiliate;

  /// 変換元のサービス種別（アフィリエイトでない場合は null）。
  final AffiliateService? service;
}

/// チャット全体で共有するアフィリエイト設定。
///
/// 既定値はビルド時に固定される [AffiliateConfig] から読み込まれる。
/// テストなどで一時的に上書きできるよう static フィールドとして公開しているが、
/// アプリ本体のUIからは変更できない（＝収益の宛先を利用者が書き換えられない）。
class AffiliateLinkConverter {
  AffiliateLinkConverter._();

  static String amazonAssociateTag = AffiliateConfig.amazonAssociateTag;
  static String rakutenAffiliateId = AffiliateConfig.rakutenAffiliateId;

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
      return AffiliateLinkResult(
        url: converted,
        isAffiliate: true,
        service: AffiliateService.amazon,
      );
    }

    if (rakutenAffiliateId.isNotEmpty && _isRakutenHost(host)) {
      final encoded = Uri.encodeComponent(rawUrl);
      final converted =
          'https://hb.afl.rakuten.co.jp/hgc/$rakutenAffiliateId/?pc=$encoded&m=$encoded';
      return AffiliateLinkResult(
        url: converted,
        isAffiliate: true,
        service: AffiliateService.rakuten,
      );
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
