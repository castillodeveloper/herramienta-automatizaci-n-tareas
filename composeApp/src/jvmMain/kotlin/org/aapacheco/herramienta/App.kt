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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun App() {
    MaterialTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        var nombre by rememberSaveable { mutableStateOf("") }
        var comando by rememberSaveable { mutableStateOf("") }
        var intervaloTxt by rememberSaveable { mutableStateOf("0") }
        var programadorActivo by rememberSaveable { mutableStateOf(false) }

        // Observa snapshots inmutables
        val tareas by GestorTareas.estadoTareas.collectAsState()

        LaunchedEffect(Unit) { GestorTareas.cargarDesdeDisco() }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.width(980.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Herramienta de Automatización de Tareas", style = MaterialTheme.typography.h6)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = nombre, onValueChange = { nombre = it },
                            label = { Text("Nombre") }, singleLine = true,
                            modifier = Modifier.width(240.dp)
                        )
                        OutlinedTextField(
                            value = comando, onValueChange = { comando = it },
                            label = { Text("Comando") },
                            placeholder = { Text("p. ej.  dir   /   echo Hola") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = intervaloTxt, onValueChange = { intervaloTxt = it.filter(Char::isDigit) },
                            label = { Text("Intervalo (s)") }, singleLine = true,
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

                    // Aviso si parece comando "infinito" y el intervalo es 0
                    val intervaloL = intervaloTxt.toLongOrNull() ?: 0L
                    if (comando.isNotBlank() && GestorTareas.requiereIntervalo(comando) && intervaloL == 0L) {
                        Text(
                            "Este comando suele no terminar. Indica un intervalo > 0 para evitar que quede colgado.",
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                        )
                    }

                    Divider()

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
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
    t: TareaUI,
    onInfo: (String) -> Unit
) {
    var verLog by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var editNombre by remember { mutableStateOf(t.nombre) }
    var editComando by remember { mutableStateOf(t.comando) }
    var editIntervalo by remember { mutableStateOf(t.intervalo.toString()) }
    var editTimeout by remember { mutableStateOf(t.timeoutS.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val ultima = t.ultimaEjecucion?.format(HHMMSS) ?: "-"
            Text(
                "${t.id}. ${t.nombre} | cmd: '${t.comando}' | cada ${t.intervalo}s | última: $ultima | ",
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            EstadoPill(t.estado)

            // Habilitada (afecta botón y scheduler)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Habilitada", fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Switch(
                    checked = t.habilitada,
                    onCheckedChange = { GestorTareas.actualizarHabilitada(t.id, it) }
                )
            }

            Button(
                onClick = { GestorTareas.ejecutarTarea(t.id); onInfo("Ejecución lanzada: ${t.nombre}") },
                enabled = t.estado != EstadoTarea.EJECUTANDO && t.habilitada
            ) { Text("Ejecutar") }

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
                editTimeout = t.timeoutS.toString()
                editOpen = true
            }) { Text("Editar") }
            OutlinedButton(onClick = {
                GestorTareas.duplicarTarea(t.id)
                onInfo("Tarea duplicada")
            }) { Text("Duplicar") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Eliminar") }
        }

        if (verLog) {
            AlertDialog(
                onDismissRequest = { verLog = false },
                title = { Text("Salida de: ${t.nombre}") },
                text = {
                    Box(Modifier.width(700.dp).height(300.dp).verticalScroll(rememberScrollState())) {
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
            val timeoutVal = editTimeout.toLongOrNull() ?: -1
            val valido = editNombre.isNotBlank() && editComando.isNotBlank() && intervaloVal >= 0 && timeoutVal >= 0

            AlertDialog(
                onDismissRequest = { editOpen = false },
                title = { Text("Editar tarea #${t.id}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(editNombre, { editNombre = it }, label = { Text("Nombre") }, singleLine = true)
                        OutlinedTextField(editComando, { editComando = it }, label = { Text("Comando o script") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                editIntervalo, { editIntervalo = it.filter(Char::isDigit) },
                                label = { Text("Intervalo (s)") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                editTimeout, { editTimeout = it.filter(Char::isDigit) },
                                label = { Text("Timeout (s) 0=sin límite") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                        if (!valido) Text("Completa todos los campos (valores ≥ 0).", color = MaterialTheme.colors.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            GestorTareas.actualizarTarea(
                                t.id, editNombre.trim(), editComando.trim(), (editIntervalo.toLongOrNull() ?: 0L)
                            )
                            GestorTareas.actualizarTimeout(t.id, (editTimeout.toLongOrNull() ?: 0L))
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

@Composable
private fun EstadoPill(estado: EstadoTarea) {
    val (bg, fg) = when (estado) {
        EstadoTarea.PENDIENTE   -> Color(0xFFE3F2FD) to Color(0xFF0D47A1)
        EstadoTarea.EJECUTANDO  -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        EstadoTarea.FINALIZADA  -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        EstadoTarea.ERROR       -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)
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
