import "package:flutter/services.dart";
import "package:flutter_test/flutter_test.dart";
import "package:kgka_music_hl/controllers/player_controller.dart";
import "package:kgka_music_hl/core/api_client.dart";
import "package:kgka_music_hl/services/music_api.dart";
import "package:kgka_music_hl/services/music_audio_handler.dart";
import "package:shared_preferences/shared_preferences.dart";

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("com.ryanheise.just_audio.methods"),
      (call) async {
        if (call.method == "init") return {"id": "test_id"};
        if (call.method == "dispose") return {};
        return {};
      },
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("com.ryanheise.audio_service.client.methods"),
      (call) async => {},
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("com.ryanheise.audio_service.handler.methods"),
      (call) async => {},
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("kgka_music_hl/audio_effects"),
      (call) async => null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("kgka_music_hl/super_lyric"),
      (call) async => true,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("kgka_music_hl/bluetooth_lyrics"),
      (call) async => null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("kgka_music_hl/desktop_lyrics"),
      (call) async {
        if (call.method == "checkPermission") return true;
        return null;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("com.ryanheise.just_audio.methods"),
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("com.ryanheise.audio_service.client.methods"),
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel("kgka_music_hl/desktop_lyrics"),
      null,
    );
  });

  test("用户临时关闭桌面歌词悬浮窗不会清空持久化设置，点开软件切回前台自动恢复自启动", () async {
    SharedPreferences.setMockInitialValues({
      "settings.desktop_lyrics_enabled": true,
    });

    final api = MusicApi(ApiClient());
    final handler = MusicAudioHandler();
    final player = PlayerController(api, handler);

    await player.settingsRestored;
    expect(player.desktopLyricsEnabled, isTrue);
    expect(player.isDesktopLyricsUserDismissed, isFalse);

    // 模拟原生桌面歌词悬浮窗被用户点击关闭按钮（userClosed: true）
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      "kgka_music_hl/desktop_lyrics",
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall("onVisibilityChanged", {
          "visible": false,
          "userClosed": true,
        }),
      ),
      (data) {},
    );

    // 持久化开关依然开启，但标记为本次临时关闭
    expect(player.desktopLyricsEnabled, isTrue);
    expect(player.isDesktopLyricsUserDismissed, isTrue);

    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getBool("settings.desktop_lyrics_enabled"), isTrue);

    // 用户“点开软件”（应用切回前台）
    player.setAppForeground(true);

    // 临时关闭状态被自动重置，自启动恢复
    expect(player.isDesktopLyricsUserDismissed, isFalse);
    expect(player.desktopLyricsEnabled, isTrue);

    // 用户在设置中主动关闭
    await player.setDesktopLyricsEnabled(false);
    expect(player.desktopLyricsEnabled, isFalse);
    expect(prefs.getBool("settings.desktop_lyrics_enabled"), isFalse);

    // 用户在设置中再次开启
    await player.setDesktopLyricsEnabled(true);
    expect(player.desktopLyricsEnabled, isTrue);
    expect(player.isDesktopLyricsUserDismissed, isFalse);
    expect(prefs.getBool("settings.desktop_lyrics_enabled"), isTrue);
  });

  test("在设置预览桌面歌词时会重置临时关闭状态", () async {
    SharedPreferences.setMockInitialValues({
      "settings.desktop_lyrics_enabled": true,
    });

    final api = MusicApi(ApiClient());
    final handler = MusicAudioHandler();
    final player = PlayerController(api, handler);

    await player.settingsRestored;

    // 触发临时关闭
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      "kgka_music_hl/desktop_lyrics",
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall("onVisibilityChanged", {
          "visible": false,
          "userClosed": true,
        }),
      ),
      (data) {},
    );
    expect(player.isDesktopLyricsUserDismissed, isTrue);

    // 进入桌面歌词设置页开启预览
    await player.setDesktopLyricsPreviewVisible(true);
    expect(player.isDesktopLyricsUserDismissed, isFalse);
  });
}
