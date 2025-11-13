package org.aapacheco.herramienta

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

data class TareaUI(
    val id: Int,
    val nombre: String,
    val comando: String,
    val intervalo: Long,
    val estado: EstadoTarea,
    val ultimaEjecucion: LocalDateTime?,
    val salida: String,
    val error: String,
    val habilitada: Boolean,
    val cwd: String,
    val timeout: Long?,
    val env: Map<String, String>
)

/**
 * Núcleo principal del gestor: controla las tareas,
 * su ejecución concurrente y su persistencia.
 */
object GestorTareas {

    private const val DEFAULT_TIMEOUT_S: Long = 20
    private const val MAX_SALIDA_KB: Int = 256
    private val OS_IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tareas = ConcurrentHashMap<Int, Tarea>()
    private var contadorId = 0
    private var programadorJob: Job? = null

    private val procesosActivos = ConcurrentHashMap<Int, Pair<Process, Job>>()

    private val baseDir: Path = Paths.get(System.getProperty("user.home"), "HerramientaAutomatizacion")
    private val dataFile: Path = baseDir.resolve("tareas.properties")

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
        error = error,
        habilitada = habilitada,
        cwd = cwd,
        timeout = timeout,
        env = env.toMap()
    )

    private fun emitir() {
        _estadoTareas.value = tareas.values.sortedBy { it.id }.map { it.toUI() }
    }

    init {
        try { Runtime.getRuntime().addShutdownHook(Thread { cancelAll() }) } catch (_: Exception) {}
    }

    private fun buildProceso(comando: String): ProcessBuilder {
        val pb = if (OS_IS_WINDOWS) ProcessBuilder("cmd", "/c", comando)
        else ProcessBuilder("bash", "-lc", comando)
        return pb.redirectErrorStream(true)
    }

    fun requiereIntervalo(comando: String): Boolean {
        val c = comando.trim().lowercase()
        if (c.startsWith("ping")) {
            return if (OS_IS_WINDOWS) c.contains(" -t") && !c.contains(" -n ") else !c.contains(" -c ")
        }
        return c.startsWith("tail -f") || c.startsWith("watch ") || c == "top" || c == "htop" || c.startsWith("yes")
    }

    fun estaEjecutando(id: Int): Boolean =
        procesosActivos.containsKey(id) || tareas[id]?.estado == EstadoTarea.EJECUTANDO

    fun agregarTarea(nombre: String, comando: String, intervalo: Long = 0): Tarea {
        val tarea = Tarea(++contadorId, nombre, comando, if (intervalo < 0) 0 else intervalo)
        tareas[tarea.id] = tarea
        guardarEnDisco(); emitir()
        return tarea
    }

    fun duplicarTarea(id: Int): Tarea? {
        val src = tareas[id] ?: return null
        val copia = Tarea(
            id = ++contadorId,
            nombre = "${src.nombre} (copia)",
            comando = src.comando,
            intervalo = src.intervalo,
            habilitada = src.habilitada,
            cwd = src.cwd,
            timeout = src.timeout,
            env = src.env.toMutableMap()
        )
        tareas[copia.id] = copia
        guardarEnDisco(); emitir()
        return copia
    }

    fun toggleHabilitada(id: Int, on: Boolean) {
        tareas[id]?.let {
            it.habilitada = on
            guardarEnDisco(); emitir()
        }
    }

    fun ejecutarTarea(id: Int) {
        val tarea = tareas[id] ?: return
        if (!tarea.habilitada) return
        if (tarea.estado == EstadoTarea.EJECUTANDO || procesosActivos.containsKey(id)) return

        tarea.estado = EstadoTarea.EJECUTANDO
        tarea.error = ""
        tarea.salida = ""
        emitir()
        delayLaunch(100) // 🔹 permite a Compose repintar antes de arrancar

        appScope.launch {
            var proceso: Process? = null
            var timeoutJob: Job? = null
            try {
                val pb = buildProceso(tarea.comando)
                if (tarea.cwd.isNotBlank()) {
                    val dir = File(tarea.cwd)
                    if (dir.isDirectory) pb.directory(dir)
                }
                if (tarea.env.isNotEmpty()) pb.environment().putAll(tarea.env)

                proceso = pb.start()
                val job: Job = currentCoroutineContext().job
                procesosActivos[id] = proceso to job
                emitir()

                val effectiveTimeout = tarea.timeout?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_S
                if (effectiveTimeout > 0) {
                    timeoutJob = launch {
                        delay(effectiveTimeout * 1000)
                        if (isActive && tarea.estado == EstadoTarea.EJECUTANDO) {
                            tarea.estado = EstadoTarea.ERROR
                            tarea.error = "Timeout tras ${effectiveTimeout}s"
                            tarea.ultimaEjecucion = LocalDateTime.now()
                            emitir()
                            killProcessTree(proceso)
                        }
                    }
                }

                val maxBytes = MAX_SALIDA_KB * 1024
                var usedBytes = 0
                var truncado = false

                proceso.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (!truncado) {
                            val lineBytes = line.toByteArray().size + 1
                            if (usedBytes + lineBytes <= maxBytes) {
                                if (tarea.salida.isNotEmpty()) tarea.salida += "\n"
                                tarea.salida += line
                                usedBytes += lineBytes
                                emitir()
                            } else {
                                truncado = true
                                tarea.salida += "\n[… salida truncada a ${MAX_SALIDA_KB} KB …]"
                                emitir()
                            }
                        }
                    }
                }

                val code = proceso.waitFor()
                tarea.ultimaEjecucion = LocalDateTime.now()
                if (tarea.estado == EstadoTarea.EJECUTANDO) {
                    tarea.estado = if (code == 0) EstadoTarea.FINALIZADA else EstadoTarea.ERROR
                    if (code != 0 && tarea.error.isBlank()) tarea.error = "Código de salida: $code"
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
                guardarEnDisco(); emitir()
            }
        }
    }

    private fun delayLaunch(ms: Long) {
        appScope.launch { delay(ms) }
    }

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
                    if (tarea.habilitada && tarea.intervalo > 0) {
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

    fun actualizarTareaFull(
        id: Int,
        nombre: String,
        comando: String,
        intervalo: Long,
        habilitada: Boolean,
        cwd: String,
        timeout: Long?,
        env: Map<String, String>
    ): Boolean {
        val t = tareas[id] ?: return false
        t.nombre = nombre
        t.comando = comando
        t.intervalo = if (intervalo < 0) 0 else intervalo
        t.habilitada = habilitada
        t.cwd = cwd.trim()
        t.timeout = timeout?.takeIf { it > 0 }
        t.env.clear(); t.env.putAll(env)
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
                val habilitada = props.getProperty("tarea.$i.habilitada")?.lowercase() != "false"
                val cwd = props.getProperty("tarea.$i.cwd") ?: ""
                val timeout = props.getProperty("tarea.$i.timeout")?.toLongOrNull()?.takeIf { it > 0 }

                val envCount = props.getProperty("tarea.$i.envCount")?.toIntOrNull() ?: 0
                val env = mutableMapOf<String, String>()
                for (j in 0 until envCount) {
                    val k = props.getProperty("tarea.$i.env.$j.key") ?: continue
                    val v = props.getProperty("tarea.$i.env.$j.val") ?: ""
                    env[k] = v
                }

                val t = Tarea(id, nombre, comando, intervalo,
                    habilitada = habilitada, cwd = cwd, timeout = timeout, env = env)
                tareas[id] = t
                if (id > maxId) maxId = id
            }
            contadorId = maxId
            emitir()
        } catch (_: Exception) {}
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
                props["tarea.$i.habilitada"] = t.habilitada.toString()
                props["tarea.$i.cwd"] = t.cwd
                props["tarea.$i.timeout"] = (t.timeout ?: 0L).toString()
                props["tarea.$i.envCount"] = t.env.size.toString()
                var j = 0
                t.env.forEach { (k, v) ->
                    props["tarea.$i.env.$j.key"] = k
                    props["tarea.$i.env.$j.val"] = v
                    j++
                }
            }
            Files.newOutputStream(dataFile).use { props.store(it, "Tareas PSP") }
        } catch (_: Exception) {}
    }

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

    private fun killProcessTree(proc: Process?) {
        if (proc == null) return
        if (OS_IS_WINDOWS) {
            try {
                ProcessBuilder("cmd", "/c", "taskkill /PID ${proc.pid()} /T /F")
                    .start().waitFor(2000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        } else {
            try {
                proc.destroy()
                if (proc.isAlive) proc.destroyForcibly()
            } catch (_: Exception) {}
        }
    }
}
