package com.github.wrager.sbgscout.bridge

import android.util.Base64
import android.webkit.JavascriptInterface

/**
 * Принимает содержимое blob-файла из WebView в виде base64 и сохраняет его
 * через переданный колбэк. Используется для скачивания файлов, которые
 * юзерскрипты создают через `URL.createObjectURL(blob)` + `<a download>` —
 * стандартный `DownloadListener` blob: URL не обрабатывает, поэтому содержимое
 * вычитывается из JS через `FileReader.readAsDataURL` и передаётся сюда.
 *
 * @param save колбэк сохранения (имя, MIME, байты) → сообщение для Toast
 *             (например, имя файла или путь). Null из колбэка = ошибка.
 * @param onResult вызывается после сохранения с сообщением для Toast (null при ошибке)
 */
class DownloadBridge(
    private val save: (filename: String, mimeType: String, bytes: ByteArray) -> String?,
    private val onResult: (savedName: String?) -> Unit,
) {

    @JavascriptInterface
    fun saveBlob(base64: String, filename: String, mimeType: String) {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
            onResult(null)
            return
        }
        val savedName = save(filename, mimeType, bytes)
        onResult(savedName)
    }

    companion object {
        const val JS_INTERFACE_NAME = "__sbg_download"

        /**
         * Перехватчик blob-скачиваний для WebView. Юзерскрипты (например SVP)
         * используют паттерн:
         * ```
         * const url = URL.createObjectURL(blob);
         * link.href = url; link.download = name; link.click();
         * URL.revokeObjectURL(url);
         * ```
         * Стандартный `DownloadListener` получает событие асинхронно — к этому
         * моменту blob уже revoke'нут и `fetch(url)` падает с "Failed to fetch".
         *
         * Решение: оборачиваем `URL.createObjectURL`, чтобы кешировать blob по URL,
         * и перехватываем `HTMLAnchorElement.click` — когда anchor имеет blob href
         * и атрибут `download`, читаем blob из кеша синхронно и передаём в мост.
         * Нативный click() НЕ вызывается (он бы открыл blob в новом табе).
         */
        val BOOTSTRAP_SCRIPT = """
            (function() {
              if (window.__sbg_download_installed) return;
              window.__sbg_download_installed = true;
              var blobs = new Map();
              var origCreate = URL.createObjectURL.bind(URL);
              var origRevoke = URL.revokeObjectURL.bind(URL);
              URL.createObjectURL = function(obj) {
                var url = origCreate(obj);
                if (obj instanceof Blob) blobs.set(url, obj);
                return url;
              };
              URL.revokeObjectURL = function(url) {
                // Откладываем удаление из кеша, чтобы click() после revoke ещё мог прочитать blob.
                setTimeout(function(){ blobs.delete(url); }, 30000);
                return origRevoke(url);
              };
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function() {
                var href = this.href;
                var download = this.getAttribute('download');
                if (download != null && href && href.indexOf('blob:') === 0) {
                  var blob = blobs.get(href);
                  if (blob) {
                    var mime = blob.type || 'application/octet-stream';
                    var reader = new FileReader();
                    reader.onload = function() {
                      var dataUrl = reader.result;
                      var base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                      window.__sbg_download.saveBlob(base64, download, mime);
                    };
                    reader.readAsDataURL(blob);
                    return;
                  }
                }
                return origClick.call(this);
              };
            })();
        """.trimIndent()
    }
}
