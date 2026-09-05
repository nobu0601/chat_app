import 'package:shared_preferences/shared_preferences.dart';

import 'affiliate_link.dart';

/// [AffiliateLinkConverter] の設定値（アソシエイトタグ／アフィリエイトID）を
/// 端末内（shared_preferences）に保存・復元するための入出力層。
class AffiliateSettingsStore {
  AffiliateSettingsStore._();

  static const _amazonTagKey = 'affiliate_amazon_tag';
  static const _rakutenIdKey = 'affiliate_rakuten_id';

  /// アプリ起動時に呼び出し、保存済みの設定を [AffiliateLinkConverter] に反映する。
  static Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    AffiliateLinkConverter.amazonAssociateTag =
        prefs.getString(_amazonTagKey) ?? '';
    AffiliateLinkConverter.rakutenAffiliateId =
        prefs.getString(_rakutenIdKey) ?? '';
  }

  /// 設定画面から呼び出し、新しい値を保存すると同時に即時反映する。
  static Future<void> save({
    required String amazonTag,
    required String rakutenId,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_amazonTagKey, amazonTag);
    await prefs.setString(_rakutenIdKey, rakutenId);
    AffiliateLinkConverter.amazonAssociateTag = amazonTag;
    AffiliateLinkConverter.rakutenAffiliateId = rakutenId;
  }
}
