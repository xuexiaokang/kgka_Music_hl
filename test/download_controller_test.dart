import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:kgka_music_hl/controllers/download_controller.dart';
import 'package:kgka_music_hl/core/api_client.dart';
import 'package:kgka_music_hl/models/music_models.dart';
import 'package:kgka_music_hl/services/download_service.dart';
import 'package:kgka_music_hl/services/music_api.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _FakeDownloadService extends DownloadService {
  _FakeDownloadService(this.fileSizes);

  final Map<String, int> fileSizes;

  @override
  Future<int> fileSize(String path) async => fileSizes[path] ?? 0;
}

class _BlockingDownloadService extends DownloadService {
  @override
  Future<int> fileSize(String path) => Completer<int>().future;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'downloaded file wins regardless of current quality and hash casing',
    () async {
      const downloadedSong = Song(
        id: '1',
        title: '已下载歌曲',
        artist: '歌手',
        hash: 'ABC123',
      );
      const playlistSong = Song(
        id: '1',
        title: '已下载歌曲',
        artist: '歌手',
        hash: 'abc123',
      );
      const path = '/downloads/song.flac';
      SharedPreferences.setMockInitialValues({
        'ka_music_downloads_index': jsonEncode([
          {
            'song': downloadedSong.toCache(),
            'quality': AudioQuality.lossless.apiValue,
            'filePath': path,
            'downloadedAt': '2026-10-26T12:00:00.000',
          },
        ]),
      });

      final controller = DownloadController(
        _FakeDownloadService({path: 1024}),
        MusicApi(ApiClient()),
      );

      expect(
        await controller.localPathFor(playlistSong, AudioQuality.standard),
        path,
      );
      expect(controller.isDownloaded(playlistSong), isTrue);
    },
  );

  test(
    'stalled local file lookup falls back instead of blocking playback',
    () async {
      const song = Song(
        id: '2',
        title: '旧下载歌曲',
        artist: '歌手',
        hash: 'stale-path',
      );
      SharedPreferences.setMockInitialValues({
        'ka_music_downloads_index': jsonEncode([
          {
            'song': song.toCache(),
            'quality': AudioQuality.standard.apiValue,
            'filePath': '/downloads/stalled.mp3',
            'downloadedAt': '2026-10-26T12:00:00.000',
          },
        ]),
      });

      final controller = DownloadController(
        _BlockingDownloadService(),
        MusicApi(ApiClient()),
      );

      final stopwatch = Stopwatch()..start();
      expect(
        await controller.localPathFor(song, AudioQuality.standard),
        isNull,
      );
      stopwatch.stop();
      expect(stopwatch.elapsed, lessThan(const Duration(seconds: 2)));
      expect(controller.isDownloaded(song), isTrue);
    },
  );
}
