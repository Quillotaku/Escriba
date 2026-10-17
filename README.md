# Escriba

**Record on the watch, transcribe and summarize on the phone, keep it in your Obsidian vault.**

Escriba turns a Wear OS watch and an Android phone into a Plaud-style conversation recorder, but
**everything is local**: the watch records, the phone transcribes with Whisper or Parakeet, tells
who's speaking, summarizes with an LLM and writes each conversation as an Obsidian note with its
summary, decisions, tasks and transcript. No Internet, no accounts and no cloud.

> Version 1.1.0 · Android 15+ (arm64) and Wear OS 5+ · English interface

## What it does

- **Records from the wrist**: the watch app's button, the Tile or the complication. Continuous mode
  with a schedule (07:30–23:30 by default) or on demand. Speech detector: only what has speech is
  kept.
- **Sends to the phone end-to-end encrypted** over Bluetooth (Wearable Data Layer, only to directly
  connected devices; never through the cloud).
- **Transcribes on the phone** with whisper.cpp (`large-v3-turbo`) or Parakeet TDT v3 (about 10
  times faster).
- **Separates speakers and recognizes the voices** you enroll ("Me", "Ana López"…). Voiceprints only
  live in the phone's private database.
- **Summarizes with a local LLM** (Gemma 3 4B by default): title, summary, decisions, tasks with
  owner and due date, and links to people, projects and clients that already exist in your vault.
  It writes in the language of the conversation.
- **Writes Obsidian notes** with Dataview-ready frontmatter, one per conversation (split by
  silences), and links them from the daily note.
- **Profiles** (work, a client, ideas…) as vault files, with inheritance, editable from Obsidian or
  from the app.
- **Dashboard** with recorded time per profile, tasks and model usage.

## Privacy

- Neither app has the Internet permission.
- Watch and phone pair with a 6-digit code (P-256 ECDH with a commitment) and everything that
  travels between them is encrypted with AES-256-GCM. The key is protected by the Android Keystore.
- Android backups are disabled.
- Audio is deleted from the phone 7 days after the note is written (configurable) and, if the
  profile saves it in the vault, after the days the profile says.
- Exported components are protected: only the system can bind the Tile and the complication, and
  the watch listener only accepts encrypted messages from the paired phone.

## Requirements

| | Minimum | Tested with |
|---|---|---|
| Phone | Android 15 (API 35), arm64, 8 GB of RAM | Honor Magic 8 Pro (Android 16, MagicOS, 12 GB) |
| Watch | Wear OS 5 (API 34) with a microphone | Xiaomi Watch 5 (Wear OS 6, HyperOS) |
| Obsidian | Any version; the Dataview plugin for the daily note | Any sync tool works |
| Storage | ~4 GB for the recommended models | |

Performance measured on the Honor Magic 8 Pro: Whisper `large-v3-turbo-q8_0` transcribes at
×0.26–0.31 of real time (an hour of speech in about 16–19 min), Parakeet at ×0.03, and Gemma 3 4B
reads about 64 tokens/s and writes about 15 tokens/s.

## Installation

### Download

Each [release](https://github.com/Quillotaku/Escriba/releases) has two APKs, `escriba-phone-<version>.apk`
and `escriba-watch-<version>.apk`, with their SHA-256. Both are signed with the same key, which the
Data Layer requires. The watch app isn't in the Play Store: it's installed with `adb` (see
[Install](#4-install) below). To build them yourself, follow the steps below.

### Building from source

#### 1. Tools

- A recent **Android Studio** (its JDK, the JBR, is the one used to build).
- **Android SDK 37**, **NDK 30.0.16248370** and **CMake 4.1.2** (SDK Manager → SDK Tools).
- **git** and **adb** (it comes with the SDK).

#### 2. Code

```bash
git clone --recurse-submodules https://github.com/Quillotaku/Escriba.git escriba
cd escriba
```

whisper.cpp and llama.cpp are submodules (`native/whisper.cpp`, `native/llama.cpp`). If you cloned
without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

#### 3. Build

```bash
./gradlew :mobile:assembleRelease :wear:assembleRelease
```

The first time, Gradle downloads the sherpa-onnx AAR from its official releases and checks its
SHA-256 (the `:mobile:downloadSherpaOnnx` task).

Release builds are signed with the key described in a `keystore.properties` file at the project
root, which never goes in the repository:

```properties
storeFile=C:/path/to/escriba-release.jks
storePassword=…
keyAlias=escriba
keyPassword=…
```

Without that file they're signed with your debug key, which is fine for trying it out. Either way,
**the phone and the watch must be signed with the same key**: the Data Layer only connects apps with
the same id (`dev.escriba`) and the same signature. An app signed with another key can't be updated
over the installed one: it has to be uninstalled first, which deletes its data.

#### 4. Install

Turn on wireless debugging on the phone and the watch (Developer options) and:

```bash
adb -s <phone> install -r mobile/build/outputs/apk/release/mobile-release.apk
adb -s <watch> install -r wear/build/outputs/apk/release/wear-release.apk
```

## The models

The models don't go inside the app: they're left in a folder on the phone (e.g. `Download/models`)
and that folder is chosen in the app. The files must have **exactly** these names.

| What for | File | Size | Download |
|---|---|---|---|
| Transcription (recommended) | `ggml-large-v3-turbo-q8_0.bin` | 833 MB | [whisper.cpp on Hugging Face](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin) |
| Transcription (lighter) | `ggml-large-v3-turbo-q5_0.bin` | 547 MB | [whisper.cpp on Hugging Face](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin) |
| Summary (recommended) | `gemma-3-4b-it-Q4_K_M.gguf` | 2.4 GB | [unsloth/gemma-3-4b-it-GGUF](https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf) |
| Summary (alternative) | `Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2.4 GB | [unsloth/Qwen3-4B-Instruct-2507-GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf) |
| Parakeet (optional) | 4 files, see below | 640 MB | [sherpa-onnx: parakeet-tdt-0.6b-v3-int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2) |
| Who speaks (optional) | `pyannote-segmentation-3-0.onnx` | 6 MB | [sherpa-onnx: pyannote-segmentation-3-0](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) |
| Who speaks (optional) | `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | 25 MB | [sherpa-onnx: 3D-Speaker](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx) |

What comes inside the sherpa-onnx archives has to be **renamed**:

| Inside the archive | Name in the models folder |
|---|---|
| `encoder.int8.onnx` | `parakeet-tdt-0.6b-v3-encoder.int8.onnx` |
| `decoder.int8.onnx` | `parakeet-tdt-0.6b-v3-decoder.int8.onnx` |
| `joiner.int8.onnx` | `parakeet-tdt-0.6b-v3-joiner.int8.onnx` |
| `tokens.txt` | `parakeet-tdt-0.6b-v3-tokens.txt` |
| `model.onnx` (pyannote) | `pyannote-segmentation-3-0.onnx` |

Copy the folder to the phone, for example with `adb push models /sdcard/Download/models`. Each model
has its own license (Gemma has its own terms of use): check it before using it.

## Getting started

1. **Open Escriba on the phone.** The welcome tour takes you through everything: choosing the vault
   folder (it creates whatever configuration is missing without overwriting anything), the models
   folder (it says what it finds and what's missing), pairing the watch, notifications and battery.
   It can be repeated from Settings → Welcome tour.
2. **Pair the watch.** Open Escriba on the watch → Phone → Pair. Check that both show the same code
   and confirm on both.
3. **Don't let the system close Escriba.** Exempt it from battery optimization on the phone and the
   watch. On MagicOS, also: Settings → Battery → App launch → Escriba → turn off "Manage
   automatically" and turn on all three options (the app's Settings → Battery has the guide).
4. **Record.** From the watch app's button, the Tile (favourite profiles as buttons) or the
   complication. When it ends, the phone processes the session and the note shows up in the vault.
   Sessions with more than 10 minutes of speech wait for the phone to be charging, unless you tap
   "Process now".

## Configuration in the vault

Everything lives in `_config/escriba/` inside your vault. They're Markdown notes with frontmatter
(Obsidian's Properties view works to edit them). If something is misspelled, Escriba explains it in
`_config/escriba/errors.md` and in the app, and uses the default value.

### `escriba.md` (global settings)

| Property | Default | What it does |
|---|---|---|
| `schedule` | `07:30-23:30` | Window in which continuous mode records |
| `time_zone` | `Europe/Madrid` | For the times in the notes |
| `default_profile` | `personal` | Profile used when recording without choosing one |
| `default_whisper` | `large-v3-turbo-q8_0` | Whisper model if the profile doesn't say otherwise |
| `default_llm` | `gemma-3-4b-it-Q4_K_M` | LLM if the profile doesn't say otherwise |
| `daily_note_folder` / `daily_note_format` | `Daily` / `YYYY-MM-DD` | Where your daily note is (`YYYY-MM-DD` or `DD-MM-YYYY`) |
| `people_folder`, `projects_folder`, `clients_folder` | `People`, `Projects`, `Clients` | Where to look for notes to link to |
| `attachments_folder` | `Attachments/Escriba` | Where audio saved in the vault goes |
| `phone_audio_retention` | `7d` | Days the audio stays on the phone after the note is written |

### `profiles/<id>.md` (one file per profile)

| Property | Example | What it does |
|---|---|---|
| `name`, `color`, `icon`, `favorite` | `Work`, `"#2E7D32"`, `briefcase`, `true` | How it looks on the watch and in the app (favourites go to the Tile) |
| `inherits` | `work` | Takes everything from another profile; only what's different goes here |
| `mode` | `continuous` / `on_demand` | Records the whole schedule or only when you tap |
| `vad` | `low` / `medium` / `high` | Speech detector sensitivity |
| `split_after_silence` | `15m` | Silence that separates two conversations (two notes) |
| `folder`, `template` | `Work/{{yyyy}}/{{MM}}` | Where the notes are written and with which template |
| `engine`, `whisper`, `llm` | `parakeet`, `large-v3-turbo-q8_0`, `gemma-3-4b-it-Q4_K_M` | This profile's models |
| `language`, `vocabulary` | `auto`, `[Kubernetes, "SAP S/4"]` | Language (`auto`, `en`, `es`) and words Whisper should recognize |
| `diarization` | `true` | Separate who speaks |
| `tasks`, `discard_trivial` | `true` | Extract tasks; don't write a note for trivial conversations |
| `link` | `[people, projects, clients]` | Which existing notes to link to |
| `client` | `"[[Acme]]"` | This profile's client |
| `save_audio`, `audio_retention` | `true`, `90d` / `forever` | Save the audio next to the note, and for how long |
| `rounding` | `1m` / `5m` | Duration rounding (to the nearest minute by default) |
| `daily_note` | `dataview` / `link` / `none` | How it shows up in the daily note |

The profile's body, under `## Prompt`, holds the instructions for the summary. A child profile with
no `## Prompt` inherits its parent's.

## Development

### Structure

| Module | What it is |
|---|---|
| `shared` | Pure Kotlin (JVM): the watch ↔ phone contract, encryption, Ogg/Opus, speech detector, profiles, notes, LLM, voices. All the logic that can be tested without Android goes here. |
| `datalayer` | The Wearable Data Layer link: pairing, encrypted messages, log. |
| `native` | whisper.cpp with our own JNI bridge. |
| `llm` | llama.cpp with our own JNI bridge. |
| `wear` | The watch app: recording, fragment storage, sending, Tile, complication. |
| `mobile` | The phone app: inbox, Room, transcription, diarization, summary, notes, UI. |

The design system
(colours, typography, components) is documented separately and applied with `EscribaTheme` on the
phone and `EscribaWearTheme` on the watch.

### Tests

```bash
./gradlew :shared:test :mobile:testDebugUnitTest
```

Everything runs on the PC (JUnit and Robolectric). **Don't run `connectedAndroidTest` on the phone
you use every day**: it uninstalls the app when it finishes and wipes the database, the pending
audio and the pairing. Room's schemas are in `mobile/schemas`; `MigrationTest` checks the whole
migration chain.

### Guidelines

- **Original code.** No code is copied from other apps or samples; libraries are used as
  dependencies.
- **Privacy first.** No Internet permission, no `DataClient` (it syncs through the cloud), nothing
  leaves the phone except to the vault. Anything new that travels between watch and phone is
  encrypted (`sendSealed`).
- **English** in the code, the comments, the documentation and the commit messages. The UI texts
  live in `strings.xml` (ready for translations); logs are plain English text.
- **The database** exports its schemas to `mobile/schemas`. A new migration goes in
  `EscribaDb.MIGRATIONS`, with its case in `MigrationTest`.
- **Release and R8.** What the system reads by name (like `android_wear_capabilities`) is kept in
  `res/raw/keep.xml`; what native code reads by name, in `proguard-rules.pro`. After touching the
  release configuration, check that the phone and the watch still pair.

### Opening an issue

Include:

1. Model and Android version of the phone and the watch, and Escriba's version (system Settings →
   Apps → Escriba).
2. What you did, what you expected and what happened.
3. The log: on the phone, Settings → Log; on the watch, Diagnostics → Log. **Review it before
   pasting it**: it may contain file names from your vault.
4. If it's about transcription or summaries, which model and which profile (without the
   conversation's content).

### Contributing with a fork

1. Fork the repository and create a branch from `main` (`git checkout -b fix-something`).
2. Make the changes with their tests (`shared` or `mobile/src/test`) and check that everything passes.
3. Build both apps in release and test them paired on real devices if you touch the link, recording
   or the UI.
4. Open a pull request explaining what and why; if the UI changes, with screenshots. By opening it,
   you agree that your contribution is licensed under the Apache License 2.0.

## Credits

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT).
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0): Parakeet, diarization and voiceprints.
- The [Literata](https://github.com/googlefonts/literata) and [Atkinson Hyperlegible Next and Mono](https://www.brailleinstitute.org/freefont/) fonts (SIL Open Font License; licenses in [`docs/licenses`](docs/licenses)).
- All these licenses are also inside the app: Settings → Open source licenses.
- The models (Whisper, Gemma, Qwen, Parakeet, pyannote, 3D-Speaker) have their own licenses.

## License

Escriba is licensed under the [Apache License 2.0](LICENSE). Contributions are accepted under the
same license (section 5 of the license).

The libraries it depends on keep their own licenses (MIT, Apache-2.0, OFL; see Credits), as do the
models, which aren't distributed with the app.
