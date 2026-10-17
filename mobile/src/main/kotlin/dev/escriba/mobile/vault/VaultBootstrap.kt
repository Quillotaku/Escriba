package dev.escriba.mobile.vault

import dev.escriba.shared.vault.DailyNote

/**
 * Initial vault structure for Escriba. It only creates what's missing: it never overwrites
 * anything the user already has.
 */
object VaultBootstrap {

    const val CONFIG_DIR = "_config/escriba"
    const val PROFILES_DIR = "$CONFIG_DIR/profiles"
    const val TEMPLATES_DIR = "$CONFIG_DIR/templates"
    const val CONFIG_FILE = "$CONFIG_DIR/escriba.md"
    const val ERRORS_FILE = "$CONFIG_DIR/errors.md"
    const val DAILY_TEMPLATE = "$TEMPLATES_DIR/daily.md"

    /**
     * Daily note template. It's assembled line by line on purpose: with the query interpolated
     * inside an indented text block, `trimIndent` didn't remove the indentation and Obsidian showed
     * it as a code block instead of running Dataview.
     */
    private fun dailyTemplate(): String =
        listOf("# {{date}}", "", "## Recordings", DailyNote.DATAVIEW_QUERY, "", "## Notes", "").joinToString("\n")

    val folders = listOf("Daily", "People", "Projects", "Clients", "Recordings", "Attachments/Escriba", PROFILES_DIR, TEMPLATES_DIR)

    val files: Map<String, String> = linkedMapOf(
        CONFIG_FILE to """
            ---
            version: 1
            schedule: "07:30-23:30"
            time_zone: Europe/Madrid
            default_profile: personal
            default_whisper: large-v3-turbo-q8_0
            default_llm: gemma-3-4b-it-Q4_K_M
            daily_note_folder: Daily
            daily_note_format: YYYY-MM-DD
            people_folder: People
            projects_folder: Projects
            clients_folder: Clients
            attachments_folder: Attachments/Escriba
            phone_audio_retention: 7d
            ---
            # Escriba settings

            Global settings. Profiles live in `profiles/` (one file per profile) and note templates in
            `templates/`. If something is misspelled, Escriba explains it in `errors.md` and keeps
            using the rest.

            - **schedule**: the window in which continuous mode records (outside it, the watch rests).
            - **phone_audio_retention**: days the audio stays on the phone after the note is written.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/personal.md" to """
            ---
            name: Personal
            color: "#1E88E5"
            icon: home
            favorite: true
            mode: continuous
            vad: medium
            split_after_silence: 10m
            folder: Recordings/{{yyyy}}/{{MM}}
            language: auto
            discard_trivial: true
            save_audio: false
            daily_note: dataview
            ---
            ## Prompt
            Summarize in 3–5 sentences what was talked about. Neutral tone. No tasks.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/work.md" to """
            ---
            name: Work
            color: "#2E7D32"
            icon: briefcase
            favorite: true
            mode: on_demand
            vad: medium
            split_after_silence: 15m
            folder: Work/{{yyyy}}/{{MM}}
            template: _config/escriba/templates/minutes.md
            language: auto
            diarization: true
            tasks: true
            link:
              - people
              - projects
              - clients
            save_audio: true
            audio_retention: 90d
            rounding: 1m
            daily_note: dataview
            ---
            > For a specific client, create `work-acme.md` with `inherits: work`,
            > `client: "[[Acme]]"` and `folder: Clients/Acme/Meetings`. It inherits everything else.

            ## Prompt
            Write the minutes of the meeting with: attendees, topics discussed, decisions, risks and
            next steps. Extract the tasks with owner and date when they're mentioned.
        """.trimIndent() + "\n",

        "$PROFILES_DIR/ideas.md" to """
            ---
            name: Ideas
            color: "#F9A825"
            icon: idea
            mode: on_demand
            vad: high
            split_after_silence: 5m
            folder: Ideas
            language: auto
            save_audio: false
            daily_note: dataview
            ---
            ## Prompt
            Return the clean transcript (no filler words or repetitions) and 3–5 tags.
            Don't summarize.
        """.trimIndent() + "\n",

        "$TEMPLATES_DIR/minutes.md" to """
            # {{title}}

            **Client:** {{client}} · **Duration:** {{duration}} min · **Participants:** {{participants}}

            ## Summary
            {{summary}}

            {{tasks}}

            {{audio}}

            ## Transcript
            {{transcript}}
        """.trimIndent() + "\n",

        DAILY_TEMPLATE to dailyTemplate(),

        "$CONFIG_DIR/README.md" to """
            # Escriba

            Escriba writes a note here for every conversation the watch records. Everything is
            processed on the phone; nothing goes to the cloud.

            ## Daily note
            Turn on Obsidian's **Daily notes** plugin with the `Daily` folder, the `YYYY-MM-DD`
            format and the `_config/escriba/templates/daily.md` template. The recordings table needs
            the **Dataview** plugin.

            ## Profiles
            Each file in `profiles/` is a profile. You can edit them here or from the app.
            Properties: `name`, `inherits`, `color`, `icon` (mic, briefcase, idea, home, person,
            group, phone, car, book, star), `favorite`, `mode` (continuous, on_demand),
            `vad` (low, medium, high), `split_after_silence`, `folder`, `template`, `engine`
            (whisper, parakeet), `whisper`, `llm`, `language` (auto, en, es), `vocabulary`,
            `diarization`, `tasks`, `link` (people, projects, clients), `discard_trivial`,
            `save_audio`, `audio_retention` (e.g. 30d or forever), `rounding`, `daily_note`
            (dataview, link, none) and `client`. The prompt goes in the `## Prompt` section.

            ## Templates
            Variables: `{{title}}`, `{{summary}}`, `{{tasks}}`, `{{audio}}`, `{{transcript}}`,
            `{{date}}`, `{{start}}`, `{{end}}`, `{{duration}}`, `{{profile}}`, `{{client}}`,
            `{{participants}}`. Escriba always generates the frontmatter.
        """.trimIndent() + "\n",
    )

    /** What's still missing from the vault. */
    fun missing(vault: VaultAccess): List<String> =
        folders.filterNot(vault::exists) + files.keys.filterNot(vault::exists)

    /** Creates what's missing. Returns the created paths. */
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
