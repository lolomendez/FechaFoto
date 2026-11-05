# FechaFoto

Compara la fecha del nombre de fichero (IMG_20230415_142310, PXL_…, VID_…, 2023-04-15 14.23.10, IMG-20230415-WA0001, epoch ms…)
con la fecha de captura de los metadatos (EXIF en fotos, mvhd en vídeos) y permite regenerarla.

## Compilar
1. Android Studio (Koala o superior) > Open > carpeta `FechaFoto`. Deja que sincronice Gradle (descarga Gradle 8.9).
2. Conecta el móvil (Android 11+) con depuración USB > Run ▶.

## Uso
Analizar > filtrar por ⚠️/❌ > marcar > "Regenerar fecha desde el nombre". Android pide confirmación una sola vez por lote.
"Deshacer último lote" restaura los valores originales (copia guardada en el almacenamiento privado de la app).

## Notas
- Si el nombre no trae hora (WhatsApp), se usa las 12:00 y la comparación es solo por día.
- Fotos: JPEG/PNG/WebP se escriben dentro del fichero. HEIC/AVIF y otros: solo se actualiza la fecha de la galería (MediaStore).
- Vídeos: MP4/MOV/3GP se parchean in situ (sin recodificar). MKV/WebM: solo galería.
- Los vídeos guardan la fecha en UTC; la app la convierte a hora local para comparar.
- Tolerancia de coincidencia con hora: ±2 min.

## Descargar el APK
Cada compilación se publica en la pestaña **Releases** del repositorio (github.com/<usuario>/FechaFoto/releases) como `app-debug.apk`, descarga directa sin zip.

## Sin Android Studio: compilar en GitHub
1. Crea un repositorio privado en github.com y sube todo el contenido de esta carpeta (incluida `.github`).
2. Pestaña Actions > "Build APK" > espera ~5 min.
3. Descarga el artefacto `FechaFoto-apk`, descomprímelo e instala `app-debug.apk` en el móvil (permitir "instalar apps desconocidas").
