package com.hoilai.mm.music

import android.content.Context
import android.view.View
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * 车机全屏歌词的原生 PlatformView 承载体。Dart 侧用 [android.views] 的 AndroidView
 * (viewType = [CarLyricPlatformViewFactory.VIEW_TYPE]) 嵌入，经同一 viewId 的
 * MethodChannel (`<viewType>/<id>`) 调用 setLyrics / setProgress / setStyles。
 */
class CarLyricPlatformView(
    context: Context,
    viewId: Int,
    viewType: String,
    messenger: BinaryMessenger,
    creationParams: Map<*, *>?
) : PlatformView, MethodChannel.MethodCallHandler {

    private val lyricView = CarLyricView(context)
    private val channel = MethodChannel(messenger, "$viewType/$viewId").also {
        it.setMethodCallHandler(this)
    }

    init {
        creationParams?.let { handleSetStyles(it) }
    }

    override fun getView(): View = lyricView

    override fun dispose() {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "setLyrics" -> {
                    @Suppress("UNCHECKED_CAST")
                    lyricView.setLyrics(call.arguments as? List<Any?> ?: emptyList<Any?>())
                    result.success(null)
                }
                "setStyles" -> handleSetStyles(call.arguments)
                "setProgress" -> {
                    val ms = (call.arguments as? Number)?.toLong() ?: 0L
                    lyricView.setProgress(ms)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("car_lyric_view", e.message, null)
        }
    }

    private fun handleSetStyles(args: Any?) {
        @Suppress("UNCHECKED_CAST")
        val map = args as? Map<*, *> ?: return
        lyricView.setStyles(map)
    }
}

class CarLyricPlatformViewFactory(
    private val messenger: BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    companion object {
        const val VIEW_TYPE = "ka.car_lyric_view"
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        @Suppress("UNCHECKED_CAST")
        val params = args as? Map<*, *>
        return CarLyricPlatformView(context, viewId, VIEW_TYPE, messenger, params)
    }
}
