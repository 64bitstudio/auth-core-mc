# 000 — Directiva de documentación y flujo de tareas (CONFIG GLOBAL)

**Origen:** instrucción del Product Owner, aplica a este proyecto y a todos los futuros.

## Qué se pidió
- Carpetas `/pending`, `/in-process`, `/done` por proyecto para rastrear tareas como archivos que se mueven de carpeta según su estado.
- Carpeta `/docs` con 5 archivos vivos, actualizados como último paso obligatorio al mover una tarea a `/done`:
  - `README.md` — guía de instalación/arranque a prueba de fallos, incluyendo dónde tocar parámetros como `primary_color` / `app_name`.
  - `ARQUITECTURA.md` — cómo se comunican las partes del sistema, explicado pedagógicamente.
  - `BASE_DE_DATOS.md` — esquema de tablas, relaciones, para qué sirve cada campo.
  - `API.md` — endpoints, métodos, entradas/salidas, en lenguaje simple.
  - `COMPONENTES.md` — estructura de pantallas/componentes de la UI.
- Redacción clara, sin dar nada por sentado, explicando el *por qué* de las decisiones técnicas, no solo el *cómo*.

## Por qué importa
El Product Owner necesita poder entender el sistema a profundidad sin depender de que el equipo se lo explique verbalmente cada vez, y necesita que la documentación nunca quede desactualizada respecto al código.

## Hecho en esta tarea
- Creada la estructura `/pending /in-process /done /docs` en este proyecto.
- Creados los 5 archivos de `/docs` con el estado inicial (arquitectura ya decidida documentada; el resto como placeholder hasta que existan componentes reales que documentar).
- Guardada como memoria persistente para que futuras sesiones sigan esta misma convención en cualquier proyecto nuevo, no solo en este.
