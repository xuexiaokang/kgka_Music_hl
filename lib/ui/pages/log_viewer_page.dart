import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../services/log_service.dart';
import '../adaptive_layout.dart';
import '../widgets/liquid_glass_ui.dart';
import '../widgets/toast.dart';

class LogViewerPage extends StatefulWidget {
  const LogViewerPage({super.key});

  @override
  State<LogViewerPage> createState() => _LogViewerPageState();
}

class _LogViewerPageState extends State<LogViewerPage> {
  final ScrollController _scrollController = ScrollController();
  final TextEditingController _searchController = TextEditingController();

  StreamSubscription<LogEntry>? _logSub;
  List<LogEntry> _allEntries = [];
  String _keyword = '';
  String _selectedCategory = 'all';
  bool _autoScroll = true;
  bool _isExporting = false;

  @override
  void initState() {
    super.initState();
    _loadInitialEntries();
    _logSub = LogService.instance.logStream.listen((entry) {
      if (!mounted) return;
      setState(() {
        _allEntries.add(entry);
      });
      if (_autoScroll && _scrollController.hasClients) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (_scrollController.hasClients) {
            _scrollController.jumpTo(
              _scrollController.position.maxScrollExtent,
            );
          }
        });
      }
    });
  }

  @override
  void dispose() {
    _logSub?.cancel();
    _scrollController.dispose();
    _searchController.dispose();
    super.dispose();
  }

  void _loadInitialEntries() {
    _allEntries = List.of(LogService.instance.recentEntries);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
      }
    });
  }

  List<LogEntry> get _filteredEntries {
    return _allEntries.where((entry) {
      // 类别过滤
      if (_selectedCategory == 'error' && entry.level != LogLevel.error) {
        return false;
      }
      if (_selectedCategory == 'playback') {
        final lower = entry.message.toLowerCase();
        if (!lower.contains('playback') &&
            !lower.contains('player') &&
            !lower.contains('source') &&
            !lower.contains('audio')) {
          return false;
        }
      }
      if (_selectedCategory == 'download') {
        final lower = entry.message.toLowerCase();
        if (!lower.contains('download') && !lower.contains('cache')) {
          return false;
        }
      }
      if (_selectedCategory == 'api') {
        final lower = entry.message.toLowerCase();
        if (!lower.contains('api') &&
            !lower.contains('http') &&
            !lower.contains('request') &&
            !lower.contains('search')) {
          return false;
        }
      }

      // 关键词搜索
      if (_keyword.isNotEmpty) {
        final matchMsg =
            entry.message.toLowerCase().contains(_keyword.toLowerCase());
        final matchTag =
            entry.tag?.toLowerCase().contains(_keyword.toLowerCase()) ?? false;
        if (!matchMsg && !matchTag) return false;
      }
      return true;
    }).toList();
  }

  Future<void> _handleExportToFile() async {
    if (_isExporting) return;

    final hasFilter = _keyword.isNotEmpty || _selectedCategory != 'all';
    if (hasFilter) {
      final choice = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('选择导出范围'),
          content: Text(
            '当前已设置筛选（共 ${_filteredEntries.length} 条记录）。\n请选择导出完整全量日志还是仅导出当前筛选结果：',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop('filtered'),
              child: const Text('仅当前筛选'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(ctx).pop('full'),
              child: const Text('导出全量日志 (推荐)'),
            ),
          ],
        ),
      );
      if (choice == null) return;
      if (choice == 'filtered') {
        final content = _filteredEntries.map((e) => e.format()).join('\n');
        await _performExport(customContent: content, isFull: false);
        return;
      }
    }

    await _performExport(isFull: true);
  }

  Future<void> _performExport({String? customContent, required bool isFull}) async {
    setState(() => _isExporting = true);
    try {
      final savedPath = await LogService.instance.exportLogsToFile(
        fullLogs: isFull,
        customContent: customContent,
      );
      if (!mounted) return;
      if (savedPath != null) {
        final stats = await LogService.instance.getLogStats();
        if (!mounted) return;
        _showExportSuccessDialog(savedPath, isFull: isFull, stats: stats);
      } else {
        Toast.error('导出日志文件失败');
      }
    } catch (e) {
      if (mounted) Toast.error('导出失败：$e');
    } finally {
      if (mounted) setState(() => _isExporting = false);
    }
  }

  void _showExportSuccessDialog(
    String path, {
    required bool isFull,
    LogStats? stats,
  }) {
    showDialog<void>(
      context: context,
      builder: (dialogCtx) => AlertDialog(
        title: Row(
          children: [
            const Icon(Icons.check_circle_rounded, color: Colors.green),
            const SizedBox(width: 8),
            Text(isFull ? '全量日志导出成功' : '日志导出成功'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isFull
                  ? '已将本地完整全量日志（UTF-8 纯文本格式 .txt）导出：'
                  : '已将筛选日志（UTF-8 纯文本格式 .txt）导出：',
              style: const TextStyle(fontSize: 13),
            ),
            if (isFull && stats != null) ...[
              const SizedBox(height: 6),
              Text(
                '总日志文件：${stats.fileCount} 个  |  总体积：${stats.formattedSize}',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: SelectableText(
                path,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                ),
              ),
            ),
          ],
        ),
        actions: [
          TextButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: path));
              Toast.success('路径已复制到剪贴板');
            },
            icon: const Icon(Icons.copy_rounded, size: 16),
            label: const Text('复制路径'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogCtx).pop(),
            child: const Text('完成'),
          ),
        ],
      ),
    );
  }

  Future<void> _handleCopyAll() async {
    if (_keyword.isNotEmpty || _selectedCategory != 'all') {
      final text = _filteredEntries.map((e) => e.format()).join('\n');
      if (text.isEmpty) {
        Toast.info('当前筛选结果为空');
        return;
      }
      await Clipboard.setData(ClipboardData(text: text));
      if (mounted) Toast.success('已复制当前筛选的 ${_filteredEntries.length} 条日志');
      return;
    }

    final success = await LogService.instance.copyLogsToClipboard();
    if (!mounted) return;
    if (success) {
      Toast.success('全量诊断日志已复制到剪贴板');
    } else {
      Toast.error('复制失败');
    }
  }

  Future<void> _handleClearLogs() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空本地日志'),
        content: const Text('确定要清空本地所有已存储的日志记录吗？清空后将无法找回。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            child: const Text('清空'),
          ),
        ],
      ),
    );

    if (confirm == true) {
      await LogService.instance.clearLogs();
      if (!mounted) return;
      setState(() {
        _allEntries.clear();
      });
      Toast.success('日志已清空');
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final entries = _filteredEntries;

    return LiquidGlassBackground(
      child: Scaffold(
        backgroundColor: Colors.transparent,
        appBar: AppBar(
          title: const Text('运行日志与诊断'),
          backgroundColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
          actions: [
            IconButton(
              tooltip: _autoScroll ? '已开启自动滚动到底部' : '自动滚动已暂停',
              icon: Icon(
                _autoScroll
                    ? Icons.vertical_align_bottom_rounded
                    : Icons.pause_circle_outline_rounded,
                color: _autoScroll ? colorScheme.primary : null,
              ),
              onPressed: () => setState(() => _autoScroll = !_autoScroll),
            ),
            IconButton(
              tooltip: '清空日志',
              icon: const Icon(Icons.delete_sweep_outlined),
              onPressed: _handleClearLogs,
            ),
          ],
        ),
        body: AdaptiveContentPadding(
          child: Column(
            children: [
              // 搜索与过滤栏
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Column(
                  children: [
                    TextField(
                      controller: _searchController,
                      decoration: InputDecoration(
                        hintText: '搜索日志关键词...',
                        prefixIcon: const Icon(Icons.search_rounded, size: 20),
                        suffixIcon: _keyword.isNotEmpty
                            ? IconButton(
                                icon: const Icon(Icons.clear_rounded, size: 18),
                                onPressed: () {
                                  _searchController.clear();
                                  setState(() => _keyword = '');
                                },
                              )
                            : null,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 8,
                        ),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide.none,
                        ),
                        filled: true,
                        fillColor: colorScheme.surfaceContainerHighest.withValues(alpha: 0.6),
                      ),
                      onChanged: (val) => setState(() => _keyword = val.trim()),
                    ),
                    const SizedBox(height: 8),
                    SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        children: [
                          _buildFilterChip('all', '全部 (${_allEntries.length})'),
                          const SizedBox(width: 8),
                          _buildFilterChip('error', '仅错误'),
                          const SizedBox(width: 8),
                          _buildFilterChip('playback', '播放相关'),
                          const SizedBox(width: 8),
                          _buildFilterChip('download', '下载/缓存'),
                          const SizedBox(width: 8),
                          _buildFilterChip('api', '网络/API'),
                        ],
                      ),
                    ),
                  ],
                ),
              ),

              // 日志展示区域
              Expanded(
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                  decoration: BoxDecoration(
                    color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.4),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(
                      color: colorScheme.outlineVariant.withValues(alpha: 0.2),
                    ),
                  ),
                  child: entries.isEmpty
                      ? const Center(
                          child: Text(
                            '暂无匹配的日志记录',
                            style: TextStyle(fontSize: 14, color: Colors.grey),
                          ),
                        )
                      : SelectionArea(
                          child: ListView.builder(
                            controller: _scrollController,
                            padding: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 10,
                            ),
                            itemCount: entries.length,
                            itemBuilder: (context, index) {
                              final item = entries[index];
                              return _buildLogItem(item, colorScheme);
                            },
                          ),
                        ),
                ),
              ),

              // 底部工具栏
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    Text(
                      '已显示 ${entries.length} 条',
                      style: TextStyle(
                        fontSize: 12,
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const Spacer(),
                    OutlinedButton.icon(
                      onPressed: _handleCopyAll,
                      icon: const Icon(Icons.copy_rounded, size: 16),
                      label: const Text('复制日志'),
                    ),
                    const SizedBox(width: 10),
                    FilledButton.icon(
                      onPressed: _isExporting ? null : _handleExportToFile,
                      icon: _isExporting
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.file_download_outlined, size: 16),
                      label: const Text('导出全量日志'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFilterChip(String category, String label) {
    final selected = _selectedCategory == category;
    final colorScheme = Theme.of(context).colorScheme;
    return ChoiceChip(
      label: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
        ),
      ),
      selected: selected,
      onSelected: (_) => setState(() => _selectedCategory = category),
      selectedColor: colorScheme.primaryContainer,
      showCheckmark: false,
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
    );
  }

  Widget _buildLogItem(LogEntry item, ColorScheme colorScheme) {
    Color textColor;
    switch (item.level) {
      case LogLevel.error:
        textColor = colorScheme.error;
        break;
      case LogLevel.warning:
        textColor = Colors.amber.shade700;
        break;
      case LogLevel.debug:
        textColor = colorScheme.outline;
        break;
      case LogLevel.info:
        textColor = colorScheme.onSurface;
        break;
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Text(
        item.format(),
        style: TextStyle(
          fontFamily: 'monospace',
          fontSize: 11,
          height: 1.4,
          color: textColor,
        ),
      ),
    );
  }
}
