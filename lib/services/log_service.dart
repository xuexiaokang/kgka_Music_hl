import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import '../config/app_config.dart';

enum LogLevel {
  debug,
  info,
  warning,
  error,
}

class LogStats {
  const LogStats({
    required this.fileCount,
    required this.totalBytes,
    required this.entryCount,
    this.earliestDate,
    this.latestDate,
  });

  final int fileCount;
  final int totalBytes;
  final int entryCount;
  final DateTime? earliestDate;
  final DateTime? latestDate;

  String get formattedSize {
    if (totalBytes < 1024) return '$totalBytes B';
    if (totalBytes < 1024 * 1024) {
      return '${(totalBytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(totalBytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }
}

class LogEntry {
  const LogEntry({
    required this.timestamp,
    required this.level,
    required this.message,
    this.tag,
  });

  final DateTime timestamp;
  final LogLevel level;
  final String message;
  final String? tag;

  String format() {
    final timeStr = _formatTimestamp(timestamp);
    final levelStr = level.name.toUpperCase().padRight(5);
    final tagStr = tag != null ? '[$tag] ' : '';
    return '[$timeStr] [$levelStr] $tagStr$message';
  }

  static String _formatTimestamp(DateTime dt) {
    final y = dt.year.toString().padLeft(4, '0');
    final m = dt.month.toString().padLeft(2, '0');
    final d = dt.day.toString().padLeft(2, '0');
    final h = dt.hour.toString().padLeft(2, '0');
    final min = dt.minute.toString().padLeft(2, '0');
    final s = dt.second.toString().padLeft(2, '0');
    final ms = dt.millisecond.toString().padLeft(3, '0');
    return '$y-$m-$d $h:$min:$s.$ms';
  }
}

/// 全局运行日志与诊断服务。
///
/// 自动截获应用内的 [debugPrint]、未捕获异常及框架错误，
/// 本地滚动持久化（按天分割，自动保留最近 7 天），
/// 并提供内存环形缓存、实时流、一键复制及文件导出功能。
class LogService {
  LogService._();

  static final LogService instance = LogService._();

  static const int maxMemoryEntries = 1000;
  static const int maxFileDays = 7;
  static const int maxSingleFileSize = 5 * 1024 * 1024; // 5MB

  final List<LogEntry> _recentEntries = [];
  final List<String> _pendingWriteBuffer = [];
  final StreamController<LogEntry> _streamController =
      StreamController<LogEntry>.broadcast();

  Directory? _logDir;
  File? _currentFile;
  Timer? _flushTimer;
  bool _initialized = false;
  bool _isFlushing = false;

  Stream<LogEntry> get logStream => _streamController.stream;

  List<LogEntry> get recentEntries => List.unmodifiable(_recentEntries);

  Directory? get logDirectory => _logDir;

  /// 初始化日志系统，配置存储目录并接管全局输出与错误监听。
  Future<void> init({Directory? logDirectoryOverride}) async {
    if (logDirectoryOverride != null) {
      _logDir = logDirectoryOverride;
      _currentFile = null;
    }
    if (_initialized && logDirectoryOverride == null) return;
    _initialized = true;

    try {
      if (logDirectoryOverride != null) {
        _logDir = logDirectoryOverride;
      } else {
        Directory baseDir;
        try {
          baseDir = await getApplicationSupportDirectory();
        } catch (_) {
          baseDir = await getApplicationDocumentsDirectory();
        }
        _logDir = Directory('${baseDir.path}${Platform.pathSeparator}logs');
      }

      if (!_logDir!.existsSync()) {
        _logDir!.createSync(recursive: true);
      }

      await _setupCurrentFile();
      _cleanOldLogFiles();
    } catch (e) {
      // 容灾：文件系统初始化失败时降级为仅内存模式
      _logDir = null;
    }

    _hookGlobalPrintsAndErrors();
    record('=== KA Music Log Session Started (v${AppConfig.appVersion}) ===');
  }

  void _hookGlobalPrintsAndErrors() {
    final originalDebugPrint = debugPrint;
    debugPrint = (String? message, {int? wrapWidth}) {
      originalDebugPrint(message, wrapWidth: wrapWidth);
      if (message != null && message.isNotEmpty) {
        _handleCapturedMessage(message);
      }
    };

    final originalFlutterError = FlutterError.onError;
    FlutterError.onError = (details) {
      originalFlutterError?.call(details);
      final stack = details.stack != null ? '\n${details.stack}' : '';
      record(
        '[FlutterError] ${details.exceptionAsString()}$stack',
        level: LogLevel.error,
      );
    };

    PlatformDispatcher.instance.onError = (error, stack) {
      record(
        '[UncaughtError] $error\n$stack',
        level: LogLevel.error,
      );
      return false;
    };
  }

  void _handleCapturedMessage(String raw) {
    LogLevel level = LogLevel.info;
    final lower = raw.toLowerCase();
    if (lower.contains('failed') ||
        lower.contains('error') ||
        lower.contains('exception')) {
      level = LogLevel.error;
    } else if (lower.contains('warning') || lower.contains('timeout')) {
      level = LogLevel.warning;
    }
    record(raw, level: level);
  }

  /// 记录一条日志。
  void record(
    String message, {
    LogLevel level = LogLevel.info,
    String? tag,
  }) {
    final entry = LogEntry(
      timestamp: DateTime.now(),
      level: level,
      message: message,
      tag: tag,
    );

    // 内存环形缓存
    _recentEntries.add(entry);
    if (_recentEntries.length > maxMemoryEntries) {
      _recentEntries.removeRange(
        0,
        _recentEntries.length - maxMemoryEntries,
      );
    }

    if (!_streamController.isClosed) {
      _streamController.add(entry);
    }

    // 磁盘缓冲
    if (_logDir != null) {
      _pendingWriteBuffer.add(entry.format());
      _scheduleFlush();
    }
  }

  void _scheduleFlush() {
    if (_flushTimer?.isActive == true) return;
    _flushTimer = Timer(const Duration(milliseconds: 1000), () {
      unawaited(flush());
    });
  }

  /// 立即将缓冲区中的日志写入磁盘文件。
  Future<void> flush() async {
    if (_isFlushing || _pendingWriteBuffer.isEmpty || _logDir == null) return;
    _isFlushing = true;
    _flushTimer?.cancel();
    _flushTimer = null;

    final linesToWrite = List<String>.of(_pendingWriteBuffer);
    _pendingWriteBuffer.clear();

    try {
      await _setupCurrentFile();
      if (_currentFile != null) {
        final sink = _currentFile!.openWrite(mode: FileMode.append);
        for (final line in linesToWrite) {
          sink.writeln(line);
        }
        await sink.flush();
        await sink.close();
      }
    } catch (_) {
      // 写入失败不抛出，保证应用运行稳定
    } finally {
      _isFlushing = false;
    }
  }

  Future<void> _setupCurrentFile() async {
    if (_logDir == null) return;
    final now = DateTime.now();
    final dateStr =
        '${now.year}${now.month.toString().padLeft(2, '0')}${now.day.toString().padLeft(2, '0')}';
    final targetName = 'app_$dateStr.log';

    if (_currentFile == null || !_currentFile!.path.endsWith(targetName)) {
      _currentFile = File('${_logDir!.path}${Platform.pathSeparator}$targetName');
      if (!_currentFile!.existsSync()) {
        _currentFile!.createSync(recursive: true);
        final header = _buildSystemInfoHeader();
        _currentFile!.writeAsStringSync('$header\n');
      }
    }

    // 检查文件体积，超过 5MB 时滚动
    try {
      if (_currentFile!.existsSync() &&
          _currentFile!.lengthSync() > maxSingleFileSize) {
        final rollPath =
            '${_logDir!.path}${Platform.pathSeparator}app_${dateStr}_${DateTime.now().millisecondsSinceEpoch}.log';
        _currentFile!.renameSync(rollPath);
        _currentFile = File('${_logDir!.path}${Platform.pathSeparator}$targetName');
        _currentFile!.createSync(recursive: true);
      }
    } catch (_) {}
  }

  void _cleanOldLogFiles() {
    if (_logDir == null || !_logDir!.existsSync()) return;
    try {
      final now = DateTime.now();
      final threshold = now.subtract(const Duration(days: maxFileDays));
      final files = _logDir!.listSync().whereType<File>();
      for (final file in files) {
        final stat = file.statSync();
        if (stat.modified.isBefore(threshold)) {
          file.deleteSync();
        }
      }
    } catch (_) {}
  }

  String _buildSystemInfoHeader({bool isFull = true}) {
    final buffer = StringBuffer();
    buffer.writeln('==================================================');
    buffer.writeln('KA Music Diagnostics Log${isFull ? ' (Full Logs)' : ''}');
    buffer.writeln('App Version : ${AppConfig.appVersion}');
    buffer.writeln('Base URL    : ${AppConfig.effectiveBaseUrl}');
    buffer.writeln('OS Platform : ${Platform.operatingSystem} ${Platform.operatingSystemVersion}');
    buffer.writeln('Created At  : ${DateTime.now().toIso8601String()}');
    buffer.writeln('==================================================');
    return buffer.toString();
  }

  /// 获取本地日志统计信息（文件数量、总占用大小等）。
  Future<LogStats> getLogStats() async {
    await flush();
    int fileCount = 0;
    int totalBytes = 0;
    DateTime? earliest;
    DateTime? latest;

    if (_logDir != null && _logDir!.existsSync()) {
      try {
        final files = _logDir!
            .listSync()
            .whereType<File>()
            .where((f) => f.path.endsWith('.log'))
            .toList();
        fileCount = files.length;
        for (final file in files) {
          final stat = file.statSync();
          totalBytes += stat.size;
          if (earliest == null || stat.modified.isBefore(earliest)) {
            earliest = stat.modified;
          }
          if (latest == null || stat.modified.isAfter(latest)) {
            latest = stat.modified;
          }
        }
      } catch (_) {}
    }

    return LogStats(
      fileCount: fileCount,
      totalBytes: totalBytes,
      entryCount: _recentEntries.length,
      earliestDate: earliest,
      latestDate: latest,
    );
  }

  /// 生成适合排查问题的完整日志字符串。
  ///
  /// 当 [maxLines] 为 null 时导出全量日志（包含本地存储的所有历史日志文件全部内容，不设截断）；
  /// 当指定 [maxLines] 时，截取最近的 [maxLines] 行。
  Future<String> exportLogsText({int? maxLines}) async {
    await flush();
    final buffer = StringBuffer();
    buffer.writeln(_buildSystemInfoHeader(isFull: maxLines == null));
    buffer.writeln();

    if (_logDir != null && _logDir!.existsSync()) {
      try {
        final files = _logDir!
            .listSync()
            .whereType<File>()
            .where((f) => f.path.endsWith('.log'))
            .toList()
          ..sort((a, b) => a.path.compareTo(b.path));

        final collectedLines = <String>[];
        for (final file in files) {
          try {
            final lines = await file.readAsLines();
            collectedLines.addAll(lines);
          } catch (_) {}
        }

        if (maxLines != null && collectedLines.length > maxLines) {
          buffer.writeln('... [Earlier logs truncated for export length: showing last $maxLines lines] ...');
          for (final line in collectedLines.sublist(collectedLines.length - maxLines)) {
            buffer.writeln(line);
          }
        } else {
          for (final line in collectedLines) {
            buffer.writeln(line);
          }
        }
        return buffer.toString();
      } catch (_) {}
    }

    // 若本地文件读取失败，回退到内存缓存
    for (final entry in _recentEntries) {
      buffer.writeln(entry.format());
    }
    return buffer.toString();
  }

  /// 复制日志文本到系统剪贴板。
  ///
  /// [maxLines] 为 null 时复制全量日志。
  Future<bool> copyLogsToClipboard({int? maxLines = 2000}) async {
    try {
      final text = await exportLogsText(maxLines: maxLines);
      await Clipboard.setData(ClipboardData(text: text));
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 导出日志到用户可见的文件中，返回导出的文件完整路径。
  ///
  /// [fullLogs] 为 true 时导出全量日志（包含全部历史持久化文件，不设行数截断上限）；
  /// [customContent] 允许直接导出自定义日志文本（例如当前搜索/过滤视图的结果）。
  /// Android 平台优先导出到系统公共 Download 目录（Download/KA Music/logs），
  /// 桌面端与其它平台优先导出到系统下载文件夹或应用文档目录。
  Future<String?> exportLogsToFile({
    String? customDestinationDir,
    bool fullLogs = true,
    String? customContent,
  }) async {
    final text = customContent ?? await exportLogsText(maxLines: fullLogs ? null : 2000);
    final now = DateTime.now();
    final prefix = customContent != null
        ? 'ka_music_filtered_log'
        : (fullLogs ? 'ka_music_full_log' : 'ka_music_log');
    final fileName =
        '${prefix}_${now.year}${now.month.toString().padLeft(2, '0')}${now.day.toString().padLeft(2, '0')}_${now.hour.toString().padLeft(2, '0')}${now.minute.toString().padLeft(2, '0')}${now.second.toString().padLeft(2, '0')}.txt';

    Directory targetDir;
    if (customDestinationDir != null && customDestinationDir.trim().isNotEmpty) {
      targetDir = Directory(customDestinationDir.trim());
    } else {
      targetDir = await _resolvePublicExportDirectory();
    }

    if (!targetDir.existsSync()) {
      await targetDir.create(recursive: true);
    }

    final exportFile = File('${targetDir.path}${Platform.pathSeparator}$fileName');
    await exportFile.writeAsString(text, flush: true);
    return exportFile.path;
  }

  Future<Directory> _resolvePublicExportDirectory() async {
    if (Platform.isAndroid) {
      try {
        final publicDownload = Directory('/storage/emulated/0/Download/KA Music/logs');
        if (publicDownload.existsSync() || (await publicDownload.create(recursive: true).then((_) => true).catchError((_) => false))) {
          return publicDownload;
        }
      } catch (_) {}
    }

    try {
      final downloadsDir = await getDownloadsDirectory();
      if (downloadsDir != null && downloadsDir.existsSync()) {
        final subDir = Directory('${downloadsDir.path}${Platform.pathSeparator}KA Music Logs');
        if (!subDir.existsSync()) {
          await subDir.create(recursive: true);
        }
        return subDir;
      }
    } catch (_) {}

    try {
      final docsDir = await getApplicationDocumentsDirectory();
      final subDir = Directory('${docsDir.path}${Platform.pathSeparator}KA Music Logs');
      if (!subDir.existsSync()) {
        await subDir.create(recursive: true);
      }
      return subDir;
    } catch (_) {
      return _logDir ?? Directory.systemTemp;
    }
  }

  /// 清空本地所有存储的日志和内存缓存。
  Future<void> clearLogs() async {
    _flushTimer?.cancel();
    _flushTimer = null;
    _pendingWriteBuffer.clear();
    _recentEntries.clear();

    if (_logDir != null && _logDir!.existsSync()) {
      try {
        final files = _logDir!.listSync().whereType<File>();
        for (final file in files) {
          try {
            file.deleteSync();
          } catch (_) {}
        }
      } catch (_) {}
    }

    _currentFile = null;
    await _setupCurrentFile();
    record('=== Log cleared and new session started ===');
  }
}
