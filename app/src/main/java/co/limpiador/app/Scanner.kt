package co.limpiador.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Un archivo encontrado en el almacenamiento compartido. */
class Entry(
    val id: Int,
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val kind: String,
    val tags: Set<String>
)

/**
 * Recorre el almacenamiento (interno y tarjeta SD) y clasifica los archivos.
 * No borra nada: solo arma las listas que el usuario revisa antes de eliminar.
 */
object Scanner {

    const val TRASH_DIR = ".LimpiadorPapelera"
    private const val MB = 1024L * 1024L
    const val BIG_BYTES = 100L * MB
    const val OLD_DAYS = 365L
    private const val DUP_MIN_BYTES = 50L * 1024L

    @Volatile var entries: List<Entry> = emptyList(); private set
    @Volatile var emptyDirs: List<String> = emptyList(); private set
    /** Grupos de duplicados: el primer id de cada grupo es el que se sugiere conservar. */
    @Volatile var dupGroups: List<List<Int>> = emptyList(); private set
    @Volatile var running = false; private set
    @Volatile var cancelled = false
    @Volatile var lastScan = 0L; private set

    /** Ids ya eliminados o enviados a la papelera (se ocultan de las listas). */
    val removed: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
    val removedDirs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val byId = ConcurrentHashMap<Int, Entry>()

    fun get(id: Int): Entry? = byId[id]

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "3gp", "3g2", "webm", "m4v", "ts", "flv", "wmv", "mpg", "mpeg", "rmvb", "vob")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "wma", "mid", "midi", "3ga", "awb", "m4b")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "avif")
    private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "txt", "odt", "ods", "odp", "csv", "rtf", "epub", "xml", "json")
    private val APK = setOf("apk", "apks", "xapk", "apkm")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    private val TEMP_EXT = setOf("tmp", "temp", "log", "chck", "crdownload", "part", "partial")

    /** Carpetas de primer nivel que nunca se proponen como "vacías" aunque lo estén. */
    private val STANDARD_DIRS = setOf(
        "DCIM", "Download", "Downloads", "Pictures", "Music", "Movies", "Documents", "Android",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Recordings", "Audiobooks"
    )

    /** Raíces de almacenamiento: interno (/storage/emulated/0) y tarjetas SD, si hay. */
    fun roots(ctx: Context): List<File> =
        ctx.getExternalFilesDirs(null).filterNotNull()
            .map { File(it.absolutePath.substringBefore("/Android/")) }
            .distinctBy { it.absolutePath }
            .filter { it.canRead() }

    fun rootOf(ctx: Context, path: String): File? =
        roots(ctx).firstOrNull { path == it.absolutePath || path.startsWith(it.absolutePath + "/") }

    private fun kindOf(ext: String, rel: String): String {
        val lowerRel = rel.lowercase()
        if (lowerRel.contains("/.thumbnails/") || lowerRel.startsWith("lost.dir/") || ext in TEMP_EXT) return "temporal"
        return when (ext) {
            in VIDEO -> "video"
            in AUDIO -> "audio"
            in IMAGE -> "imagen"
            in DOC -> "documento"
            in APK -> "apk"
            in ARCHIVE -> "comprimido"
            else -> "otro"
        }
    }

    private fun tagsOf(rel: String, size: Long, mtime: Long, now: Long): Set<String> {
        val t = HashSet<String>()
        val r = "/" + rel
        if (r.startsWith("/Download/") || r.startsWith("/Downloads/")) t.add("descargas")
        if (r.contains("/WhatsApp/Media/") || r.contains("/WhatsApp Business/Media/")) {
            t.add("whatsapp")
            if (r.contains("/Sent/")) t.add("enviados")
        }
        if (r.contains("/Telegram/")) t.add("telegram")
        if (r.contains("/Screenshots/") || r.contains("/Capturas de pantalla/")) t.add("capturas")
        if (size >= BIG_BYTES) t.add("grande")
        if (mtime > 0 && now - mtime > OLD_DAYS * 24L * 3600_000L) t.add("antiguo")
        return t
    }

    /**
     * Análisis completo. [progress] recibe JSON con el avance; se llama desde este mismo hilo.
     */
    fun scan(ctx: Context, progress: (JSONObject) -> Unit) {
        if (running) return
        running = true
        cancelled = false
        try {
            val now = System.currentTimeMillis()
            val found = ArrayList<Entry>()
            val empties = ArrayList<String>()
            var nextId = 1
            var bytes = 0L
            var lastPush = 0L

            for (root in roots(ctx)) {
                val rootPath = root.absolutePath
                val stack = ArrayDeque<File>()
                stack.push(root)
                while (stack.isNotEmpty()) {
                    if (cancelled) break
                    val dir = stack.pop()
                    val rel = if (dir.absolutePath == rootPath) "" else dir.absolutePath.substring(rootPath.length + 1)
                    // Datos privados de otras apps y nuestra propia papelera: nunca se tocan
                    if (rel == "Android/data" || rel == "Android/obb" || rel == TRASH_DIR) continue
                    val children = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
                    if (children.isEmpty()) {
                        if (rel.isNotEmpty() && !(rel.indexOf('/') < 0 && rel in STANDARD_DIRS)) empties.add(dir.absolutePath)
                        continue
                    }
                    for (f in children) {
                        val isLink = try { Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { true }
                        if (isLink) continue
                        if (f.isDirectory) { stack.push(f); continue }
                        if (!f.isFile) continue
                        val size = f.length()
                        val mtime = f.lastModified()
                        val fileRel = f.absolutePath.substring(rootPath.length + 1)
                        val ext = f.name.substringAfterLast('.', "").lowercase()
                        found.add(Entry(nextId++, f.absolutePath, f.name, size, mtime, kindOf(ext, fileRel), tagsOf(fileRel, size, mtime, now)))
                        bytes += size
                        val t = System.currentTimeMillis()
                        if (t - lastPush > 250) {
                            lastPush = t
                            progress(JSONObject().put("fase", "archivos").put("archivos", found.size)
                                .put("bytes", bytes).put("carpeta", rel))
                        }
                    }
                }
            }

            byId.clear()
            for (e in found) byId[e.id] = e
            removed.clear()
            removedDirs.clear()
            entries = found
            emptyDirs = empties
            dupGroups = emptyList()

            if (!cancelled) dupGroups = findDuplicates(found, progress)
            lastScan = System.currentTimeMillis()
        } finally {
            running = false
        }
    }

    /** Duplicados exactos: mismo tamaño y mismo SHA-256 del contenido completo. */
    private fun findDuplicates(all: List<Entry>, progress: (JSONObject) -> Unit): List<List<Int>> {
        val bySize = all.asSequence()
            .filter { it.size >= DUP_MIN_BYTES && it.kind != "temporal" }
            .groupBy { it.size }
            .values.filter { it.size > 1 }
        val candidates = bySize.sumOf { it.size }
        var done = 0
        var lastPush = 0L
        val groups = ArrayList<List<Entry>>()
        for (sameSize in bySize) {
            if (cancelled) break
            val byHash = HashMap<String, MutableList<Entry>>()
            for (e in sameSize) {
                if (cancelled) break
                val h = sha256(File(e.path)) ?: continue
                byHash.getOrPut(h) { ArrayList() }.add(e)
                done++
                val t = System.currentTimeMillis()
                if (t - lastPush > 250) {
                    lastPush = t
                    progress(JSONObject().put("fase", "duplicados").put("hechos", done).put("total", candidates))
                }
            }
            groups.addAll(byHash.values.filter { it.size > 1 })
        }
        // Se sugiere conservar la copia más antigua que no sea un "enviado" de WhatsApp
        return groups
            .map { g -> g.sortedWith(compareBy<Entry>({ "enviados" in it.tags }, { it.mtime }, { it.path.length })).map { it.id } }
            .sortedByDescending { g -> (byId[g[0]]?.size ?: 0L) * (g.size - 1) }
    }

    private fun sha256(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    // ---------- Consultas para la interfaz ----------

    private fun alive(): List<Entry> = entries.filter { it.id !in removed }

    private fun matches(e: Entry, cat: String): Boolean = when (cat) {
        "video", "audio", "imagen", "documento", "apk", "comprimido", "temporal", "otro" -> e.kind == cat
        else -> cat in e.tags
    }

    fun toJson(e: Entry): JSONObject {
        val dir = e.path.substringBeforeLast('/')
        return JSONObject()
            .put("id", e.id).put("nombre", e.name).put("carpeta", dir)
            .put("tam", e.size).put("fecha", e.mtime).put("tipo", e.kind)
            .put("etiquetas", JSONArray(e.tags.toList()))
    }

    fun summary(): JSONObject {
        val cats = listOf(
            "video", "audio", "imagen", "documento", "apk", "comprimido", "temporal", "otro",
            "descargas", "whatsapp", "enviados", "telegram", "capturas", "grande", "antiguo"
        )
        val out = JSONObject()
        val live = alive()
        for (c in cats) {
            var n = 0; var b = 0L
            for (e in live) if (matches(e, c)) { n++; b += e.size }
            out.put(c, JSONObject().put("n", n).put("bytes", b))
        }
        val groups = liveDupGroups()
        var wasted = 0L; var copies = 0
        for (g in groups) for (id in g.drop(1)) { copies++; wasted += byId[id]?.size ?: 0L }
        out.put("duplicados", JSONObject().put("n", copies).put("bytes", wasted).put("grupos", groups.size))
        val dirs = emptyDirs.filter { it !in removedDirs }
        out.put("vacias", JSONObject().put("n", dirs.size).put("bytes", 0))
        out.put("total", JSONObject().put("n", live.size).put("bytes", live.sumOf { it.size }))
        out.put("fechaAnalisis", lastScan)
        return out
    }

    fun list(cat: String): JSONArray {
        val arr = JSONArray()
        alive().filter { matches(it, cat) }.sortedByDescending { it.size }.forEach { arr.put(toJson(it)) }
        return arr
    }

    private fun liveDupGroups(): List<List<Int>> =
        dupGroups.map { g -> g.filter { it !in removed } }.filter { it.size > 1 }

    fun duplicates(): JSONArray {
        val arr = JSONArray()
        for (g in liveDupGroups()) {
            val items = JSONArray()
            g.mapNotNull { byId[it] }.forEach { items.put(toJson(it)) }
            arr.put(JSONObject().put("items", items))
        }
        return arr
    }

    fun emptyDirsJson(): JSONArray = JSONArray(emptyDirs.filter { it !in removedDirs })
}
