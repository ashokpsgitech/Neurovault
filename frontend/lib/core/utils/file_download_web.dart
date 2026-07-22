// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'dart:typed_data';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    final blob = html.Blob([bytes], 'application/octet-stream');
    final url = html.Url.createObjectUrlFromBlob(blob);

    final anchor = html.document.createElement('a') as html.AnchorElement;
    anchor.href = url;
    anchor.setAttribute('download', filename);
    anchor.style.display = 'none';

    html.document.body?.children.add(anchor);
    anchor.click();

    Future.delayed(const Duration(minutes: 5), () {
      try {
        anchor.remove();
        html.Url.revokeObjectUrl(url);
      } catch (_) {}
    });

    return 'Browser Downloads Folder ($filename)';
  } catch (e) {
    return null;
  }
}
