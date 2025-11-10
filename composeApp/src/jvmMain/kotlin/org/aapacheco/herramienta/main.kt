package org.aapacheco.herramienta

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import java.lang.Thread.sleep

fun main() = application {
    // Creamos una tarea de prueba
    val tarea = GestorTareas.agregarTarea(
        nombre = "Listar archivos del directorio actual",
        comando = "dir"
    )

    // Ejecutamos la tarea
    println("Ejecutando tarea: ${tarea.nombre}")
    GestorTareas.ejecutarTarea(tarea.id)

    // Esperamos unos segundos para que termine el proceso
    runBlocking {
        delay(3000)
    }

    // Mostramos la salida registrada
    val resultado = GestorTareas.listarTareas().first()
    println("\n--- Resultado de la tarea ---")
    println(resultado.salida)
    println("------------------------------")
    println("Estado final: ${resultado.estado}")
}