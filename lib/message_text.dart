import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import 'affiliate_link.dart';

/// チャットメッセージ本文を表示するウィジェット。
///
/// 本文中に Amazon / 楽天の商品 URL が含まれていた場合、設定済みの
/// アソシエイトタグ／アフィリエイトIDを使って自動的にアフィリエイトリンクへ
/// 変換し、タップで開けるリンクとして表示する。ステルスマーケティング対策として
/// 変換したリンクの直前に「[PR]」表示を付ける。
class MessageText extends StatefulWidget {
  const MessageText({super.key, required this.text, this.style});

  final String text;
  final TextStyle? style;

  @override
  State<MessageText> createState() => _MessageTextState();
}

class _MessageTextState extends State<MessageText> {
  final List<TapGestureRecognizer> _recognizers = [];

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  void _disposeRecognizers() {
    for (final recognizer in _recognizers) {
      recognizer.dispose();
    }
    _recognizers.clear();
  }

  Future<void> _open(String url) async {
    final uri = Uri.tryParse(url);
    if (uri == null) return;
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    // build のたびに古い recognizer を破棄してから作り直す。
    _disposeRecognizers();

    final baseStyle = widget.style ?? DefaultTextStyle.of(context).style;
    final spans = <InlineSpan>[];
    var lastIndex = 0;

    for (final match in AffiliateLinkConverter.urlPattern.allMatches(widget.text)) {
      if (match.start > lastIndex) {
        spans.add(TextSpan(text: widget.text.substring(lastIndex, match.start)));
      }

      final rawUrl = match.group(0)!;
      final result = AffiliateLinkConverter.convert(rawUrl);

      if (result.isAffiliate) {
        spans.add(
          const TextSpan(
            text: '[PR] ',
            style: TextStyle(
              color: Colors.deepOrange,
              fontWeight: FontWeight.bold,
              fontSize: 12,
            ),
          ),
        );
      }

      final recognizer = TapGestureRecognizer()..onTap = () => _open(result.url);
      _recognizers.add(recognizer);

      spans.add(
        TextSpan(
          text: rawUrl,
          style: const TextStyle(
            color: Colors.blue,
            decoration: TextDecoration.underline,
          ),
          recognizer: recognizer,
        ),
      );

      lastIndex = match.end;
    }

    if (lastIndex < widget.text.length) {
      spans.add(TextSpan(text: widget.text.substring(lastIndex)));
    }
    if (spans.isEmpty) {
      spans.add(TextSpan(text: widget.text));
    }

    return Text.rich(TextSpan(style: baseStyle, children: spans));
  }
}
