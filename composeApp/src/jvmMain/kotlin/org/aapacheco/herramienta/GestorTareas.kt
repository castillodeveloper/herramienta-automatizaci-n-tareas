package org.aapacheco.herramienta

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
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

/** Gestiona la lista de tareas y sus ejecuciones concurrentes. */
object GestorTareas {

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

    // Hook de cierre limpio
    init {
        try { Runtime.getRuntime().addShutdownHook(Thread { cancelAll() }) } catch (_: Exception) {}
    }

    // ================== Utilidades SO y validación ==================
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Helper: ProcessBuilder según SO (Windows vs Unix). */
    private fun buildProceso(comando: String): ProcessBuilder =
        if (isWindows) ProcessBuilder("cmd", "/c", comando) else ProcessBuilder("bash", "-lc", comando)
            .redirectErrorStream(true)

    /** Mata el árbol de procesos (padre + hijos). */
    private fun killProcessTree(proc: Process) {
        try {
            val pid = proc.pid()
            if (isWindows) {
                // /T mata el árbol; /F fuerza
                ProcessBuilder("cmd", "/c", "taskkill /PID $pid /T /F")
                    .redirectErrorStream(true).start().waitFor()
            } else {
                // Intento amable + forzado
                ProcessBuilder("bash", "-lc", "pkill -TERM -P $pid || true")
                    .redirectErrorStream(true).start().waitFor()
                ProcessBuilder("bash", "-lc", "kill -TERM $pid || true")
                    .redirectErrorStream(true).start().waitFor()
                Thread.sleep(200)
                ProcessBuilder("bash", "-lc", "pkill -KILL -P $pid || true")
                    .redirectErrorStream(true).start().waitFor()
                ProcessBuilder("bash", "-lc", "kill -KILL $pid || true")
                    .redirectErrorStream(true).start().waitFor()
            }
        } catch (_: Exception) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.destroyForcibly() } catch (_: Exception) {}
        }
    }

    /** Heurística: ¿es un comando que tiende a no terminar si no se limita? */
    fun requiereIntervalo(comando: String): Boolean {
        val c = comando.trim().lowercase()
        if (c.startsWith("ping")) {
            return if (isWindows) c.contains(" -t") && !c.contains(" -n ")
            else !c.contains(" -c ")
        }
        return c.startsWith("tail -f") || c.startsWith("watch ")
                || c == "top" || c == "htop" || c.startsWith("yes")
    }

    // ================== API ==================
    fun agregarTarea(nombre: String, comando: String, intervalo: Long = 0): Tarea {
        val tarea = Tarea(++contadorId, nombre, comando, if (intervalo < 0) 0 else intervalo)
        tareas[tarea.id] = tarea
        guardarEnDisco(); emitir()
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
            try {
                proceso = buildProceso(tarea.comando).start()

                // Registrar ejecución activa (job correcto)
                val job: Job = currentCoroutineContext().job
                procesosActivos[id] = proceso to job

                // STREAMING: leer línea a línea y publicar a la UI
                proceso.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(line)
                        tarea.salida = sb.toString()
                        emitir()
                    }
                }

                val code = proceso.waitFor()
                tarea.ultimaEjecucion = LocalDateTime.now()

                if (tarea.estado == EstadoTarea.EJECUTANDO) {
                    tarea.estado = if (code == 0) EstadoTarea.FINALIZADA else EstadoTarea.ERROR
                    if (code != 0) tarea.error = "Código de salida: $code"
                }
            } catch (e: Exception) {
                tarea.estado = EstadoTarea.ERROR
                tarea.error = e.message ?: "Error desconocido"
                tarea.ultimaEjecucion = LocalDateTime.now()
            } finally {
                procesosActivos.remove(id)
                try { LogFiles.escribirLogEjecucion(tarea) } catch (_: Exception) {}
                try {
                    if (proceso != null && proceso.isAlive) killProcessTree(proceso)
                } catch (_: Exception) {}
                guardarEnDisco(); emitir()
            }
        }
    }

    /** Cancela la ejecución en curso de una tarea (si la hay). */
    fun cancelarEjecucion(id: Int): Boolean {
        val par = procesosActivos.remove(id) ?: return false
        val (proc, job) = par
        // Primero matamos el árbol del proceso; luego cancelamos la corutina lectora
        try { killProcessTree(proc) } catch (_: Exception) {}
        try { job.cancel() } catch (_: Exception) {}

        tareas[id]?.apply {
            estado = EstadoTarea.PENDIENTE
            error = "Ejecución cancelada por el usuario"
            ultimaEjecucion = LocalDateTime.now()
        }
        guardarEnDisco(); emitir()
        return true
    }

    fun eliminarTarea(id: Int) {
        if (procesosActivos.containsKey(id)) cancelarEjecucion(id)
        tareas.remove(id)
        guardarEnDisco(); emitir()
    }

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

    fun actualizarTarea(id: Int, nombre: String, comando: String, intervalo: Long): Boolean {
        val t = tareas[id] ?: return false
        t.nombre = nombre
        t.comando = comando
        t.intervalo = if (intervalo < 0) 0 else intervalo
        guardarEnDisco(); emitir()
        return true
    }

    @Synchronized
    fun cargarDesdeDisco() {
        try {
            if (!Files.exists(dataFile)) return
            val props = Properties().apply { Files.newInputStream(dataFile).use { load(it) } }
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
        } catch (_: Exception) { /* no-op */ }
    }

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

    /** Cierre limpio global. */
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
}
