package org.aapacheco.herramienta

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestiona la lista de tareas y sus ejecuciones concurrentes.
 */
object GestorTareas {

    private val tareas = ConcurrentHashMap<Int, Tarea>()
    private var contadorId = 0
    private var programadorJob: Job? = null

    /**
     * Agrega una nueva tarea al gestor.
     */
    fun agregarTarea(nombre: String, comando: String, intervalo: Long = 0): Tarea {
        val tarea = Tarea(++contadorId, nombre, comando, intervalo)
        tareas[tarea.id] = tarea
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
                        val trans = ultima?.let { java.time.Duration.between(it, ahora).seconds }
                            ?: Long.MAX_VALUE
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
}
