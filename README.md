# Proyecto 2: Herramienta de Automatización de Tareas

Aplicación desarrollada en Kotlin utilizando Compose for Desktop** para la interfaz gráfica y ProcessBuilder para la ejecución de procesos concurrentes.  
Forma parte del módulo Programación de Servicios y Procesos (PSP)** del ciclo Desarrollo de Aplicaciones Multiplataforma (DAM).

---

## Descripción

La herramienta permite a los usuarios definir, ejecutar y gestionar tareas automatizadas mediante scripts o comandos del sistema.  
Cada tarea se ejecuta en un proceso independiente, lo que permite realizar operaciones de forma concurrente y controlada, sin bloquear la interfaz principal.

El objetivo del proyecto es comprender y aplicar los conceptos de procesos, concurrencia y comunicación con el sistema operativo desde Kotlin.

---

## Requisitos del proyecto

- Permitir al usuario crear, editar y eliminar tareas.
- Cada tarea puede ser un comando del sistema o un script (por ejemplo `.bat` o `.sh`).
- Permitir programar la ejecución de tareas en intervalos regulares (cada hora, diariamente, etc.).
- Registrar la salida y los errores de cada tarea en un archivo de log.
- Incluir una interfaz gráfica para gestionar las tareas y ver los resultados.
- Utilizar `ProcessBuilder` para ejecutar los comandos definidos por el usuario.
- Implementar manejo de errores (por ejemplo, comandos inválidos o fallos de ejecución).
- Documentar el código y la lógica utilizada.

---

## ️ Tecnologías utilizadas

| Tecnología | Uso principal |
|-------------|----------------|
| Kotlin | Lógica del programa |
| Compose for Desktop | Interfaz gráfica moderna |
| Coroutines | Concurrencia y temporización |
| ProcessBuilder | Ejecución de comandos y scripts del sistema |
| Gradle Kotlin DSL | Sistema de construcción del proyecto |

---

##  Funcionalidades principales

- Crear y guardar tareas personalizadas.
- Ejecutar tareas manualmente o de forma programada.
- Mostrar la salida y los errores de ejecución.
- Registrar logs con los resultados de cada proceso.
- Permitir eliminar o modificar tareas existentes.
- Interfaz moderna y responsiva desarrollada con Compose.

---

##  Ejecución del proyecto

Desde IntelliJ IDEA o la terminal:

```bash
./gradlew :composeApp:run
