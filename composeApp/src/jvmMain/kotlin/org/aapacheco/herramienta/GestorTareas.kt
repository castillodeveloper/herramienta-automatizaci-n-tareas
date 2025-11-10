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
                tarea.estado = EstadoTarea.ERROR
                tarea.error = e.message ?: "Error desconocido"
            }
        }
    }

    /**
     * Devuelve la lista de tareas registradas.
     */
    fun listarTareas(): List<Tarea> = tareas.values.toList()

    /**
     * Elimina una tarea del gestor.
     */
    fun eliminarTarea(id: Int) {
        tareas.remove(id)
    }
}
