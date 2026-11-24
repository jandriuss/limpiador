package co.limpiador.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Apps instaladas: uso, tamaño, caché y revisión de seguridad. */
object AppsInfo {

    /** Tiendas reconocidas. Una app instalada por cualquier otro medio se marca como "fuera de tienda". */
    private val STORES = mapOf(
        "com.android.vending" to "Play Store",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.huawei.appmarket" to "AppGallery",
        "com.xiaomi.market" to "Tienda Xiaomi",
        "com.xiaomi.mipicks" to "GetApps (Xiaomi)",
        "com.heytap.market" to "Tienda OPPO/realme",
        "com.oppo.market" to "Tienda OPPO",
        "com.bbk.appstore" to "Tienda vivo",
        "com.vivo.appstore" to "Tienda vivo",
        "com.amazon.venezia" to "Amazon Appstore",
        "org.fdroid.fdroid" to "F-Droid",
        "com.motorola.ccc.ota" to "Motorola"
    )

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT)
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isUserApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun installedPackages(pm: PackageManager, flags: Int): List<PackageInfo> =
        pm.getInstalledPackages(flags)

    /** Lista de apps del usuario con último uso, tamaño y caché. */
    fun apps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val usage = hasUsageAccess(ctx)
        val now = System.currentTimeMillis()

        val lastUse = HashMap<String, Long>()
        if (usage) {
            try {
                val usm = ctx.getSystemService(UsageStatsManager::class.java)
                val stats = usm.queryAndAggregateUsageStats(now - 2L * 365 * 24 * 3600_000L, now)
                for ((pkg, s) in stats) {
                    // Última vez que la app estuvo visible en pantalla (uso real, no procesos en segundo plano)
                    val t = if (s.lastTimeVisible > 0) s.lastTimeVisible
                            else if (s.totalTimeInForeground > 0) s.lastTimeUsed else 0L
                    if (t > 0) lastUse[pkg] = t
                }
            } catch (_: Exception) { }
        }
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)

        val out = JSONArray()
        for (pi in installedPackages(pm, 0)) {
            val ai = pi.applicationInfo ?: continue
            if (!isUserApp(ai) || pi.packageName == ctx.packageName) continue
            val o = JSONObject()
                .put("pkg", pi.packageName)
                .put("nombre", pm.getApplicationLabel(ai).toString())
                .put("instalada", pi.firstInstallTime)
                .put("actualizada", pi.lastUpdateTime)
                .put("ultimoUso", lastUse[pi.packageName] ?: 0L)
            val inst = installerOf(pm, pi.packageName)
            o.put("origen", STORES[inst] ?: if (inst == null) "Instalada manualmente" else "Otro instalador")
            o.put("deTienda", inst in STORES)
            if (usage) {
                try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pi.packageName, Process.myUserHandle())
                    o.put("app", s.appBytes).put("datos", s.dataBytes).put("cache", s.cacheBytes)
                } catch (_: Exception) { }
            }
            out.put(o)
        }
        return out
    }

    private fun enabledPackages(ctx: Context, key: String): Set<String> {
        val raw = Settings.Secure.getString(ctx.contentResolver, key) ?: return emptySet()
        return raw.split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
    }

    private fun opAllowed(ops: AppOpsManager, op: String, uid: Int, pkg: String): Boolean = try {
        ops.unsafeCheckOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    /**
     * Revisión de seguridad por señales de riesgo (no es un antivirus con firmas):
     * origen de instalación, accesos especiales activos y permisos sensibles concedidos.
     */
    fun security(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val accessibility = enabledPackages(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listeners = enabledPackages(ctx, "enabled_notification_listeners")
        val admins = try {
            ctx.getSystemService(DevicePolicyManager::class.java).activeAdmins?.map { it.packageName }?.toSet()
        } catch (_: Exception) { null } ?: emptySet()

        val results = ArrayList<Pair<Int, JSONObject>>()
        for (pi in installedPackages(pm, PackageManager.GET_PERMISSIONS)) {
            val ai = pi.applicationInfo ?: continue
            if (!isUserApp(ai) || pi.packageName == ctx.packageName) continue
            val pkg = pi.packageName
            val granted = HashSet<String>()
            val req = pi.requestedPermissions ?: emptyArray()
            val flags = pi.requestedPermissionsFlags ?: IntArray(0)
            for (i in req.indices) {
                if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(req[i])
            }
            val inst = installerOf(pm, pkg)
            val reasons = JSONArray()
            var score = 0
            if (inst !in STORES) { score += 2; reasons.put("Instalada por fuera de una tienda oficial") }
            if (pkg in accessibility) { score += 3; reasons.put("Tiene activo el servicio de Accesibilidad (puede leer la pantalla y tocar por usted)") }
            if (pkg in admins) { score += 2; reasons.put("Es administradora del dispositivo (difícil de desinstalar)") }
            if (pkg in listeners) { score += 2; reasons.put("Lee todas sus notificaciones (incluye códigos de verificación)") }
            if ("android.permission.READ_SMS" in granted || "android.permission.RECEIVE_SMS" in granted) {
                score += 2; reasons.put("Puede leer sus mensajes SMS")
            }
            if (opAllowed(ops, "android:system_alert_window", ai.uid, pkg)) { score += 1; reasons.put("Puede mostrarse encima de otras apps") }
            if ("android.permission.REQUEST_INSTALL_PACKAGES" in req) { score += 1; reasons.put("Puede instalar otras apps") }
            if ("android.permission.READ_CALL_LOG" in granted) { score += 1; reasons.put("Puede leer el registro de llamadas") }
            if (score < 2) continue
            val nivel = when { score >= 5 -> "alto"; score >= 3 -> "medio"; else -> "bajo" }
            results.add(score to JSONObject()
                .put("pkg", pkg)
                .put("nombre", pm.getApplicationLabel(ai).toString())
                .put("nivel", nivel)
                .put("origen", STORES[inst] ?: if (inst == null) "Instalada manualmente" else "Otro instalador")
                .put("motivos", reasons))
        }
        val out = JSONArray()
        results.sortedByDescending { it.first }.forEach { out.put(it.second) }
        return out
    }
}
