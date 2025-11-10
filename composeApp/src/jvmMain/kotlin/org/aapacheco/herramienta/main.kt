package org.aapacheco.herramienta

import androidx.compose.ui.window.application
import kotlinx.coroutines.*

fun main() = application {
    // Agregamos dos tareas
    GestorTareas.agregarTarea("Saludo", "echo Hola PSP!", intervalo = 5)
    GestorTareas.agregarTarea("Listar archivos", "dir", intervalo = 10)

    println("Programador iniciado. Las tareas se ejecutarán automáticamente...")

    GestorTareas.iniciarProgramador()

    // Mantiene la app viva mientras se ejecutan las tareas
    Thread.sleep(30000)
}
