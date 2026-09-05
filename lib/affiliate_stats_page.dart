import 'package:flutter/material.dart';

import 'affiliate_click_log.dart';
import 'affiliate_link.dart';

/// アフィリエイトリンクのクリック実績を確認するための画面。
/// アプリ運営者が収益化の状況を把握するためのシンプルなダッシュボード。
class AffiliateStatsPage extends StatefulWidget {
  const AffiliateStatsPage({super.key});

  @override
  State<AffiliateStatsPage> createState() => _AffiliateStatsPageState();
}

class _AffiliateStatsPageState extends State<AffiliateStatsPage> {
  late Future<_Stats> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<_Stats> _load() async {
    final total = await AffiliateClickLog.countAll();
    final amazon = await AffiliateClickLog.countByService(AffiliateService.amazon);
    final rakuten = await AffiliateClickLog.countByService(AffiliateService.rakuten);
    return _Stats(total: total, amazon: amazon, rakuten: rakuten);
  }

  void _reload() {
    setState(() {
      _future = _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('アフィリエイト実績'),
        actions: [
          IconButton(icon: Icon(Icons.refresh), onPressed: _reload),
        ],
      ),
      body: FutureBuilder<_Stats>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(child: Text('読み込みに失敗しました: ${snapshot.error}'));
          }
          final stats = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _StatCard(label: '合計クリック数', value: stats.total),
              const SizedBox(height: 12),
              _StatCard(label: 'Amazon', value: stats.amazon),
              const SizedBox(height: 12),
              _StatCard(label: '楽天', value: stats.rakuten),
            ],
          );
        },
      ),
    );
  }
}

class _Stats {
  const _Stats({required this.total, required this.amazon, required this.rakuten});

  final int total;
  final int amazon;
  final int rakuten;
}

class _StatCard extends StatelessWidget {
  const _StatCard({required this.label, required this.value});

  final String label;
  final int value;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text(label),
        trailing: Text(
          '$value',
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
        ),
      ),
    );
  }
}
