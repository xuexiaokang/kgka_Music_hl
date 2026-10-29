import 'dart:io';
import 'dart:typed_data';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kgka_music_hl/models/music_models.dart';
import 'package:kgka_music_hl/services/id3_tag_writer.dart';

void main() {
  test('Id3TagWriter injects valid ID3v2.3 tags readable by audio_metadata_reader', () async {
    final tempDir = await Directory.systemTemp.createTemp('id3_test');
    final tempFile = File('${tempDir.path}/test_song.mp3');

    // 创建模拟的 MP3 音频数据（包含 MP3 同步字 0xFF 0xFB）
    final dummyMp3Audio = Uint8List.fromList([
      0xFF, 0xFB, 0x90, 0x64, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ]);
    await tempFile.writeAsBytes(dummyMp3Audio);

    const song = Song(
      id: 'test_123',
      title: '晴天',
      artist: '周杰伦',
      albumName: '叶惠美',
      hash: 'abc123def456',
    );

    final ok = await Id3TagWriter.injectMetadata(
      filePath: tempFile.path,
      song: song,
      lyricsText: '[00:01.00]故事的小黄花',
    );

    expect(ok, isTrue);

    // 用 audio_metadata_reader 读回验证
    final metadata = readMetadata(tempFile, getImage: false);
    expect(metadata.title, equals('晴天'));
    expect(metadata.artist, equals('周杰伦'));
    expect(metadata.album, equals('叶惠美'));
    expect(metadata.lyrics, contains('故事的小黄花'));

    await tempDir.delete(recursive: true);
  });
}
