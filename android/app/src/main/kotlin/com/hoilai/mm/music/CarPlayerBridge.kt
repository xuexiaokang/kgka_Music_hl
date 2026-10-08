package com.hoilai.mm.music

import android.content.Context
import android.content.Intent
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * 全原生车机播放器的桥接单例。
 *
 * 两条 MethodChannel：
 *  - `ka.car_player/native`  Dart→原生：open / sync / meta / lyrics / close（本类做 handler）
 *  - `ka.car_player/flutter` 原生→Dart：playPause / seek / next / prev / closed（Dart 做 handler）
 *
 * 进入车机全屏时 Dart 调 `open` 拉起 [CarPlayerActivity]；此后 FlutterActivity 被覆盖、
 * 引擎停帧，全屏完全由原生 HWUI 渲染（对齐酷我）。播放控制经 `flutter` 通道回传给 Dart 的
 * PlayerController，音频链路不变。Dart 在状态变化/每秒把权威进度 `sync` 回来纠偏。
 */
object CarPlayerBridge {

    private var appContext: Context? = null
    private var toNative: MethodChannel? = null
    private var fromNative: MethodChannel? = null

    @Volatile
    var current: CarPlayerActivity? = null

    /** open 时暂存的初始快照，供 Activity onCreate 读取。 */
    var snapshot: Map<*, *>? = null

    fun init(context: Context, messenger: BinaryMessenger) {
        appContext = context.applicationContext
        toNative = MethodChannel(messenger, "ka.car_player/native").apply {
            setMethodCallHandler { call, result -> handle(call, result) }
        }
        fromNative = MethodChannel(messenger, "ka.car_player/flutter")
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "open" -> {
                    snapshot = call.arguments as? Map<*, *>
                    val ctx = appContext
                    if (ctx == null) {
                        result.error("no_context", "appContext null", null)
                        return
                    }
                    val intent = Intent(ctx, CarPlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    ctx.startActivity(intent)
                    result.success(true)
                }
                "sync" -> current?.onSync(call.arguments as? Map<*, *>).also { result.success(null) }
                "meta" -> current?.onMeta(call.arguments as? Map<*, *>).also { result.success(null) }
                "lyrics" -> current?.onLyrics(call.arguments as? Map<*, *>).also { result.success(null) }
                "close" -> {
                    current?.finishExternally()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("car_player", e.message, null)
        }
    }

    /** 原生→Dart 事件（主线程调用）。 */
    fun sendEvent(name: String, args: Any? = null) {
        fromNative?.invokeMethod(name, args)
    }
}
