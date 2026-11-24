package co.limpiador.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Size
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toBitmap
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Contenedor nativo. La interfaz es una página web local (assets/www/index.html) y todo el trabajo
 * con archivos y apps lo hace el puente [Bridge] (window.Limpiador en JavaScript).
 *
 * Rutas locales servidas a la página:
 *   /assets/...        la interfaz
 *   /miniatura/<id>    miniatura de una foto o video encontrado
 *   /icono/<paquete>   ícono de una app instalada
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/www/index.html"
    }

    lateinit var web: WebView
        private set

    fun pushJs(fn: String, arg: String) {
        runOnUiThread {
            try { web.evaluateJavascript("window.$fn && window.$fn($arg)", null) } catch (_: Exception) { }
        }
    }

    // ---------- Resultados de pantallas del sistema ----------

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { pushJs("__appsCambiaron", "0") }

    private val clearCacheLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pushJs("__cacheResultado", res.resultCode.toString())
        }

    private val legacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pushJs("__volvio", "0") }

    fun uninstall(pkg: String) = runOnUiThread {
        try {
            uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (_: ActivityNotFoundException) { }
    }

    /** Diálogo del sistema para borrar la caché externa de todas las apps (Android 11+). */
    fun clearAllCaches(): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !hasAllFiles()) return false
        runOnUiThread {
            try {
                clearCacheLauncher.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
            } catch (_: Exception) {
                pushJs("__cacheResultado", "-99")
            }
        }
        return true
    }

    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun requestAllFiles() = runOnUiThread {
        if (Build.VERSION.SDK_INT >= 30) {
            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try { startActivity(specific) } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { }
            }
        } else {
            legacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    fun requestUsageAccess() = runOnUiThread {
        val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        try { startActivity(specific) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) { }
        }
    }

    fun openAppDetails(pkg: String) = runOnUiThread {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (_: Exception) { }
    }

    fun openStorageSettings() = runOnUiThread {
        try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (_: Exception) { }
    }

    /** Abre el archivo con la app que corresponda (para revisarlo antes de borrarlo). */
    fun openFile(f: File, mime: String) = runOnUiThread {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", f)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(view, "Abrir con"))
        } catch (_: Exception) {
            pushJs("__aviso", "'No hay una app para abrir este archivo'")
        }
    }

    // ---------- Ciclo de vida ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/miniatura/", ThumbHandler())
            .addPathHandler("/icono/", IconHandler())
            .build()

        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(this), "Limpiador")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST
        }

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("window.__atras ? window.__atras() : false") { r ->
                if (r != "true") moveTaskToBack(true)
            }
        }

        // Lo que lleve más de 7 días en la papelera se borra solo
        Thread { try { Trash.purge(this, null, Trash.KEEP_DAYS) } catch (_: Exception) { } }.start()

        web.loadUrl(START_URL)
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) pushJs("__volvio", "0")
    }

    override fun onDestroy() {
        Scanner.cancelled = true
        super.onDestroy()
    }

    // ---------- Miniaturas e íconos ----------

    private fun png(bmp: Bitmap, jpeg: Boolean): WebResourceResponse {
        val bos = ByteArrayOutputStream()
        bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 70, bos)
        return WebResourceResponse(if (jpeg) "image/jpeg" else "image/png", null, ByteArrayInputStream(bos.toByteArray()))
            .apply { responseHeaders = mapOf("Cache-Control" to "max-age=86400") }
    }

    private inner class ThumbHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val id = path.trim('/').toIntOrNull() ?: return null
            val e = Scanner.get(id) ?: return null
            val f = File(e.path)
            if (!f.exists()) return null
            val size = Size(192, 192)
            return try {
                val bmp = when (e.kind) {
                    "imagen" -> ThumbnailUtils.createImageThumbnail(f, size, null)
                    "video" -> ThumbnailUtils.createVideoThumbnail(f, size, null)
                    "audio" -> ThumbnailUtils.createAudioThumbnail(f, size, null)
                    else -> null
                } ?: return null
                png(bmp, true)
            } catch (_: Exception) { null }
        }
    }

    private inner class IconHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val pkg = path.trim('/')
            return try {
                val d = packageManager.getApplicationIcon(pkg)
                png(d.toBitmap(96, 96), false)
            } catch (_: Exception) { null }
        }
    }
}
