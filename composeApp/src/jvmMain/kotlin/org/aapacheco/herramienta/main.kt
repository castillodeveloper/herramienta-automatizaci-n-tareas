package org.aapacheco.herramienta

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Herramienta de Automatización de Tareas",
        state = rememberWindowState(
            size = DpSize(1100.dp, 700.dp),
            // Usa UNA de estas dos, según tu versión:
            position = WindowPosition(Alignment.Center)          // ✅ versiones recientes
            // position = WindowPosition.Aligned(Alignment.Center) // ✅ si tu API lo pide así
        ),
        resizable = false
    ) {
        App()
    }

}
