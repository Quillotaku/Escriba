# Escriba

**Graba en el reloj, transcribe y resume en el móvil, guarda en tu bóveda de Obsidian.**

Escriba convierte un reloj Wear OS y un móvil Android en una grabadora de conversaciones al estilo
de Plaud, pero **todo en local**: el reloj graba, el móvil transcribe con Whisper o Parakeet,
distingue quién habla, resume con un LLM y escribe cada conversación como una nota de Obsidian con
su resumen, decisiones, tareas y transcripción. Sin Internet, sin cuentas y sin nube.

> Versión 1.0.0 · Android 15+ (arm64) y Wear OS 5+ · Interfaz en español

## Qué hace

- **Graba desde la muñeca**: botón de la app del reloj, Tile o complicación. Modo continuo con
  horario (por defecto 07:30–23:30) o bajo demanda. Detector de voz: solo se guarda lo que tiene voz.
- **Envía al móvil cifrado de extremo a extremo** por Bluetooth (Wearable Data Layer, solo a
  dispositivos conectados directamente; nunca por la nube).
- **Transcribe en el móvil** con whisper.cpp (`large-v3-turbo`) o con Parakeet TDT v3 (unas 10
  veces más rápido).
- **Separa hablantes y reconoce voces** que registres («Yo», «Ana López»…). Las huellas de voz
  solo viven en la base de datos privada del móvil.
- **Resume con un LLM local** (Gemma 3 4B por defecto): título, resumen, decisiones, tareas con
  responsable y fecha, y enlaces a personas, proyectos y clientes que ya existan en tu bóveda.
- **Escribe notas de Obsidian** con frontmatter para Dataview, una por conversación (se parten
  por silencios), y enlaza la nota diaria.
- **Perfiles** (trabajo, un cliente, ideas…) como archivos de la bóveda, con herencia, editables
  desde Obsidian o desde la app.
- **Panel** con el tiempo grabado por perfil, las tareas y el uso de los modelos.

## Privacidad

- Ninguna de las dos apps tiene permiso de Internet.
- Reloj y móvil se emparejan con un código de 6 dígitos (ECDH P-256 con compromiso) y todo lo que
  viaja entre ellos va cifrado con AES-256-GCM. La clave está protegida por el Android Keystore.
- Las copias de seguridad de Android están desactivadas.
- El audio se borra del móvil a los 7 días de escribir la nota (configurable) y, si el perfil lo
  guarda en la bóveda, a los días que diga el perfil.

Más detalle en [`docs/seguridad.md`](docs/seguridad.md).

## Requisitos

| | Mínimo | Probado con |
|---|---|---|
| Móvil | Android 15 (API 35), arm64, 8 GB de RAM | Honor Magic 8 Pro (Android 16, MagicOS, 12 GB) |
| Reloj | Wear OS 5 (API 34) con micrófono | Xiaomi Watch 5 (Wear OS 6, HyperOS) |
| Obsidian | Cualquier versión; el plugin Dataview para la nota diaria | Sincronización con YAOS (cualquier otra vale) |
| Espacio | ~4 GB para los modelos recomendados | |

Rendimiento medido en el Honor Magic 8 Pro: Whisper `large-v3-turbo-q8_0` transcribe a ×0,26–0,31
del tiempo real (una hora de voz en unos 16–19 min), Parakeet a ×0,03 y Gemma 3 4B lee unos 64
tokens/s y escribe unos 15 tokens/s.

## Instalación

Todavía no hay APK publicadas: se compilan desde el código.

### 1. Herramientas

- **Android Studio** reciente (su JDK, el JBR, es el que se usa para compilar).
- **Android SDK 37**, **NDK 30.0.16248370** y **CMake 4.1.2** (SDK Manager → SDK Tools).
- **git** y **adb** (viene con el SDK).

### 2. Código

```bash
git clone --recurse-submodules <url-del-repositorio> escriba
cd escriba
```

whisper.cpp y llama.cpp son submódulos (`native/whisper.cpp`, `native/llama.cpp`). Si clonaste sin
`--recurse-submodules`:

```bash
git submodule update --init --recursive
```

### 3. Compilar

```bash
./gradlew :mobile:assembleRelease :wear:assembleRelease
```

La primera vez Gradle descarga el AAR de sherpa-onnx de sus *releases* oficiales y comprueba su
SHA-256 (tarea `:mobile:descargarSherpaOnnx`).

Las dos apps se firman con la clave de depuración **a propósito**: la Data Layer solo comunica apps
con el mismo id (`dev.escriba`) y la misma firma. Si las firmas con tu propia clave, firma las dos
con la misma.

### 4. Instalar

Activa la depuración por Wi-Fi en el móvil y en el reloj (Opciones de desarrollador) y:

```bash
adb -s <móvil> install -r mobile/build/outputs/apk/release/mobile-release.apk
adb -s <reloj> install -r wear/build/outputs/apk/release/wear-release.apk
```

## Los modelos

Los modelos no van dentro de la app: se dejan en una carpeta del móvil (p. ej. `Download/models`)
y se elige esa carpeta en la app. Los archivos deben tener **exactamente** estos nombres.

| Para qué | Archivo | Tamaño | Descarga |
|---|---|---|---|
| Transcripción (recomendado) | `ggml-large-v3-turbo-q8_0.bin` | 833 MB | [whisper.cpp en Hugging Face](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin) |
| Transcripción (más ligero) | `ggml-large-v3-turbo-q5_0.bin` | 547 MB | [whisper.cpp en Hugging Face](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin) |
| Resumen (recomendado) | `gemma-3-4b-it-Q4_K_M.gguf` | 2,4 GB | [unsloth/gemma-3-4b-it-GGUF](https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf) |
| Resumen (alternativa) | `Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2,4 GB | [unsloth/Qwen3-4B-Instruct-2507-GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf) |
| Parakeet (opcional) | 4 archivos, ver abajo | 640 MB | [sherpa-onnx: parakeet-tdt-0.6b-v3-int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2) |
| Quién habla (opcional) | `pyannote-segmentation-3-0.onnx` | 6 MB | [sherpa-onnx: pyannote-segmentation-3-0](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) |
| Quién habla (opcional) | `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | 25 MB | [sherpa-onnx: 3D-Speaker](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx) |

Hay que **renombrar** lo que viene dentro de los comprimidos de sherpa-onnx:

| Dentro del comprimido | Nombre en la carpeta de modelos |
|---|---|
| `encoder.int8.onnx` | `parakeet-tdt-0.6b-v3-encoder.int8.onnx` |
| `decoder.int8.onnx` | `parakeet-tdt-0.6b-v3-decoder.int8.onnx` |
| `joiner.int8.onnx` | `parakeet-tdt-0.6b-v3-joiner.int8.onnx` |
| `tokens.txt` | `parakeet-tdt-0.6b-v3-tokens.txt` |
| `model.onnx` (pyannote) | `pyannote-segmentation-3-0.onnx` |

Copia la carpeta al móvil, por ejemplo con `adb push models /sdcard/Download/models`. Cada modelo
tiene su licencia (Gemma tiene sus propios términos de uso): revísala antes de usarlo.

## Primeros pasos

1. **Abre Escriba en el móvil.** El tour de inicio te lleva por todo: elegir la carpeta de la bóveda
   (crea la configuración que falte sin sobrescribir nada), la carpeta de modelos (dice qué
   encuentra y qué falta), emparejar el reloj, las notificaciones y la batería. Se repite desde
   Ajustes → Tour de inicio.
2. **Empareja el reloj.** Abre Escriba en el reloj → Móvil → Emparejar. Comprueba que los dos
   muestran el mismo código y confirma en los dos.
3. **Que el sistema no cierre Escriba.** Exclúyela de la optimización de batería en el móvil y en el
   reloj. En MagicOS, además: Ajustes → Batería → Inicio de aplicaciones → Escriba → desactiva
   «Gestionar automáticamente» y activa las tres opciones (en Ajustes → Batería de la app está la guía).
4. **Graba.** Desde el botón de la app del reloj, el Tile (los perfiles favoritos como botones) o la
   complicación. Al acabar, el móvil procesa la sesión y la nota aparece en la bóveda. Las sesiones
   de más de 10 minutos de voz esperan a que el móvil esté cargando, salvo que pulses «Procesar ahora».

## Configuración en la bóveda

Todo vive en `_config/escriba/` dentro de tu bóveda. Son notas de Markdown con frontmatter (la vista
de Propiedades de Obsidian sirve para editarlas). Si algo está mal escrito, Escriba lo explica en
`_config/escriba/errores.md` y en la app, y usa el valor por defecto.

### `escriba.md` (ajustes globales)

| Propiedad | Por defecto | Qué hace |
|---|---|---|
| `horario` | `07:30-23:30` | Franja en la que graba el modo continuo |
| `zona_horaria` | `Europe/Madrid` | Para las horas de las notas |
| `perfil_por_defecto` | `casual` | Perfil al empezar a grabar sin elegir |
| `whisper_por_defecto` | `large-v3-turbo-q8_0` | Modelo de Whisper si el perfil no dice otro |
| `llm_por_defecto` | `gemma-3-4b-it-Q4_K_M` | LLM si el perfil no dice otro |
| `nota_diaria_carpeta` / `nota_diaria_formato` | `Diario` / `YYYY-MM-DD` | Dónde está tu nota diaria (`YYYY-MM-DD` o `DD-MM-YYYY`) |
| `carpeta_personas`, `carpeta_proyectos`, `carpeta_clientes` | `Personas`, `Proyectos`, `Clientes` | Dónde buscar notas a las que enlazar |
| `carpeta_adjuntos` | `Adjuntos/Escriba` | Dónde va el audio que se guarda en la bóveda |
| `retencion_audio_movil` | `7d` | Días que el audio se queda en el móvil tras escribir la nota |

### `perfiles/<id>.md` (un archivo por perfil)

| Propiedad | Ejemplo | Qué hace |
|---|---|---|
| `nombre`, `color`, `icono`, `favorito` | `Trabajo`, `"#2E7D32"`, `maletin`, `true` | Cómo se ve en el reloj y en la app (los favoritos van al Tile) |
| `hereda` | `trabajo` | Toma todo de otro perfil; aquí solo lo distinto |
| `modo` | `continuo` / `bajo_demanda` | Graba todo el horario o solo cuando pulsas |
| `vad` | `baja` / `media` / `alta` | Sensibilidad del detector de voz |
| `separar_tras_silencio` | `15m` | Silencio que separa dos conversaciones (dos notas) |
| `carpeta`, `plantilla` | `Trabajo/{{yyyy}}/{{MM}}` | Dónde se escriben las notas y con qué plantilla |
| `motor`, `whisper`, `llm` | `parakeet`, `large-v3-turbo-q8_0`, `gemma-3-4b-it-Q4_K_M` | Modelos de este perfil |
| `idioma`, `vocabulario` | `es`, `[Kubernetes, "SAP S/4"]` | Idioma (`es`, `en`, `auto`) y palabras que Whisper debe reconocer |
| `diarizacion` | `true` | Separar quién habla |
| `tareas`, `descartar_triviales` | `true` | Extraer tareas; no escribir nota de lo trivial |
| `enlazar` | `[personas, proyectos, clientes]` | A qué notas existentes enlazar |
| `cliente` | `"[[Acme]]"` | Cliente de este perfil |
| `guardar_audio`, `retencion_audio` | `true`, `90d` / `siempre` | Guardar el audio junto a la nota y cuánto tiempo |
| `redondeo` | `1m` / `5m` | Redondeo de la duración (por defecto, al minuto más cercano) |
| `nota_diaria` | `dataview` / `enlace` / `ninguna` | Cómo aparece en la nota diaria |

El cuerpo del perfil, bajo `## Prompt`, son las instrucciones para el resumen. Un perfil hijo sin
`## Prompt` hereda el del padre.

## Desarrollo

### Estructura

| Módulo | Qué es |
|---|---|
| `shared` | Kotlin puro (JVM): contrato reloj ↔ móvil, cifrado, Ogg/Opus, detector de voz, perfiles, notas, LLM, voces. Aquí va toda la lógica que se puede probar sin Android. |
| `datalayer` | Enlace por la Wearable Data Layer: emparejamiento, mensajes cifrados, registro. |
| `native` | whisper.cpp con un puente JNI propio. |
| `llm` | llama.cpp con un puente JNI propio. |
| `wear` | App del reloj: grabación, almacenamiento de fragmentos, envío, Tile, complicación. |
| `mobile` | App del móvil: recepción, Room, transcripción, diarización, resumen, notas, interfaz. |

La arquitectura y las decisiones de cada fase están en [`docs/arquitectura.md`](docs/arquitectura.md);
el estudio de alternativas, en [`docs/alternativas.md`](docs/alternativas.md). El sistema de diseño
(colores, tipografía, componentes) está documentado aparte y se aplica con `EscribaTheme` en el
móvil y `EscribaWearTheme` en el reloj.

### Tests

```bash
./gradlew :shared:test :mobile:testDebugUnitTest
```

Todo corre en el PC (JUnit y Robolectric). **No ejecutes `connectedAndroidTest` en tu móvil de
uso diario**: desinstala la app al terminar y borra la base de datos, el audio pendiente y el
emparejamiento. Detalles en [`docs/testing.md`](docs/testing.md).

### Pautas

- **Código propio.** No se copia código de otras apps ni de ejemplos; las librerías se usan como
  dependencias.
- **Privacidad ante todo.** Nada de permiso de Internet, nada de `DataClient` (sincroniza por la
  nube), nada que salga del móvil salvo a la bóveda. Todo lo nuevo que viaje entre reloj y móvil va
  cifrado (`sendSealed`).
- **Español** en la interfaz, los comentarios y los mensajes de commit.
- **La base de datos** exporta sus esquemas a `mobile/schemas`. Una migración nueva va en
  `EscribaDb.MIGRATIONS` y con su caso en `MigrationTest`.
- **Release y R8.** Lo que el sistema lee por nombre (como `android_wear_capabilities`) se conserva
  en `res/raw/keep.xml`; lo que el código nativo lee por nombre, en `proguard-rules.pro`. Después de
  tocar la configuración de release, comprueba que móvil y reloj siguen emparejándose.

### Abrir un issue

Incluye:

1. Modelo y versión de Android del móvil y del reloj, y versión de Escriba (Ajustes del sistema →
   Aplicaciones → Escriba).
2. Qué hiciste, qué esperabas y qué pasó.
3. El registro: en el móvil, Ajustes → Registro; en el reloj, Diagnóstico → Registro. **Revísalo
   antes de pegarlo**: puede contener nombres de archivo de tu bóveda.
4. Si es de transcripción o resumen, qué modelo y qué perfil (sin el contenido de la conversación).

### Contribuir con un fork

1. Haz un fork y crea una rama desde `main` (`git checkout -b arregla-tal-cosa`).
2. Haz los cambios con sus tests (`shared` o `mobile/src/test`) y comprueba que todo pasa.
3. Compila las dos apps en release y pruébalas emparejadas en dispositivos reales si tocas el enlace,
   la grabación o la interfaz.
4. Abre un pull request explicando el qué y el porqué; si cambia la interfaz, con capturas.

## Créditos

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) y [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT).
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0): Parakeet, diarización y huellas de voz.
- Fuentes [Literata](https://github.com/googlefonts/literata) y [Atkinson Hyperlegible Next y Mono](https://www.brailleinstitute.org/freefont/) (SIL Open Font License; licencias en [`docs/licencias`](docs/licencias)).
- Los modelos (Whisper, Gemma, Qwen, Parakeet, pyannote, 3D-Speaker) tienen sus propias licencias.

## Licencia

Pendiente de elegir.
