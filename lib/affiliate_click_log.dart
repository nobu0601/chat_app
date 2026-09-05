import 'package:cloud_firestore/cloud_firestore.dart';

import 'affiliate_link.dart';

/// アフィリエイトリンクのクリックを Firestore に記録するための入出力層。
///
/// 収益化の効果測定（どの部屋・どのサービスのリンクがどれくらい
/// クリックされているか）のために使う。個人を特定する情報は保存しない。
class AffiliateClickLog {
  AffiliateClickLog._();

  static CollectionReference<Map<String, dynamic>> get _collection =>
      FirebaseFirestore.instance.collection('affiliate_clicks');

  /// アフィリエイトリンクが開かれたことを記録する。
  static Future<void> record({
    required String room,
    required AffiliateLinkResult result,
  }) async {
    if (!result.isAffiliate || result.service == null) return;
    final now = DateTime.now();
    await _collection.add({
      'date': Timestamp.fromDate(now),
      'room': room,
      'service': result.service!.name,
    });
  }

  /// サービス種別ごとのクリック件数を取得する（集計クエリ）。
  static Future<int> countByService(AffiliateService service) async {
    final snapshot =
        await _collection.where('service', isEqualTo: service.name).count().get();
    return snapshot.count ?? 0;
  }

  /// 全体のクリック件数を取得する。
  static Future<int> countAll() async {
    final snapshot = await _collection.count().get();
    return snapshot.count ?? 0;
  }
}
