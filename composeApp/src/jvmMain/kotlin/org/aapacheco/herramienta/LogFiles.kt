package org.aapacheco.herramienta

import java.awt.Desktop
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LogFiles {
    // Carpeta: C:\Users\<usuario>\HerramientaAutomatizacion\logs
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), "HerramientaAutomatizacion")
    private val logsDir: Path = baseDir.resolve("logs")

    private val humanStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val fileStamp  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    init {
        try { Files.createDirectories(logsDir) } catch (_: Exception) {}
    }

    /** Crea un .log por ejecución con salida y error (si lo hay) y devuelve la ruta creada. */
    fun escribirLogEjecucion(t: Tarea): Path {
        val safeName = t.nombre.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        val file = logsDir.resolve("${t.id}_${safeName}_${LocalDateTime.now().format(fileStamp)}.log")

        val cuerpo = buildString {
            appendLine("== Tarea #${t.id} — ${t.nombre} ==")
            appendLine("Fecha: ${LocalDateTime.now().format(humanStamp)}")
            appendLine("Comando: ${t.comando}")
            appendLine("Intervalo (s): ${t.intervalo}")
            appendLine("Estado: ${t.estado}")
            appendLine("Última ejecución: ${t.ultimaEjecucion ?: "-"}")
            appendLine()
            if (t.salida.isNotBlank()) {
                appendLine("[SALIDA]")
                appendLine(t.salida)
            }
            if (t.error.isNotBlank()) {
                if (t.salida.isNotBlank()) appendLine()
                appendLine("[ERROR]")
                appendLine(t.error)
            }
        }

        Files.writeString(
            file,
            cuerpo,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        )
        return file
    }

    /** Abre la carpeta de logs en el explorador del sistema. */
    fun abrirCarpetaLogs() {
        if (Desktop.isDesktopSupported()) {
            try { Desktop.getDesktop().open(logsDir.toFile()) } catch (_: Exception) {}
        }
    }
}
