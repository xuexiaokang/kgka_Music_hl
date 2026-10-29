import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../controllers/player_controller.dart';
import '../services/music_api.dart';

/// 处理 AI / 外部唤起的 Deep Link（URL Scheme: kgka://）。
///
/// 支持协议：
/// - kgka://play?keyword=周杰伦 (自动搜索并播放第一首)
/// - kgka://play?song=七里香
/// - kgka://control?action=next | prev | toggle | play | pause (控制播控)
/// - kgka://search?keyword=周杰伦 (打开搜索)
class DeepLinkService {
  static const _channel = MethodChannel('kgka_music_hl/deep_link');
  static bool _initialized = false;

  /// 初始化监听原生发送的 Deep Link。
  static void initialize({
    required PlayerController player,
    required MusicApi api,
  }) {
    if (_initialized) return;
    _initialized = true;

    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onDeepLink') {
        final link = call.arguments as String?;
        if (link != null) {
          await handleLink(link, player: player, api: api);
        }
      }
    });

    // 检查冷启动传入的初始链接
    unawaited(
      _channel.invokeMethod<String>('getInitialLink').then((link) {
        if (link != null && link.isNotEmpty) {
          handleLink(link, player: player, api: api);
        }
      }).catchError((_) {}),
    );
  }

  /// 解析并执行 Deep Link 指令。
  static Future<void> handleLink(
    String link, {
    required PlayerController player,
    required MusicApi api,
  }) async {
    try {
      final uri = Uri.tryParse(link);
      if (uri == null || uri.scheme != 'kgka') return;

      final actionHost = uri.host.toLowerCase();
      final params = uri.queryParameters;

      if (actionHost == 'play') {
        final query = params['keyword'] ?? params['song'] ?? params['q'] ?? params['title'];
        if (query != null && query.trim().isNotEmpty) {
          final songs = await api.searchSongs(query.trim(), pageSize: 10);
          if (songs.isNotEmpty) {
            await player.playSong(songs.first, queue: songs);
          }
        }
      } else if (actionHost == 'control') {
        final cmd = (params['action'] ?? params['cmd'])?.toLowerCase();
        switch (cmd) {
          case 'next':
            await player.next();
            break;
          case 'prev':
          case 'previous':
            await player.previous();
            break;
          case 'play':
            await player.play();
            break;
          case 'pause':
            await player.pause();
            break;
          case 'toggle':
            await player.togglePlay();
            break;
        }
      }
    } catch (e) {
      debugPrint('[DeepLinkService] handleLink error: $e');
    }
  }
}
