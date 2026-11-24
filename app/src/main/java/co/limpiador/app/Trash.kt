package co.limpiador.app

import android.content.Context
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Papelera propia: los archivos se mueven a una carpeta oculta (.LimpiadorPapelera) en la raíz del
 * mismo almacenamiento, así el movimiento es instantáneo y se pueden restaurar.
 * El espacio se libera al vaciar la papelera (o solo, a los [KEEP_DAYS] días).
 */
object Trash {

    const val KEEP_DAYS = 7L

    private fun indexFile(ctx: Context) = File(ctx.filesDir, "papelera.json")

    private fun load(ctx: Context): JSONArray = try {
        val f = indexFile(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) { JSONArray() }

    private fun save(ctx: Context, arr: JSONArray) {
        indexFile(ctx).writeText(arr.toString())
    }

    private fun dirFor(ctx: Context, path: String): File? {
        val root = Scanner.rootOf(ctx, path) ?: return null
        val d = File(root, Scanner.TRASH_DIR)
        if (!d.exists()) d.mkdirs()
        val nomedia = File(d, ".nomedia")
        if (!nomedia.exists()) try { nomedia.createNewFile() } catch (_: Exception) { }
        return if (d.isDirectory) d else null
    }

    /** Actualiza la Galería / reproductor de música (quita o agrega los archivos de su índice). */
    fun rescan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try { MediaScannerConnection.scanFile(ctx.applicationContext, paths.toTypedArray(), null, null) } catch (_: Exception) { }
    }

    /** Mueve a la papelera. Devuelve true si el archivo quedó en ella. */
    @Synchronized
    fun moveIn(ctx: Context, f: File): Boolean {
        val dir = dirFor(ctx, f.absolutePath) ?: return false
        val id = UUID.randomUUID().toString()
        val dest = File(dir, id + "_" + f.name)
        val size = f.length()
        if (!f.renameTo(dest)) return false
        val arr = load(ctx)
        arr.put(JSONObject().put("id", id).put("original", f.absolutePath).put("guardado", dest.absolutePath)
            .put("tam", size).put("fecha", System.currentTimeMillis()))
        save(ctx, arr)
        return true
    }

    @Synchronized
    fun list(ctx: Context): JSONObject {
        val arr = load(ctx)
        val alive = JSONArray()
        var total = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!File(o.getString("guardado")).exists()) continue
            total += o.optLong("tam")
            alive.put(o)
        }
        if (alive.length() != arr.length()) save(ctx, alive)
        return JSONObject().put("items", alive).put("bytes", total).put("dias", KEEP_DAYS)
    }

    /** Restaura los ids indicados a su carpeta original. Devuelve cuántos se restauraron. */
    @Synchronized
    fun restore(ctx: Context, ids: Set<String>): Int {
        val arr = load(ctx)
        val keep = JSONArray()
        val restored = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") !in ids) { keep.put(o); continue }
            val src = File(o.getString("guardado"))
            var dest = File(o.getString("original"))
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest = File(dest.parentFile, "restaurado_" + dest.name)
            if (src.renameTo(dest)) restored.add(dest.absolutePath) else keep.put(o)
        }
        save(ctx, keep)
        rescan(ctx, restored)
        return restored.size
    }

    /**
     * Borra definitivamente: los ids indicados, todo (ids = null) o los que lleven más de [olderThanDays].
     * Devuelve los bytes liberados.
     */
    @Synchronized
    fun purge(ctx: Context, ids: Set<String>?, olderThanDays: Long? = null): Long {
        val arr = load(ctx)
        val keep = JSONArray()
        val limit = olderThanDays?.let { System.currentTimeMillis() - it * 24L * 3600_000L }
        var freed = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hit = when {
                limit != null -> o.optLong("fecha") < limit
                ids == null -> true
                else -> o.getString("id") in ids
            }
            if (!hit) { keep.put(o); continue }
            val f = File(o.getString("guardado"))
            if (!f.exists() || f.delete()) freed += o.optLong("tam") else keep.put(o)
        }
        save(ctx, keep)
        return freed
    }
}
