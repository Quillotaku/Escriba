// Puente JNI entre Escriba y whisper.cpp.
//
// Expone lo mínimo: cargar un modelo, transcribir un bloque de PCM float (16 kHz, mono),
// cancelar y liberar. Los segmentos vuelven como líneas "t0\tt1\tno_speech\ttexto", con los
// tiempos en milisegundos, para no tener que construir objetos Java desde C++.

#include <jni.h>
#include <android/log.h>

#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "EscribaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_cancel{false};

// Una app instalada por ADB no puede leer logcat: los mensajes de whisper/ggml se guardan aquí
// para la pantalla de diagnóstico.
std::mutex g_log_mutex;
std::string g_log;
constexpr size_t kLogMax = 16 * 1024;

void on_log(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG, "ggml", "%s", text);
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log += text;
    if (g_log.size() > kLogMax) g_log.erase(0, g_log.size() - kLogMax);
}

bool should_abort(void *) { return g_cancel.load(); }

std::string to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// GetStringUTFChars/NewStringUTF usan "UTF-8 modificado": un texto con emojis o caracteres
// fuera del plano básico rompería NewStringUTF. Se convierte a UTF-16 a mano.
jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { i++; continue; } // byte inválido: se descarta
        if (i + len > s.size()) break;
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            unsigned char cc = s[i + k];
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        i += ok ? len : 1;
        if (!ok) continue;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

std::string clean(const char *text) {
    std::string s(text ? text : "");
    for (char &c : s) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return s;
}

} // namespace

// El modelo se lee desde un descriptor ya abierto por la app (SAF). Reabrirlo por su ruta
// (/proc/self/fd/N) no sirve: Android vuelve a comprobar el permiso sobre el archivo real de
// Descargas y lo deniega, porque Escriba no tiene permiso general de almacenamiento.
namespace {
size_t fd_read(void *ctx, void *out, size_t n) { return fread(out, 1, n, static_cast<FILE *>(ctx)); }
bool fd_eof(void *ctx) { return feof(static_cast<FILE *>(ctx)) != 0; }
void fd_close(void *ctx) { fclose(static_cast<FILE *>(ctx)); }
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeInitFd(JNIEnv *, jobject, jint fd, jboolean flash_attn) {
    static std::once_flag log_once;
    std::call_once(log_once, [] { whisper_log_set(on_log, nullptr); });

    // dup: whisper cierra el FILE al terminar de leer, y el descriptor original es de Java.
    FILE *file = fdopen(dup(fd), "rb");
    if (file == nullptr) {
        LOGE("No se pudo abrir el descriptor %d", fd);
        return 0;
    }
    fseek(file, 0, SEEK_SET);
    whisper_model_loader loader{file, fd_read, fd_eof, fd_close};

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false; // en Android la GPU de ggml aún no es fiable; se mide en CPU
    params.flash_attn = flash_attn == JNI_TRUE;
    whisper_context *ctx = whisper_init_with_params(&loader, params);
    if (ctx == nullptr) {
        LOGE("No se pudo cargar el modelo desde el descriptor %d", fd);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return from_utf8(env, whisper_print_system_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeLog(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return from_utf8(env, g_log);
}

// Tiempos de la última transcripción, en ms: "codificador,decodificador,lotes,prompt,muestreo".
extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeTimings(JNIEnv *env, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    const whisper_timings *t = whisper_get_timings(ctx);
    if (t == nullptr) return nullptr;
    const std::string s = std::to_string(t->encode_ms) + "," + std::to_string(t->decode_ms) + "," +
                          std::to_string(t->batchd_ms) + "," + std::to_string(t->prompt_ms) + "," +
                          std::to_string(t->sample_ms);
    return from_utf8(env, s);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_escriba_nativeml_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring language, jstring prompt,
        jint threads, jint beam_size, jboolean fast) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const std::string lang = to_utf8(env, language);
    const std::string initial = to_utf8(env, prompt);

    whisper_full_params params = whisper_full_default_params(beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.empty() || lang == "auto" ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.no_context = true;     // cada bloque es independiente: evita arrastrar errores entre fragmentos
    params.suppress_blank = true;
    params.initial_prompt = initial.empty() ? nullptr : initial.c_str();
    if (beam_size > 1) params.beam_search.beam_size = beam_size;
    if (fast == JNI_TRUE) {
        params.greedy.best_of = 1;
        params.temperature_inc = 0.0f; // sin reintentos con temperatura más alta
    }
    params.abort_callback = should_abort;
    params.abort_callback_user_data = nullptr;

    g_cancel.store(false);
    whisper_reset_timings(ctx);
    const int rc = whisper_full(ctx, params, samples.data(), n);
    whisper_print_timings(ctx); // al registro: desglose completo para el diagnóstico
    if (rc != 0) {
        LOGE("whisper_full devolvió %d", rc);
        return nullptr;
    }

    const int segments = whisper_full_n_segments(ctx);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(segments, string_class, nullptr);
    for (int i = 0; i < segments; i++) {
        // whisper devuelve los tiempos en centésimas de segundo.
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const float no_speech = whisper_full_get_segment_no_speech_prob(ctx, i);
        std::string line = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + std::to_string(no_speech) + "\t" +
                           clean(whisper_full_get_segment_text(ctx, i));
        jstring js = from_utf8(env, line);
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}
