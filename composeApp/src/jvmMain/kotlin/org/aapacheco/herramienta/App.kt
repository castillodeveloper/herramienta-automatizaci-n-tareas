package org.aapacheco.herramienta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

// Formato de hora para "Última ejecución"
private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun App() {
    MaterialTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        var nombre by remember { mutableStateOf("") }
        var comando by remember { mutableStateOf("") }
        var intervaloTxt by remember { mutableStateOf("0") }
        var programadorActivo by remember { mutableStateOf(false) }

        var tareas by remember { mutableStateOf(listOf<Tarea>()) }
        LaunchedEffect(Unit) {
            GestorTareas.cargarDesdeDisco()
            while (true) {
                tareas = GestorTareas.listarTareas()
                delay(500)
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            // Contenedor CENTRADO y FIJO (ancho constante)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ancho fijo del contenido
                Column(
                    modifier = Modifier.width(980.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Herramienta de Automatización de Tareas",
                        style = MaterialTheme.typography.h6
                    )

                    // --- FILA FIJA DE CONTROLES SUPERIORES ---
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
                            modifier = Modifier.width(240.dp)
                        )

                        OutlinedTextField(
                            value = comando,
                            onValueChange = { comando = it },
                            label = { Text("Comando") },                 // label corto
                            placeholder = { Text("p. ej.  dir    /    echo Hola") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)               // ocupa el resto
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
                                nombre = ""; comando = ""; intervaloTxt = "0"
                                scope.launch { snackbarHostState.showSnackbar("Tarea '$n' añadida") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Completa nombre y comando") }
                            }
                        }) { Text("Añadir") }

                        Button(onClick = {
                            if (programadorActivo) {
                                GestorTareas.detenerProgramador()
                                programadorActivo = false
                                scope.launch { snackbarHostState.showSnackbar("Programador detenido") }
                            } else {
                                GestorTareas.iniciarProgramador()
                                programadorActivo = true
                                scope.launch { snackbarHostState.showSnackbar("Programador iniciado") }
                            }
                        }) { Text(if (programadorActivo) "Detener" else "Iniciar") }

                        OutlinedButton(onClick = { LogFiles.abrirCarpetaLogs() }) {
                            Text("Abrir carpeta de logs")
                        }
                    }

                    Divider()

                    // --- LISTA DE TAREAS ---
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tareas, key = { it.id }) { t ->
                            TareaRow(
                                t = t,
                                onInfo = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TareaRow(
    t: Tarea,
    onInfo: (String) -> Unit
) {
    var verLog by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var editNombre by remember { mutableStateOf(t.nombre) }
    var editComando by remember { mutableStateOf(t.comando) }
    var editIntervalo by remember { mutableStateOf(t.intervalo.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Texto DESCRIPCIÓN (ocupa el espacio disponible) + pill (no se parte)
            val ultima = t.ultimaEjecucion?.format(HHMMSS) ?: "-"
            Text(
                "${t.id}. ${t.nombre} | cmd: '${t.comando}' | cada ${t.intervalo}s | última: $ultima | ",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            EstadoPill(t.estado)

            // Ejecutar (deshabilitado si ya está ejecutando)
            Button(
                onClick = {
                    GestorTareas.ejecutarTarea(t.id)
                    onInfo("Ejecución lanzada: ${t.nombre}")
                },
                enabled = t.estado != EstadoTarea.EJECUTANDO
            ) { Text("Ejecutar") }

            // Cancelar solo cuando está ejecutando
            if (t.estado == EstadoTarea.EJECUTANDO) {
                OutlinedButton(onClick = {
                    val ok = GestorTareas.cancelarEjecucion(t.id)
                    onInfo(if (ok) "Ejecución cancelada (#${t.id})" else "No hay ejecución activa")
                }) { Text("Cancelar") }
            }

            OutlinedButton(onClick = { verLog = true }) { Text("Ver log") }
            OutlinedButton(onClick = {
                editNombre = t.nombre
                editComando = t.comando
                editIntervalo = t.intervalo.toString()
                editOpen = true
            }) { Text("Editar") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Eliminar") }
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
                confirmButton = { TextButton(onClick = { verLog = false }) { Text("Cerrar") } }
            )
        }

        if (editOpen) {
            val intervaloVal = editIntervalo.toLongOrNull() ?: -1
            val valido = editNombre.isNotBlank() && editComando.isNotBlank() && intervaloVal >= 0

            AlertDialog(
                onDismissRequest = { editOpen = false },
                title = { Text("Editar tarea #${t.id}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editNombre, onValueChange = { editNombre = it },
                            label = { Text("Nombre") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = editComando, onValueChange = { editComando = it },
                            label = { Text("Comando o script") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = editIntervalo,
                            onValueChange = { editIntervalo = it.filter(Char::isDigit) },
                            label = { Text("Intervalo (s)") }, singleLine = true
                        )
                        if (!valido) {
                            Text("Completa todos los campos (intervalo ≥ 0).", color = MaterialTheme.colors.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            GestorTareas.actualizarTarea(
                                t.id,
                                editNombre.trim(),
                                editComando.trim(),
                                (editIntervalo.toLongOrNull() ?: 0L)
                            )
                            editOpen = false
                            onInfo("Tarea actualizada: ${t.id}")
                        },
                        enabled = valido
                    ) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { editOpen = false }) { Text("Cancelar") } }
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Eliminar tarea") },
                text = { Text("¿Seguro que quieres eliminar '${t.nombre}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        GestorTareas.eliminarTarea(t.id)
                        confirmDelete = false
                        onInfo("Tarea eliminada: ${t.nombre}")
                    }) { Text("Eliminar") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
            )
        }
    }
}

/** Pill de color para el estado de la tarea */
@Composable
private fun EstadoPill(estado: EstadoTarea) {
    val (bg, fg) = when (estado) {
        EstadoTarea.PENDIENTE   -> Color(0xFFE3F2FD) to Color(0xFF0D47A1)  // azul claro
        EstadoTarea.EJECUTANDO  -> Color(0xFFFFF3E0) to Color(0xFFE65100)  // naranja
        EstadoTarea.FINALIZADA  -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)  // verde
        EstadoTarea.ERROR       -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)  // rojo
    }
    Text(
        text = estado.name,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
