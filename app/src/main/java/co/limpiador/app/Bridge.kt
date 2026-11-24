package co.limpiador.app

import android.app.usage.StorageStatsManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Puente JavaScript ↔ Android (window.Limpiador). Todos los métodos devuelven texto JSON.
 * Los procesos largos (análisis) corren en un hilo aparte y avisan por window.__progreso / __analisisListo.
 */
class Bridge(private val act: MainActivity) {

    @JavascriptInterface
    fun estado(): String = JSONObject()
        .put("archivos", act.hasAllFiles())
        .put("uso", AppsInfo.hasUsageAccess(act))
        .put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("cacheGlobal", Build.VERSION.SDK_INT >= 30)
        .put("analizando", Scanner.running)
        .put("hayAnalisis", Scanner.lastScan > 0)
        .put("version", "1.0")
        .toString()

    @JavascriptInterface fun pedirArchivos() = act.requestAllFiles()
    @JavascriptInterface fun pedirUso() = act.requestUsageAccess()
    @JavascriptInterface fun abrirAjustesAlmacenamiento() = act.openStorageSettings()

    /** Espacio total y libre del almacenamiento interno. */
    @JavascriptInterface
    fun almacenamiento(): String {
        var total = 0L
        var libre = 0L
        try {
            val ssm = act.getSystemService(StorageStatsManager::class.java)
            total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
            libre = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
        } catch (_: Exception) {
            val st = StatFs(Environment.getDataDirectory().path)
            total = st.totalBytes
            libre = st.availableBytes
        }
        return JSONObject().put("total", total).put("libre", libre).toString()
    }

    // ---------- Análisis de archivos ----------

    @JavascriptInterface
    fun analizar(): Boolean {
        if (Scanner.running || !act.hasAllFiles()) return false
        Thread {
            try {
                Scanner.scan(act) { p -> act.pushJs("__progreso", p.toString()) }
                act.pushJs("__analisisListo", Scanner.summary().toString())
            } catch (e: Exception) {
                act.pushJs("__analisisError", JSONObject.quote(e.message ?: e.toString()))
            }
        }.start()
        return true
    }

    @JavascriptInterface fun cancelarAnalisis() { Scanner.cancelled = true }
    @JavascriptInterface fun resumen(): String = Scanner.summary().toString()
    @JavascriptInterface fun lista(cat: String): String = Scanner.list(cat).toString()
    @JavascriptInterface fun duplicados(): String = Scanner.duplicates().toString()
    @JavascriptInterface fun carpetasVacias(): String = Scanner.emptyDirsJson().toString()

    @JavascriptInterface
    fun abrirArchivo(id: Int) {
        val e = Scanner.get(id) ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        act.openFile(File(e.path), mime)
    }

    /**
     * Elimina los archivos indicados.
     * modo = "papelera": se mueven a la papelera (recuperables 7 días);
     * modo = "definitivo": se borran y el espacio queda libre de inmediato.
     */
    @JavascriptInterface
    fun eliminar(idsJson: String, modo: String): String {
        val ids = JSONArray(idsJson)
        var ok = 0; var fallos = 0; var bytes = 0L
        val gone = ArrayList<String>()
        for (i in 0 until ids.length()) {
            val id = ids.getInt(i)
            val e = Scanner.get(id) ?: continue
            val f = File(e.path)
            if (!f.exists()) { Scanner.removed.add(id); continue }
            val done = if (modo == "definitivo") f.delete() else Trash.moveIn(act, f)
            if (done) {
                ok++; bytes += e.size; gone.add(e.path); Scanner.removed.add(id)
            } else fallos++
        }
        Trash.rescan(act, gone)
        return JSONObject().put("ok", ok).put("fallos", fallos).put("bytes", bytes).put("modo", modo).toString()
    }

    @JavascriptInterface
    fun eliminarCarpetas(pathsJson: String): String {
        val arr = JSONArray(pathsJson)
        var ok = 0
        for (i in 0 until arr.length()) {
            val p = arr.getString(i)
            if (p !in Scanner.emptyDirs) continue          // solo carpetas que el análisis encontró vacías
            val d = File(p)
            val stillEmpty = d.isDirectory && (d.listFiles()?.isEmpty() == true)
            if (stillEmpty && d.delete()) { ok++; Scanner.removedDirs.add(p) }
        }
        return JSONObject().put("ok", ok).toString()
    }

    // ---------- Papelera ----------

    @JavascriptInterface fun papelera(): String = Trash.list(act).toString()

    @JavascriptInterface
    fun restaurar(idsJson: String): String {
        val arr = JSONArray(idsJson)
        val ids = (0 until arr.length()).map { arr.getString(it) }.toSet()
        return JSONObject().put("ok", Trash.restore(act, ids)).toString()
    }

    /** Vacía la papelera: todo (idsJson = "") o los ids indicados. */
    @JavascriptInterface
    fun vaciar(idsJson: String): String {
        val ids = if (idsJson.isBlank()) null else JSONArray(idsJson).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        return JSONObject().put("bytes", Trash.purge(act, ids)).toString()
    }

    // ---------- Apps ----------

    @JavascriptInterface fun apps(): String = AppsInfo.apps(act).toString()
    @JavascriptInterface fun seguridad(): String = AppsInfo.security(act).toString()
    @JavascriptInterface fun desinstalar(pkg: String) = act.uninstall(pkg)
    @JavascriptInterface fun infoApp(pkg: String) = act.openAppDetails(pkg)
    @JavascriptInterface fun limpiarCacheTodas(): Boolean = act.clearAllCaches()
}
