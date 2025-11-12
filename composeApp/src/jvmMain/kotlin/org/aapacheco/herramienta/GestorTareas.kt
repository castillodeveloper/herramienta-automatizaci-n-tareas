package org.aapacheco.herramienta

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

// ===== DTO inmutable para la UI =====
data class TareaUI(
    val id: Int,
    val nombre: String,
    val comando: String,
    val intervalo: Long,
    val estado: EstadoTarea,
    val ultimaEjecucion: LocalDateTime?,
    val salida: String,
    val error: String
)

/**
 * Gestiona la lista de tareas y sus ejecuciones concurrentes.
 */
object GestorTareas {

    // --- Config núcleo ---
    private const val DEFAULT_TIMEOUT_S: Long = 20          // 0 = sin timeout
    private const val MAX_SALIDA_KB: Int = 256             // tope de salida guardada (streaming lee más, pero no acumula más)
    private val OS_IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    // ================== Estado y alcance de corutinas ==================
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tareas = ConcurrentHashMap<Int, Tarea>()
    private var contadorId = 0
    private var programadorJob: Job? = null

    // Mapa de ejecuciones activas: id -> (proceso, job)
    private val procesosActivos = ConcurrentHashMap<Int, Pair<Process, Job>>()

    // ===== Persistencia simple en .properties =====
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), "HerramientaAutomatizacion")
    private val dataFile: Path = baseDir.resolve("tareas.properties")

    // ======= Estado para la UI (snapshots inmutables) =======
    private val _estadoTareas = MutableStateFlow<List<TareaUI>>(emptyList())
    val estadoTareas: StateFlow<List<TareaUI>> = _estadoTareas.asStateFlow()

    private fun Tarea.toUI(): TareaUI = TareaUI(
        id = id,
        nombre = nombre,
        comando = comando,
        intervalo = intervalo,
        estado = estado,
        ultimaEjecucion = ultimaEjecucion,
        salida = salida,
        error = error
    )

    private fun emitir() {
        _estadoTareas.value = tareas.values.sortedBy { it.id }.map { it.toUI() }
    }

    // ===== Hook de cierre limpio =====
    init {
        try {
            Runtime.getRuntime().addShutdownHook(Thread { cancelAll() })
        } catch (_: Exception) { /* no-op */ }
    }

    // ================== Utilidades SO y validación ==================
    /** Helper: ProcessBuilder según SO (Windows vs Unix). */
    private fun buildProceso(comando: String): ProcessBuilder {
        return if (OS_IS_WINDOWS) {
            ProcessBuilder("cmd", "/c", comando)
        } else {
            ProcessBuilder("bash", "-lc", comando)
        }.redirectErrorStream(true)
    }

    /** Heurística: ¿es un comando que tiende a no terminar si no se limita? */
    fun requiereIntervalo(comando: String): Boolean {
        val c = comando.trim().lowercase()
        if (c.startsWith("ping")) {
            return if (OS_IS_WINDOWS) c.contains(" -t") && !c.contains(" -n ")
            else !c.contains(" -c ")
        }
        return c.startsWith("tail -f") || c.startsWith("watch ") || c == "top" || c == "htop" || c.startsWith("yes")
    }

    // ================== API ==================
    /** Agrega una nueva tarea al gestor. */
    fun agregarTarea(nombre: String, comando: String, intervalo: Long = 0): Tarea {
        val tarea = Tarea(++contadorId, nombre, comando, if (intervalo < 0) 0 else intervalo)
        tareas[tarea.id] = tarea
        guardarEnDisco()
        emitir()
        return tarea
    }

    /** Ejecuta una tarea concreta por ID con salida en streaming y sin solapar. */
    fun ejecutarTarea(id: Int) {
        val tarea = tareas[id] ?: return
        if (tarea.estado == EstadoTarea.EJECUTANDO || procesosActivos.containsKey(id)) return

        // Reset y marcamos en curso
        tarea.estado = EstadoTarea.EJECUTANDO
        tarea.error = ""
        tarea.salida = ""
        emitir()

        appScope.launch {
            var proceso: Process? = null
            var timeoutJob: Job? = null
            try {
                proceso = buildProceso(tarea.comando).start()

                // Registrar ejecución activa para poder cancelarla
                val job: Job = currentCoroutineContext().job
                procesosActivos[id] = proceso to job

                // Timeout (global por ahora, configurable a nivel de constante)
                if (DEFAULT_TIMEOUT_S > 0) {
                    timeoutJob = launch {
                        delay(DEFAULT_TIMEOUT_S * 1000)
                        // Si sigue vivo, forzamos timeout
                        if (isActive && tarea.estado == EstadoTarea.EJECUTANDO) {
                            tarea.estado = EstadoTarea.ERROR
                            tarea.error = "Timeout tras ${DEFAULT_TIMEOUT_S}s"
                            tarea.ultimaEjecucion = LocalDateTime.now()
                            emitir()
                            killProcessTree(proceso)
                        }
                    }
                }

                // STREAMING con límite de almacenamiento (sin bloquear tubería)
                val maxBytes = MAX_SALIDA_KB * 1024
                var usedBytes = 0
                var truncado = false

                proceso.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        // Siempre drenamos, pero no acumulamos por encima del tope
                        if (!truncado) {
                            val lineBytes = line.toByteArray().size + 1 // +1 por salto de línea
                            if (usedBytes + lineBytes <= maxBytes) {
                                if (tarea.salida.isNotEmpty()) tarea.salida += "\n"
                                tarea.salida += line
                                usedBytes += lineBytes
                                emitir()
                            } else {
                                truncado = true
                                // Aviso de truncado (una sola vez)
                                tarea.salida += "\n[… salida truncada a ${MAX_SALIDA_KB} KB …]"
                                emitir()
                            }
                        }
                        // Si ya truncamos, seguimos leyendo sin acumular para no bloquear
                    }
                }

                val code = proceso.waitFor()
                tarea.ultimaEjecucion = LocalDateTime.now()

                // Si no fue cancelada por el usuario (que pone estado = PENDIENTE), cerramos normal
                if (tarea.estado == EstadoTarea.EJECUTANDO) {
                    if (code == 0) {
                        tarea.estado = EstadoTarea.FINALIZADA
                    } else {
                        tarea.estado = EstadoTarea.ERROR
                        if (tarea.error.isBlank()) tarea.error = "Código de salida: $code"
                    }
                }
            } catch (e: Exception) {
                tarea.estado = EstadoTarea.ERROR
                tarea.error = e.message ?: "Error desconocido"
                tarea.ultimaEjecucion = LocalDateTime.now()
            } finally {
                try { timeoutJob?.cancel() } catch (_: Exception) {}
                procesosActivos.remove(id)
                try { LogFiles.escribirLogEjecucion(tarea) } catch (_: Exception) {}
                try { proceso?.destroy() } catch (_: Exception) {}
                guardarEnDisco()
                emitir()
            }
        }
    }

    /** Cancela la ejecución en curso de una tarea (si la hay). */
    fun cancelarEjecucion(id: Int): Boolean {
        val par = procesosActivos.remove(id) ?: return false
        val (proc, job) = par
        try { killProcessTree(proc) } catch (_: Exception) {}
        try { job.cancel() } catch (_: Exception) {}

        tareas[id]?.apply {
            estado = EstadoTarea.PENDIENTE
            error = "Ejecución cancelada por el usuario"
            ultimaEjecucion = LocalDateTime.now()
        }
        guardarEnDisco()
        emitir()
        return true
    }

    /** Elimina una tarea del gestor. Si está ejecutándose, la cancela antes. */
    fun eliminarTarea(id: Int) {
        if (procesosActivos.containsKey(id)) cancelarEjecucion(id)
        tareas.remove(id)
        guardarEnDisco()
        emitir()
    }

    /** Inicia el programador que dispara tareas con intervalo > 0. */
    fun iniciarProgramador() {
        if (programadorJob?.isActive == true) return
        programadorJob = appScope.launch {
            while (isActive) {
                val ahora = LocalDateTime.now()
                tareas.values.forEach { tarea ->
                    if (tarea.intervalo > 0) {
                        val ultima = tarea.ultimaEjecucion
                        val trans = ultima?.let { java.time.Duration.between(it, ahora).seconds } ?: Long.MAX_VALUE
                        val puede = (tarea.estado != EstadoTarea.EJECUTANDO) && (trans >= tarea.intervalo)
                        if (puede) ejecutarTarea(tarea.id)
                    }
                }
                delay(1000)
            }
        }
    }

    fun detenerProgramador() {
        programadorJob?.cancel()
        programadorJob = null
    }

    /** Actualiza una tarea existente. Devuelve true si se modificó. */
    fun actualizarTarea(id: Int, nombre: String, comando: String, intervalo: Long): Boolean {
        val t = tareas[id] ?: return false
        t.nombre = nombre
        t.comando = comando
        t.intervalo = if (intervalo < 0) 0 else intervalo
        guardarEnDisco()
        emitir()
        return true
    }

    /** Carga persistencia a memoria. */
    @Synchronized
    fun cargarDesdeDisco() {
        try {
            if (!Files.exists(dataFile)) return
            val props = Properties().apply {
                Files.newInputStream(dataFile).use { load(it) }
            }
            tareas.clear()
            val count = props.getProperty("tareas.count")?.toIntOrNull() ?: 0
            var maxId = 0
            for (i in 1..count) {
                val id = props.getProperty("tarea.$i.id")?.toIntOrNull() ?: continue
                val nombre = props.getProperty("tarea.$i.nombre") ?: continue
                val comando = props.getProperty("tarea.$i.comando") ?: continue
                val intervalo = props.getProperty("tarea.$i.intervalo")?.toLongOrNull() ?: 0L
                val t = Tarea(id, nombre, comando, intervalo)
                tareas[id] = t
                if (id > maxId) maxId = id
            }
            contadorId = maxId
            emitir()
        } catch (_: Exception) {
            // Silencioso para no romper la app si el archivo está dañado
        }
    }

    /** Escribe persistencia a disco. */
    @Synchronized
    private fun guardarEnDisco() {
        try {
            Files.createDirectories(baseDir)
            val props = Properties()
            val list = tareas.values.sortedBy { it.id }
            props["tareas.count"] = list.size.toString()
            list.forEachIndexed { idx, t ->
                val i = idx + 1
                props["tarea.$i.id"] = t.id.toString()
                props["tarea.$i.nombre"] = t.nombre
                props["tarea.$i.comando"] = t.comando
                props["tarea.$i.intervalo"] = t.intervalo.toString()
            }
            Files.newOutputStream(dataFile).use { props.store(it, "Tareas PSP") }
        } catch (_: Exception) {}
    }

    /** Detiene programador, mata procesos activos y cancela el scope. Para cierre limpio. */
    fun cancelAll() {
        try { programadorJob?.cancel() } catch (_: Exception) {}
        programadorJob = null

        procesosActivos.values.forEach { (proc, job) ->
            try { killProcessTree(proc) } catch (_: Exception) {}
            try { job.cancel() } catch (_: Exception) {}
        }
        procesosActivos.clear()

        try { appScope.cancel() } catch (_: Exception) {}
    }

    // --- Kill de árbol de procesos (Windows) o destroy simple (Unix) ---
    private fun killProcessTree(proc: Process?) {
        if (proc == null) return
        if (OS_IS_WINDOWS) {
            try {
                ProcessBuilder("cmd", "/c", "taskkill /PID ${proc.pid()} /T /F")
                    .start()
                    .waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        } else {
            // En Unix: intenta un cierre amable y, si sigue vivo, fuerza.
            try {
                proc.destroy()                 // termina con SIGTERM
                if (proc.isAlive) {            // si no murió, fuerza
                    proc.destroyForcibly()     // SIGKILL
                }
            } catch (_: Exception) { /* no-op */ }
        }
    }

}
