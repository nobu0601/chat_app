import 'package:flutter_test/flutter_test.dart';
import 'package:chapter10/affiliate_link.dart';

void main() {
  tearDown(() {
    AffiliateLinkConverter.amazonAssociateTag = '';
    AffiliateLinkConverter.rakutenAffiliateId = '';
  });

  group('AffiliateLinkConverter', () {
    test('タグ未設定の場合は URL をそのまま返す', () {
      final result =
          AffiliateLinkConverter.convert('https://www.amazon.co.jp/dp/B012345678');
      expect(result.isAffiliate, isFalse);
      expect(result.url, 'https://www.amazon.co.jp/dp/B012345678');
    });

    test('Amazon の商品URLに tag パラメータを付与する', () {
      AffiliateLinkConverter.amazonAssociateTag = 'mytag-22';
      final result =
          AffiliateLinkConverter.convert('https://www.amazon.co.jp/dp/B012345678');
      expect(result.isAffiliate, isTrue);
      expect(result.url, contains('tag=mytag-22'));
      expect(result.url, startsWith('https://www.amazon.co.jp/dp/B012345678'));
    });

    test('既存の tag パラメータは上書きされる', () {
      AffiliateLinkConverter.amazonAssociateTag = 'mytag-22';
      final result = AffiliateLinkConverter.convert(
        'https://www.amazon.co.jp/dp/B012345678?tag=other-20',
      );
      expect(result.url, contains('tag=mytag-22'));
      expect(result.url, isNot(contains('other-20')));
    });

    test('amzn.to の短縮URLも変換する', () {
      AffiliateLinkConverter.amazonAssociateTag = 'mytag-22';
      final result = AffiliateLinkConverter.convert('https://amzn.to/3abcXYZ');
      expect(result.isAffiliate, isTrue);
      expect(result.url, contains('tag=mytag-22'));
    });

    test('楽天の商品URLをアフィリエイトリダイレクト形式に変換する', () {
      AffiliateLinkConverter.rakutenAffiliateId = '1234567.89012345';
      const original = 'https://item.rakuten.co.jp/shop/item001/';
      final result = AffiliateLinkConverter.convert(original);
      expect(result.isAffiliate, isTrue);
      expect(
        result.url,
        'https://hb.afl.rakuten.co.jp/hgc/1234567.89012345/'
        '?pc=${Uri.encodeComponent(original)}&m=${Uri.encodeComponent(original)}',
      );
    });

    test('すでにアフィリエイト経由の楽天リンクは二重変換しない', () {
      AffiliateLinkConverter.rakutenAffiliateId = '1234567.89012345';
      const already =
          'https://hb.afl.rakuten.co.jp/hgc/1234567.89012345/?pc=https%3A%2F%2Fitem.rakuten.co.jp%2Fshop%2Fitem001%2F';
      final result = AffiliateLinkConverter.convert(already);
      expect(result.isAffiliate, isFalse);
      expect(result.url, already);
    });

    test('関係ないURLはそのまま返す', () {
      AffiliateLinkConverter.amazonAssociateTag = 'mytag-22';
      AffiliateLinkConverter.rakutenAffiliateId = '1234567.89012345';
      final result = AffiliateLinkConverter.convert('https://example.com/');
      expect(result.isAffiliate, isFalse);
      expect(result.url, 'https://example.com/');
    });
  });
}
