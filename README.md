# Limpiador

App Android (10 o superior) para liberar espacio en el celular, en español y sin publicidad.
No tiene permiso de internet: nada de lo que analiza sale del teléfono.

## Descargar

Último APK: https://github.com/jandriuss/limpiador/releases/latest/download/Limpiador.apk

Cada cambio que se sube a `main` se compila solo con GitHub Actions y queda en *Releases*.

## Qué hace

| Sección | Qué hace | Límite de Android |
|---|---|---|
| Archivos | Busca videos, audios, imágenes, documentos, APK, comprimidos, temporales (miniaturas, .tmp, .log), descargas, WhatsApp (incluye "Enviados"), Telegram, capturas, archivos de +100 MB, archivos de +1 año, **duplicados exactos** (SHA-256) y carpetas vacías. | Requiere el permiso "Acceso a todos los archivos". No toca `Android/data` ni `Android/obb` (datos privados de otras apps). |
| Papelera | Lo eliminado puede ir primero a la papelera (`.LimpiadorPapelera`), recuperable 7 días. | El espacio se libera al vaciarla. |
| Caché | Botón que abre el diálogo de Android para borrar la caché externa de todas las apps (`ACTION_CLEAR_APP_CACHE`, Android 11+). | La caché interna de cada app solo se borra desde Ajustes → App → Almacenamiento (botón "Info"). |
| Apps | Lista apps sin uso hace +30 / +90 días, tamaño y caché; desinstalar con un toque. | Android pide confirmar cada desinstalación. Requiere "Acceso a datos de uso". |
| Seguridad | Señala apps instaladas fuera de tiendas oficiales, con Accesibilidad activa, administradoras del dispositivo, que leen notificaciones/SMS o se muestran encima de otras. | **No es un antivirus**: para eso, Google Play Protect. |

## Estructura

- `app/src/main/assets/www/index.html` — interfaz (HTML/JS).
- `app/src/main/java/co/limpiador/app/`
  - `MainActivity.kt` — WebView, permisos, miniaturas e íconos.
  - `Bridge.kt` — puente JavaScript ↔ Android (`window.Limpiador`).
  - `Scanner.kt` — análisis y clasificación de archivos, duplicados.
  - `Trash.kt` — papelera.
  - `AppsInfo.kt` — apps, uso, caché y revisión de seguridad.
