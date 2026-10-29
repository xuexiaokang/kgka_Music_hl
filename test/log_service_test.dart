import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:kgka_music_hl/services/log_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('ka_log_test_');
    await LogService.instance.init(logDirectoryOverride: tempDir);
  });

  tearDown(() async {
    try {
      if (tempDir.existsSync()) {
        tempDir.deleteSync(recursive: true);
      }
    } catch (_) {}
  });

  test('LogEntry formats correctly with timestamp, level, tag, and message', () {
    final entry = LogEntry(
      timestamp: DateTime(2026, 10, 26, 12, 34, 56, 789),
      level: LogLevel.info,
      message: 'Test message',
      tag: 'Player',
    );

    final formatted = entry.format();
    expect(formatted, contains('[2026-10-26 12:34:56.789]'));
    expect(formatted, contains('[INFO ]'));
    expect(formatted, contains('[Player]'));
    expect(formatted, contains('Test message'));
  });

  test('LogService records entries into memory and notifies stream', () async {
    final entriesFromStream = <LogEntry>[];
    final sub = LogService.instance.logStream.listen(entriesFromStream.add);

    LogService.instance.record('Hello log test', level: LogLevel.info, tag: 'Test');
    LogService.instance.record('An error occurred', level: LogLevel.error);

    await Future<void>.delayed(const Duration(milliseconds: 50));
    await sub.cancel();

    expect(LogService.instance.recentEntries.any((e) => e.message == 'Hello log test'), isTrue);
    expect(LogService.instance.recentEntries.any((e) => e.message == 'An error occurred'), isTrue);
    expect(entriesFromStream.any((e) => e.message == 'Hello log test'), isTrue);
  });

  test('LogService exports log text with system info header and full content', () async {
    LogService.instance.record('Log before export', level: LogLevel.info);
    final text = await LogService.instance.exportLogsText();

    expect(text, contains('KA Music Diagnostics Log (Full Logs)'));
    expect(text, contains('Log before export'));
  });

  test('LogService returns valid LogStats', () async {
    LogService.instance.record('Stats testing entry');
    await LogService.instance.flush();
    final stats = await LogService.instance.getLogStats();

    expect(stats.fileCount, greaterThanOrEqualTo(1));
    expect(stats.formattedSize, isNotEmpty);
    expect(stats.entryCount, greaterThanOrEqualTo(1));
  });

  test('LogService exports full log to file in specified directory', () async {
    final exportDir = await Directory.systemTemp.createTemp('ka_log_export_test_');
    try {
      LogService.instance.record('File export log entry', level: LogLevel.warning);
      final filePath = await LogService.instance.exportLogsToFile(
        customDestinationDir: exportDir.path,
        fullLogs: true,
      );

      expect(filePath, isNotNull);
      expect(filePath, contains('ka_music_full_log'));
      final file = File(filePath!);
      expect(file.existsSync(), isTrue);

      final content = await file.readAsString();
      expect(content, contains('File export log entry'));
      expect(content, contains('KA Music Diagnostics Log (Full Logs)'));
    } finally {
      if (exportDir.existsSync()) {
        exportDir.deleteSync(recursive: true);
      }
    }
  });

  test('LogService exports custom filtered content to file', () async {
    final exportDir = await Directory.systemTemp.createTemp('ka_log_export_filter_');
    try {
      final filePath = await LogService.instance.exportLogsToFile(
        customDestinationDir: exportDir.path,
        customContent: '[2026-10-26 12:00:00.000] [ERROR] Only filtered error',
      );

      expect(filePath, isNotNull);
      expect(filePath, contains('ka_music_filtered_log'));
      final file = File(filePath!);
      expect(file.existsSync(), isTrue);

      final content = await file.readAsString();
      expect(content, equals('[2026-10-26 12:00:00.000] [ERROR] Only filtered error'));
    } finally {
      if (exportDir.existsSync()) {
        exportDir.deleteSync(recursive: true);
      }
    }
  });

  test('LogService clearLogs empties memory and files', () async {
    LogService.instance.record('To be cleared');
    expect(LogService.instance.recentEntries.isNotEmpty, isTrue);

    await LogService.instance.clearLogs();
    // After clear, only the reset session message exists in recentEntries
    expect(LogService.instance.recentEntries.length, equals(1));
    expect(LogService.instance.recentEntries.first.message, contains('Log cleared and new session started'));
  });
}
