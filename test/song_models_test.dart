import 'package:flutter_test/flutter_test.dart';
import 'package:kgka_music_hl/models/music_models.dart';

void main() {
  group('Song.fromDaily', () {
    test('正确解析 album_audio_id 优先于 mixsongid/songid', () {
      final song = Song.fromDaily({
        'songname': '测试推荐歌曲',
        'author_name': '测试歌手',
        'hash': 'HASH_DAILY_1',
        'album_id': '12345',
        'album_audio_id': '88888',
        'mixsongid': 77777,
        'songid': 66666,
        'time_length': 240,
      });

      expect(song.title, '测试推荐歌曲');
      expect(song.artist, '测试歌手');
      expect(song.albumAudioId, '88888');
      expect(song.duration, const Duration(seconds: 240));
    });

    test('支持浮点数与字符串形态的 time_length 避免解析为 null', () {
      final song = Song.fromDaily({
        'songname': '浮点时长歌曲',
        'author_name': '测试歌手',
        'hash': 'HASH_DAILY_2',
        'album_id': '12345',
        'mixsongid': 77777,
        'time_length': 259.16,
      });

      expect(song.duration?.inSeconds, 259);

      final songStr = Song.fromDaily({
        'songname': '字符串时长歌曲',
        'author_name': '测试歌手',
        'hash': 'HASH_DAILY_3',
        'time_length': '180.5',
      });

      expect(songStr.duration?.inMilliseconds, 180500);
    });

    test('缺失 album_audio_id 时平滑回退 mixsongid 与 songid', () {
      final song = Song.fromDaily({
        'songname': '回退歌曲',
        'author_name': '测试歌手',
        'hash': 'HASH_DAILY_4',
        'mixsongid': 99999,
      });

      expect(song.albumAudioId, '99999');
    });
  });

  group('Song.fromTopCard', () {
    test('正确解析 album_audio_id 并支持浮点秒数时长', () {
      final song = Song.fromTopCard({
        'songname': '卡片推荐歌曲',
        'author_name': '卡片艺人',
        'hash': 'HASH_CARD_1',
        'album_id': '22222',
        'album_audio_id': '33333',
        'mixsongid': '44444',
        'time_length': 210.8,
      });

      expect(song.title, '卡片推荐歌曲');
      expect(song.artist, '卡片艺人');
      expect(song.albumAudioId, '33333');
      expect(song.duration?.inMilliseconds, 210800);
    });
  });
}
