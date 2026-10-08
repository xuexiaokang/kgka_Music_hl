import 'package:flutter/material.dart';
import 'package:flutter_lyric/widgets/mixins/lyric_layout_mixin.dart';

// 动画时长：例如 50 毫秒
const Duration _kHighlightTransitionDuration = Duration(milliseconds: 200);

// 必须混入 TickerProviderStateMixin 才能使用 AnimationController
mixin LyricLineHightlightMixin<T extends StatefulWidget>
    on State<T>, LyricLayoutMixin<T>, TickerProviderStateMixin<T> {
  late final AnimationController _animationController;
  Animation<double>? _widthAnimation;
  CurvedAnimation? _curvedAnimation;

  final ValueNotifier<double> activeHighlightWidthNotifier = ValueNotifier(0.0);

  @override
  void initState() {
    _animationController = AnimationController(
      vsync: this,
      duration: _kHighlightTransitionDuration,
    )
      ..addListener(_onWidthAnimationTick)
      ..addStatusListener(_onWidthAnimationStatus);

    controller.activeIndexNotifiter.addListener(_onActiveIndexChange);
    controller.progressNotifier.addListener(updateHighlightWidth);

    super.initState();
  }

  void _onWidthAnimationTick() {
    if (!mounted || _widthAnimation == null) {
      return;
    }
    activeHighlightWidthNotifier.value = _widthAnimation!.value;
  }

  void _onWidthAnimationStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed) {
      _disposeWidthAnimation();
    }
  }

  void _disposeWidthAnimation() {
    _widthAnimation = null;
    _curvedAnimation?.dispose();
    _curvedAnimation = null;
  }

  void _onActiveIndexChange() {
    if (!mounted) {
      return;
    }
    updateHighlightWidth();
  }

  @override
  void dispose() {
    controller.activeIndexNotifiter.removeListener(_onActiveIndexChange);
    controller.progressNotifier.removeListener(updateHighlightWidth);
    _animationController
      ..removeStatusListener(_onWidthAnimationStatus)
      ..stop();
    _disposeWidthAnimation();
    _animationController.dispose();
    activeHighlightWidthNotifier.dispose();
    super.dispose();
  }

  void updateHighlightWidth() {
    if (!mounted) {
      return;
    }
    final index = controller.activeIndexNotifiter.value;
    final metrics = layout?.metrics ?? [];

    if (index >= metrics.length || index < 0) {
      _animateWidth(0.0);
      return;
    }

    final line = metrics[index];
    var newWidth = 0.0;
    final currentProgress = controller.progressNotifier.value +
        Duration(milliseconds: controller.lyricOffset);

    line.words?.forEach((wordMetric) {
      if (currentProgress >= wordMetric.word.start) {
        newWidth += wordMetric.highlightWidth;
        final endTime = (wordMetric.word.end ?? Duration.zero);
        if (currentProgress < endTime) {
          final wordDuration = (endTime - wordMetric.word.start).inMilliseconds;
          final elapsed =
              (currentProgress - wordMetric.word.start).inMilliseconds;

          if (wordDuration > 0) {
            newWidth -=
                wordMetric.highlightWidth * (1 - elapsed / wordDuration);
          }
        }
      }
    });
    final words = line.words;
    if (words != null && words.isNotEmpty) {
      final lastWord = words.last.word;
      final lastEnd = lastWord.end ?? Duration.zero;
      if (lastEnd > lastWord.start && currentProgress >= lastEnd) {
        newWidth += style.activeHighlightExtraFadeWidth;
      }
    }
    _animateWidth(newWidth);
  }

  // [KA perf patch] 去掉库内置的 200ms AnimationController 补间。
  // 原版逻辑：只要 newWidth > currentWidth 就 forward 起一个 200ms 补间，
  // _onWidthAnimationTick 会以 ~60fps 刷新 activeHighlightWidthNotifier，
  // LyricPainter.shouldRepaint 于是每帧重新栅格整块歌词区。在弱车机 GPU 上，
  // 这会让栅格线程/GPU 持续满载，饿死并发的浮窗视频（表现为浮窗掉帧卡顿）。
  // 现改为：直接把 notifier 吸附到本帧目标宽度。于是重栅格频率完全由 app 侧
  // 的 setProgress 节流决定（见 player_page.dart _kLyricTickIntervalMs），且字与字
  // 之间的空隙处 newWidth == currentWidth → 不通知 → 不重绘 → GPU 得以 idle，
  // 与原生酷我“非每帧重绘”的渲染纪律对齐。卡拉OK 逐字填充依旧保留。
  void _animateWidth(double newWidth) {
    if (!mounted) {
      return;
    }
    final currentWidth = activeHighlightWidthNotifier.value;

    // 宽度没变化（同一字持续、或器乐间隙）→ 完全不通知，零重绘。
    if (currentWidth == newWidth) return;

    // 若上一帧的补间（历史遗留路径）仍在跑，停掉，确保不再有 60fps vsync。
    if (_animationController.isAnimating) {
      _animationController.stop();
    }
    _disposeWidthAnimation();

    // 直接吸附，不再 forward(from:0) 起 200ms 补间。
    activeHighlightWidthNotifier.value = newWidth;
  }

  Widget buildActiveHighlightWidth(Widget Function(double value) builder) {
    return ValueListenableBuilder<double>(
      valueListenable: activeHighlightWidthNotifier,
      builder: (context, value, child) {
        return builder(value);
      },
    );
  }
}
