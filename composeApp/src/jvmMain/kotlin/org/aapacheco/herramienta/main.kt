package org.aapacheco.herramienta

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Herramienta de Automatización de Tareas") {
        App()
    }
}
