// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'dart:typed_data';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    final blob = html.Blob([bytes], 'application/octet-stream');
    final url = html.Url.createObjectUrlFromBlob(blob);

    final anchor = html.AnchorElement(href: url)
      ..setAttribute('download', filename)
      ..style.display = 'none';

    html.document.body?.children.add(anchor);
    anchor.click();

    // Keep URL alive for 30 seconds so Chrome finishes writing to disk
    Future.delayed(const Duration(seconds: 30), () {
      try {
        anchor.remove();
        html.Url.revokeObjectUrl(url);
      } catch (_) {}
    });

    return 'Downloaded to browser Downloads folder: $filename';
  } catch (e) {
    return null;
  }
}
