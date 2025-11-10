package org.aapacheco.herramienta

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

// Formato de hora para "Última ejecución"
private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun App() {
    MaterialTheme {
        var nombre by remember { mutableStateOf("") }
        var comando by remember { mutableStateOf("") }
        var intervaloTxt by remember { mutableStateOf("0") }
        var programadorActivo by remember { mutableStateOf(false) }

        // Refresco periódico de la lista para ver cambios de estado/salida
        var tareas by remember { mutableStateOf(listOf<Tarea>()) }
        LaunchedEffect(Unit) {
            while (true) {
                tareas = GestorTareas.listarTareas()
                delay(500)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Herramienta de Automatización de Tareas", style = MaterialTheme.typography.h6)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.width(220.dp)
                )
                OutlinedTextField(
                    value = comando,
                    onValueChange = { comando = it },
                    label = { Text("Comando o script (p. ej. dir / echo Hola)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = intervaloTxt,
                    onValueChange = { intervaloTxt = it.filter(Char::isDigit) },
                    label = { Text("Intervalo (s)") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                Button(onClick = {
                    val n = nombre.trim()
                    val c = comando.trim()
                    val intervalo = intervaloTxt.toLongOrNull() ?: 0
                    if (n.isNotEmpty() && c.isNotEmpty()) {
                        GestorTareas.agregarTarea(n, c, intervalo)
                        nombre = ""
                        comando = ""
                        intervaloTxt = "0"
                    }
                }) { Text("Añadir") }

                Button(onClick = {
                    if (!programadorActivo) {
                        GestorTareas.iniciarProgramador()
                        programadorActivo = true
                    }
                }) {
                    Text(if (programadorActivo) "Programador activo" else "Iniciar programador")
                }
            }

            Divider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tareas, key = { it.id }) { t ->
                    TareaRow(t)
                }
            }
        }
    }
}

@Composable
private fun TareaRow(t: Tarea) {
    var verLog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val ultima = t.ultimaEjecucion?.format(HHMMSS) ?: "-"
            Text(
                "${t.id}. ${t.nombre} | cmd: '${t.comando}' | cada ${t.intervalo}s | última: $ultima | estado: ${t.estado}",
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { GestorTareas.ejecutarTarea(t.id) }) { Text("Ejecutar") }
            OutlinedButton(onClick = { verLog = true }) { Text("Ver log") }
            OutlinedButton(onClick = { GestorTareas.eliminarTarea(t.id) }) { Text("Eliminar") }
        }

        if (verLog) {
            AlertDialog(
                onDismissRequest = { verLog = false },
                title = { Text("Salida de: ${t.nombre}") },
                text = {
                    Box(
                        modifier = Modifier
                            .width(700.dp)
                            .height(300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val texto = buildString {
                            if (t.salida.isNotBlank()) append(t.salida)
                            if (t.error.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n")
                                append("[ERROR]\n${t.error}")
                            }
                            if (isEmpty()) append("(sin salida)")
                        }
                        Text(texto)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { verLog = false }) { Text("Cerrar") }
                }
            )
        }
    }
}
