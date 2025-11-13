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
                            value = intervaloTxt,
                            onValueChange = { intervaloTxt = it.filter(Char::isDigit) },
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
    var confirmDelete by remember { mutableStateOf(false) }

    var editNombre by remember { mutableStateOf(t.nombre) }
    var editComando by remember { mutableStateOf(t.comando) }
    var editIntervalo by remember { mutableStateOf(t.intervalo.toString()) }
    var editHabilitada by remember { mutableStateOf(t.habilitada) }
    var editCwd by remember { mutableStateOf(t.cwd) }
    var editTimeout by remember { mutableStateOf((t.timeout ?: 0L).toString()) }
    var editEnvText by remember { mutableStateOf(envToLines(t.env)) }

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Habilitada", modifier = Modifier.padding(end = 6.dp))
                Switch(
                    checked = t.habilitada,
                    onCheckedChange = {
                        GestorTareas.toggleHabilitada(t.id, it)
                        onInfo(if (it) "Tarea habilitada" else "Tarea deshabilitada")
                    }
                )
            }

            // === Botones ===
            val isRunning = GestorTareas.estaEjecutando(t.id)

            Button(
                onClick = { GestorTareas.ejecutarTarea(t.id); onInfo("Ejecución lanzada: ${t.nombre}") },
                enabled = !isRunning && t.habilitada
            ) { Text("Ejecutar") }

            OutlinedButton(
                onClick = {
                    val ok = GestorTareas.cancelarEjecucion(t.id)
                    onInfo(if (ok) "Ejecución cancelada (#${t.id})" else "No hay ejecución activa")
                },
                enabled = isRunning
            ) { Text("Cancelar") }

            OutlinedButton(onClick = { verLog = true }) { Text("Ver log") }
            OutlinedButton(onClick = {
                editNombre = t.nombre
                editComando = t.comando
                editIntervalo = t.intervalo.toString()
                editHabilitada = t.habilitada
                editCwd = t.cwd
                editTimeout = (t.timeout ?: 0L).toString()
                editEnvText = envToLines(t.env)
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
            val timeoutVal = editTimeout.toLongOrNull() ?: 0L
            val valido = editNombre.isNotBlank() && editComando.isNotBlank() && intervaloVal >= 0

            AlertDialog(
                onDismissRequest = { editOpen = false },
                title = { Text("Editar tarea #${t.id}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(editNombre, { editNombre = it }, label = { Text("Nombre") }, singleLine = true)
                        OutlinedTextField(editComando, { editComando = it }, label = { Text("Comando o script") }, singleLine = true)
                        OutlinedTextField(
                            editIntervalo, { editIntervalo = it.filter(Char::isDigit) },
                            label = { Text("Intervalo (s)") }, singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editHabilitada, onCheckedChange = { editHabilitada = it })
                            Text("Habilitada", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedTextField(editCwd, { editCwd = it }, label = { Text("Directorio de trabajo (opcional)") }, singleLine = true)
                        OutlinedTextField(
                            editTimeout, { editTimeout = it.filter(Char::isDigit) },
                            label = { Text("Timeout (s) — vacío/0 = sin límite") }, singleLine = true
                        )
                        OutlinedTextField(
                            editEnvText, { editEnvText = it },
                            label = { Text("Variables de entorno (KEY=VALUE, una por línea)") },
                            singleLine = false, modifier = Modifier.height(120.dp)
                        )
                        if (!valido) Text("Completa todos los campos (intervalo ≥ 0).", color = MaterialTheme.colors.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val envMap = parseEnvLines(editEnvText)
                            GestorTareas.actualizarTareaFull(
                                id = t.id,
                                nombre = editNombre.trim(),
                                comando = editComando.trim(),
                                intervalo = (editIntervalo.toLongOrNull() ?: 0L),
                                habilitada = editHabilitada,
                                cwd = editCwd.trim(),
                                timeout = (editTimeout.toLongOrNull() ?: 0L).takeIf { it > 0 },
                                env = envMap
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

@Composable
private fun EstadoPill(estado: EstadoTarea) {
    val (bg, fg) = when (estado) {
        EstadoTarea.PENDIENTE -> Color(0xFFE3F2FD) to Color(0xFF0D47A1)
        EstadoTarea.EJECUTANDO -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        EstadoTarea.FINALIZADA -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        EstadoTarea.ERROR -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)
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

private fun parseEnvLines(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) null else {
            val idx = trimmed.indexOf('=')
            if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
        }
    }.toMap()

private fun envToLines(env: Map<String, String>): String =
    env.entries.joinToString("\n") { "${it.key}=${it.value}" }
