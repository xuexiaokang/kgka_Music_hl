import 'package:flutter_test/flutter_test.dart';

void main() {
  test('DeepLink URI parsing and structure validation', () {
    final playUri = Uri.parse('kgka://play?keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6');
    expect(playUri.scheme, equals('kgka'));
    expect(playUri.host, equals('play'));
    expect(playUri.queryParameters['keyword'], equals('周杰伦'));

    final controlUri = Uri.parse('kgka://control?action=next');
    expect(controlUri.scheme, equals('kgka'));
    expect(controlUri.host, equals('control'));
    expect(controlUri.queryParameters['action'], equals('next'));
  });
}
