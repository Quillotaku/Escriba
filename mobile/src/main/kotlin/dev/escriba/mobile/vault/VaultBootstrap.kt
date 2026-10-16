package dev.escriba.mobile.vault

import dev.escriba.shared.vault.DailyNote

/**
 * Estructura inicial de una bóveda para Escriba. Solo crea lo que falta: nunca sobrescribe
 * algo que el usuario ya tenga.
 */
object VaultBootstrap {

    const val CONFIG_DIR = "_config/escriba"
    const val PROFILES_DIR = "$CONFIG_DIR/perfiles"
    const val TEMPLATES_DIR = "$CONFIG_DIR/plantillas"
    const val CONFIG_FILE = "$CONFIG_DIR/escriba.md"
    const val ERRORS_FILE = "$CONFIG_DIR/errores.md"
    const val DAILY_TEMPLATE = "$TEMPLATES_DIR/diaria.md"

    /**
     * Plantilla de la nota diaria. Se monta línea a línea a propósito: con la consulta interpolada
     * dentro de un texto sangrado, `trimIndent` no quitaba la sangría y Obsidian lo mostraba
     * como bloque de código en vez de ejecutar Dataview (error de la primera versión).
     */
    private fun dailyTemplate(): String =
        listOf("# {{date}}", "", "## Grabaciones", DailyNote.DATAVIEW_QUERY, "", "## Notas", "").joinToString("\n")

    val folders = listOf("Diario", "Personas", "Proyectos", "Clientes", "Grabaciones", "Adjuntos/Escriba", PROFILES_DIR, TEMPLATES_DIR)

    val files: Map<String, String> = linkedMapOf(
        CONFIG_FILE to """
            ---
            version: 1
            horario: "07:30-23:30"
            zona_horaria: Europe/Madrid
            perfil_por_defecto: casual
            whisper_por_defecto: large-v3-turbo-q5_0
            nota_diaria_carpeta: Diario
            nota_diaria_formato: YYYY-MM-DD
            carpeta_personas: Personas
            carpeta_proyectos: Proyectos
            carpeta_clientes: Clientes
            carpeta_adjuntos: Adjuntos/Escriba
            retencion_audio_movil: 7d
            ---
            # Configuración de Escriba

            Ajustes globales. Los perfiles están en `perfiles/` (un archivo por perfil) y las
            plantillas de nota en `plantillas/`. Si algo está mal escrito, Escriba lo explica en
            `errores.md` y sigue usando el resto.

            - **horario**: franja en la que el modo continuo graba (fuera de ella, el reloj descansa).
            - **retencion_audio_movil**: días que el audio se queda en el móvil tras escribir la nota.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/casual.md" to """
            ---
            nombre: Casual
            color: "#1E88E5"
            icono: casa
            favorito: true
            modo: continuo
            vad: media
            separar_tras_silencio: 10m
            carpeta: Grabaciones/{{yyyy}}/{{MM}}
            idioma: es
            descartar_triviales: true
            guardar_audio: false
            nota_diaria: dataview
            ---
            ## Prompt
            Resume en 3–5 frases de qué se ha hablado. Tono neutro, en español. Sin tareas.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/trabajo.md" to """
            ---
            nombre: Trabajo
            color: "#2E7D32"
            icono: maletin
            favorito: true
            modo: bajo_demanda
            vad: media
            separar_tras_silencio: 15m
            carpeta: Trabajo/{{yyyy}}/{{MM}}
            plantilla: _config/escriba/plantillas/acta.md
            idioma: es
            diarizacion: true
            tareas: true
            enlazar:
              - personas
              - proyectos
              - clientes
            guardar_audio: true
            retencion_audio: 90d
            redondeo: 5m
            nota_diaria: dataview
            ---
            > Para un cliente concreto, crea `trabajo-acme.md` con `hereda: trabajo`,
            > `cliente: "[[Acme]]"` y `carpeta: Clientes/Acme/Reuniones`. Hereda todo lo demás.

            ## Prompt
            Redacta un acta de la reunión con: asistentes, temas tratados, decisiones, riesgos y
            próximos pasos. Extrae las tareas con responsable y fecha cuando se mencionen.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/ideas.md" to """
            ---
            nombre: Ideas
            color: "#F9A825"
            icono: idea
            modo: bajo_demanda
            vad: alta
            separar_tras_silencio: 5m
            carpeta: Ideas
            idioma: es
            guardar_audio: false
            nota_diaria: dataview
            ---
            ## Prompt
            Devuelve la transcripción limpia (sin muletillas ni repeticiones) y 3–5 etiquetas.
            No resumas.
        """.trimIndent() + "\n",

        "$TEMPLATES_DIR/acta.md" to """
            # {{titulo}}

            **Cliente:** {{cliente}} · **Duración:** {{duracion}} min · **Participantes:** {{participantes}}

            ## Resumen
            {{resumen}}

            {{tareas}}

            {{audio}}

            ## Transcripción
            {{transcripcion}}
        """.trimIndent() + "\n",

        DAILY_TEMPLATE to dailyTemplate(),

        "$CONFIG_DIR/LEEME.md" to """
            # Escriba

            Escriba escribe aquí una nota por cada conversación que graba el reloj. Todo se procesa
            en el móvil; nada sale a la nube.

            ## Nota diaria
            Activa el plugin **Notas diarias** de Obsidian con la carpeta `Diario`, el formato
            `YYYY-MM-DD` y la plantilla `_config/escriba/plantillas/diaria.md`. La tabla de
            grabaciones necesita el plugin **Dataview**.

            ## Perfiles
            Cada archivo de `perfiles/` es un perfil. Puedes editarlos aquí o desde la app.
            Propiedades: `nombre`, `hereda`, `color`, `icono` (mic, maletin, idea, casa, persona,
            grupo, telefono, coche, libro, estrella), `favorito`, `modo` (continuo, bajo_demanda),
            `vad` (baja, media, alta), `separar_tras_silencio`, `carpeta`, `plantilla`, `whisper`,
            `idioma` (es, en, auto), `vocabulario`, `diarizacion`, `tareas`, `enlazar`
            (personas, proyectos, clientes), `descartar_triviales`, `guardar_audio`,
            `retencion_audio` (p. ej. 30d o siempre), `redondeo`, `nota_diaria`
            (dataview, enlace, ninguna) y `cliente`. El prompt va en la sección `## Prompt`.

            ## Plantillas
            Variables: `{{titulo}}`, `{{resumen}}`, `{{tareas}}`, `{{audio}}`, `{{transcripcion}}`,
            `{{fecha}}`, `{{inicio}}`, `{{fin}}`, `{{duracion}}`, `{{perfil}}`, `{{cliente}}`,
            `{{participantes}}`. El frontmatter lo genera siempre Escriba.
        """.trimIndent() + "\n",
    )

    /**
     * Corrige archivos que creó una versión anterior con errores, solo si siguen tal cual se
     * crearon (si el usuario los ha cambiado, no se tocan). Devuelve lo que se ha reparado.
     */
    fun repair(vault: VaultAccess): List<String> {
        val repaired = ArrayList<String>()
        val daily = vault.readText(DAILY_TEMPLATE)
        if (daily != null && daily.startsWith("            # {{date}}") && "```dataview" in daily && daily.lines().size <= 12) {
            vault.writeText(DAILY_TEMPLATE, dailyTemplate())
            repaired += DAILY_TEMPLATE
        }
        return repaired
    }

    /** Lo que falta por crear en la bóveda. */
    fun missing(vault: VaultAccess): List<String> =
        folders.filterNot(vault::exists) + files.keys.filterNot(vault::exists)

    /** Crea lo que falta. Devuelve las rutas creadas. */
    fun apply(vault: VaultAccess): List<String> {
        val created = ArrayList<String>()
        folders.filterNot(vault::exists).forEach {
            vault.ensureDir(it)
            created += "$it/"
        }
        files.filterKeys { !vault.exists(it) }.forEach { (path, content) ->
            vault.writeText(path, content)
            created += path
        }
        return created
    }
}
