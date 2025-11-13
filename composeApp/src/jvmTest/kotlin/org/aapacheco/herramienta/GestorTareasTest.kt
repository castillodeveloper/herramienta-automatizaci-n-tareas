package org.aapacheco.herramienta

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests unitarios básicos para GestorTareas.
 */
class GestorTareasTest {

    @BeforeTest
    fun setup() {
        // Limpiamos el estado entre tests pero SIN matar el appScope
        GestorTareas.resetForTests()
    }

    @Test
    fun agregarTarea_crea_una_nueva_tarea_correctamente() {
        // Act
        val tarea = GestorTareas.agregarTarea("Prueba", "echo Hola", 5)
        val snapshot = GestorTareas.getTareaUI(tarea.id)

        // Assert
        assertNotNull(snapshot, "La tarea debería existir en el estado actual")
        assertEquals("Prueba", snapshot.nombre)
        assertEquals("echo Hola", snapshot.comando)
        assertEquals(5, snapshot.intervalo)
        assertTrue(snapshot.habilitada, "Las tareas nuevas deben estar habilitadas por defecto")
    }

    @Test
    fun actualizarTareaFull_modifica_correctamente_todos_los_campos() {
        // Arrange
        val tarea = GestorTareas.agregarTarea("Demo", "echo X", 1)
        val env = mapOf("VAR" to "123")

        // Act
        val ok = GestorTareas.actualizarTareaFull(
            id = tarea.id,
            nombre = "DemoEdit",
            comando = "echo Y",
            intervalo = 10,
            habilitada = false,
            cwd = "C:/",
            timeout = 30,
            env = env
        )

        // Assert
        assertTrue(ok, "La actualización debería devolver true")

        val snapshot = GestorTareas.getTareaUI(tarea.id)
        assertNotNull(snapshot)
        assertEquals("DemoEdit", snapshot.nombre)
        assertEquals("echo Y", snapshot.comando)
        assertEquals(10, snapshot.intervalo)
        assertFalse(snapshot.habilitada)
        assertEquals("C:/", snapshot.cwd)
        assertEquals(30, snapshot.timeout)
        assertEquals("123", snapshot.env["VAR"])
    }

    @Test
    fun ejecutarTarea_ejecuta_comando_simple_y_cambia_estado_a_FINALIZADA() = runBlocking {
        // Arrange
        val tarea = GestorTareas.agregarTarea("Echo", "echo TestUnit", 0)

        // **IMPORTANTE**: lanzamos la ejecución
        GestorTareas.ejecutarTarea(tarea.id)

        // Act: esperamos de forma cooperativa a que cambie el estado
        val maxWaitMs = 5000L
        val stepMs = 200L
        var waited = 0L

        while (waited < maxWaitMs) {
            val snap = GestorTareas.getTareaUI(tarea.id)
            // Salimos cuando deja de estar EJECUTANDO (ha terminado en OK o ERROR)
            if (snap != null && snap.estado != EstadoTarea.EJECUTANDO) break
            delay(stepMs)
            waited += stepMs
        }

        // Assert
        val snapshot = GestorTareas.getTareaUI(tarea.id)
        assertNotNull(snapshot, "La tarea debe existir")
        assertEquals(
            EstadoTarea.FINALIZADA,
            snapshot.estado,
            "La tarea debería terminar en estado FINALIZADA"
        )
        assertTrue(
            snapshot.salida.contains("TestUnit", ignoreCase = true),
            "La salida debe contener el texto del echo"
        )
    }

    @Test
    fun cancelarEjecucion_devuelve_false_si_no_hay_proceso_activo() {
        val ok = GestorTareas.cancelarEjecucion(9999)
        assertFalse(ok, "Si no hay proceso activo, cancelarEjecucion debe devolver false")
    }
}
