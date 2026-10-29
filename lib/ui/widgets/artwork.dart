import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'retryable_network_image.dart';

class Artwork extends StatelessWidget {
  const Artwork({
    super.key,
    this.url,
    required this.size,
    this.borderRadius = 8,
    this.icon = Icons.music_note_rounded,
  });

  final String? url;
  final double size;
  final double borderRadius;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final imageUrl = url;
    final child = imageUrl == null
        ? _Fallback(icon: icon)
        : imageUrl.startsWith('content://')
            ? _ContentUriImage(
                uri: imageUrl,
                size: size,
                borderRadius: borderRadius,
                icon: icon,
              )
            : RetryableNetworkImage(
                url: imageUrl,
                // 固定尺寸的封面按 2 倍图取；铺满容器的大封面取 1024，
                // 宽高交给外层 SizedBox 约束，不在这里写死 infinity。
                width: size.isFinite ? size : null,
                height: size.isFinite ? size : null,
                cacheWidth: size.isFinite ? (size * 2).round() : 1024,
                cacheHeight: size.isFinite ? (size * 2).round() : 1024,
                placeholderBuilder: (context) => _ShimmerBox(
                  size: size,
                  borderRadius: borderRadius,
                ),
              );

    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: size.isFinite
          ? SizedBox.square(dimension: size, child: child)
          : SizedBox.expand(child: child),
    );
  }
}

/// 加载 content:// URI 的图片（用于本地音乐专辑封面）。
class _ContentUriImage extends StatefulWidget {
  const _ContentUriImage({
    required this.uri,
    required this.size,
    required this.borderRadius,
    required this.icon,
  });

  final String uri;
  final double size;
  final double borderRadius;
  final IconData icon;

  @override
  State<_ContentUriImage> createState() => _ContentUriImageState();
}

class _ContentUriImageState extends State<_ContentUriImage> {
  static const _channel = MethodChannel('kgka_music_hl/local_music');
  Uint8List? _bytes;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadImage();
  }

  @override
  void didUpdateWidget(covariant _ContentUriImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 列表刷新/重排时同一位置会复用 State，uri 变化需要重新加载封面，
    // 否则会显示上一次的旧封面（封面错位）。
    if (oldWidget.uri != widget.uri) {
      _bytes = null;
      _loading = true;
      _loadImage();
    }
  }

  Future<void> _loadImage() async {
    // 记录发起加载时的 uri，用于丢弃过期结果，避免快速刷新时的竞态。
    final uri = widget.uri;
    try {
      // 从 content URI 中提取 albumId
      final albumId = int.tryParse(uri.split('/').last);
      if (albumId == null || albumId <= 0) {
        if (mounted) setState(() => _loading = false);
        return;
      }
      final bytes = await _channel.invokeMethod<Uint8List>(
        'getAlbumArt',
        {'albumId': albumId},
      );
      if (!mounted || widget.uri != uri) return;
      setState(() {
        _bytes = bytes;
        _loading = false;
      });
    } catch (e) {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return _ShimmerBox(size: widget.size, borderRadius: widget.borderRadius);
    }
    if (_bytes == null) {
      return _Fallback(icon: widget.icon);
    }
    final capW = widget.size.isFinite
        ? (widget.size * MediaQuery.devicePixelRatioOf(context))
            .clamp(1, 2048)
            .round()
        : null;
    return Image.memory(_bytes!, fit: BoxFit.cover, cacheWidth: capW);
  }
}

class _Fallback extends StatelessWidget {
  const _Fallback({required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            colorScheme.primary.withValues(alpha: .88),
            const Color(0xFF70D6FF),
            colorScheme.secondary.withValues(alpha: .72),
          ],
        ),
      ),
      child: Icon(icon, color: Colors.white, size: 28),
    );
  }
}

/// 图片加载时的占位效果（静态轻量渲染，避免多 Ticker 导致高刷滑动掉帧）。
class _ShimmerBox extends StatelessWidget {
  const _ShimmerBox({required this.size, required this.borderRadius});

  final double size;
  final double borderRadius;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final baseColor = isDark
        ? colorScheme.surfaceContainerHighest.withValues(alpha: 0.6)
        : colorScheme.surfaceContainer;

    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: baseColor,
          borderRadius: BorderRadius.circular(borderRadius),
        ),
        child: SizedBox(
          width: size.isFinite ? size : null,
          height: size.isFinite ? size : null,
        ),
      ),
    );
  }
}
