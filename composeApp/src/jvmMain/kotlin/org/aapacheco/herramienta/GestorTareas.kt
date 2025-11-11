package org.aapacheco.herramienta

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Gestiona la lista de tareas y sus ejecuciones concurrentes.
 */
object GestorTareas {

    private val tareas = ConcurrentHashMap<Int, Tarea>()
    private var contadorId = 0
    private var programadorJob: Job? = null

    // ===== Persistencia simple en .properties =====
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), "HerramientaAutomatizacion")
    private val dataFile: Path = baseDir.resolve("tareas.properties")

    /**
     * Agrega una nueva tarea al gestor.
     */
    fun agregarTarea(nombre: String, comando: String, intervalo: Long = 0): Tarea {
        val tarea = Tarea(++contadorId, nombre, comando, intervalo)
        tareas[tarea.id] = tarea
        guardarEnDisco()                 // ← guarda tras añadir
        return tarea
    }


    /**
     * Ejecuta una tarea concreta por ID, usando ProcessBuilder.
     */
    fun ejecutarTarea(id: Int) {
        val tarea = tareas[id] ?: return
        tarea.estado = EstadoTarea.EJECUTANDO

        // Corrutina para no bloquear la interfaz
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val proceso = ProcessBuilder("cmd", "/c", tarea.comando)
                    .redirectErrorStream(true)
                    .start()

                val salida = BufferedReader(InputStreamReader(proceso.inputStream)).readText()
                val codigoSalida = proceso.waitFor()

                tarea.salida = salida
                tarea.ultimaEjecucion = LocalDateTime.now()

                if (codigoSalida == 0) {
                    tarea.estado = EstadoTarea.FINALIZADA
                } else {
                    tarea.estado = EstadoTarea.ERROR
                    tarea.error = "Código de salida: $codigoSalida"
                }
            } catch (e: Exception) {
                // Si falla la ejecución, marcamos ERROR y registramos hora también
                tarea.estado = EstadoTarea.ERROR
                tarea.error = e.message ?: "Error desconocido"
                tarea.ultimaEjecucion = LocalDateTime.now()
            } finally {
                // Guardar log SIEMPRE (éxito o error). Ignoramos cualquier fallo al escribir.
                try { LogFiles.escribirLogEjecucion(tarea) } catch (_: Exception) {}
            }
        }
    }

    /** Devuelve la lista de tareas registradas. */
    fun listarTareas(): List<Tarea> = tareas.values.toList()

    /** Elimina una tarea del gestor. */
    fun eliminarTarea(id: Int) {
        tareas.remove(id)
        guardarEnDisco()                 // ← guarda tras borrar
    }


    /**
     * Inicia un bucle que revisa periódicamente todas las tareas
     * con intervalo > 0 y las ejecuta automáticamente.
     */
    fun iniciarProgramador() {
        if (programadorJob?.isActive == true) return
        programadorJob = GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val ahora = LocalDateTime.now()
                tareas.values.forEach { tarea ->
                    if (tarea.intervalo > 0) {
                        val ultima = tarea.ultimaEjecucion
                        val trans = ultima?.let { java.time.Duration.between(it, ahora).seconds } ?: Long.MAX_VALUE
                        val puede = (tarea.estado != EstadoTarea.EJECUTANDO) && (trans >= tarea.intervalo)
                        if (puede) {
                            println("⏰ Ejecutando tarea automática: ${tarea.nombre}")
                            ejecutarTarea(tarea.id)
                        }
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
        guardarEnDisco()                 // ← guarda tras editar
        return true
    }


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
        } catch (_: Exception) {
            // Silencioso para no romper la app si el archivo está dañado
        }
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
}
