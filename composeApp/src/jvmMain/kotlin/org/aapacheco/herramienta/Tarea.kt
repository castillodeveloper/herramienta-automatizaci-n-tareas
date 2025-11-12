package org.aapacheco.herramienta

import java.time.LocalDateTime

enum class EstadoTarea { PENDIENTE, EJECUTANDO, FINALIZADA, ERROR }

data class Tarea(
    val id: Int,
    var nombre: String,
    var comando: String,
    var intervalo: Long,
    var estado: EstadoTarea = EstadoTarea.PENDIENTE,
    var ultimaEjecucion: LocalDateTime? = null,
    var salida: String = "",
    var error: String = "",

    // P1 — nuevos campos
    var habilitada: Boolean = true,
    var cwd: String = "",               // directorio de trabajo (opcional)
    var timeout: Long? = null,          // en segundos (null/0 => sin límite)
    var env: MutableMap<String, String> = mutableMapOf() // variables de entorno por tarea
)
