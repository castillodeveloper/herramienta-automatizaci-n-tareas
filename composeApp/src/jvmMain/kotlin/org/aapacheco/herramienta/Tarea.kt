package org.aapacheco.herramienta

import java.time.LocalDateTime

/**
 * Representa una tarea automatizada definida por el usuario.
 */
data class Tarea(
    val id: Int,                      // Identificador único
    var nombre: String,               // Nombre de la tarea
    var comando: String,              // Comando o script a ejecutar
    var intervalo: Long = 0,          // Intervalo en segundos (0 = manual)
    var ultimaEjecucion: LocalDateTime? = null,  // Última vez que se ejecutó
    var estado: EstadoTarea = EstadoTarea.PENDIENTE,  // Estado actual
    var salida: String = "",           // Última salida registrada
    var error: String = ""             // Último error registrado
)

/**
 * Posibles estados de una tarea.
 */
enum class EstadoTarea {
    PENDIENTE,
    EJECUTANDO,
    FINALIZADA,
    ERROR
}
