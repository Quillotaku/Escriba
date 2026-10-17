// Puente JNI entre Escriba y llama.cpp.
//
// Carga un modelo GGUF desde un descriptor abierto por la app (SAF), genera texto a partir de
// un mensaje de sistema y uno de usuario con la plantilla de chat del propio modelo y, si se le
// da una gramática GBNF, restringe la salida a ella (así el resultado es siempre JSON válido).

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#include <cmath>

#define TAG "EscribaLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_cancel{false};

std::mutex g_log_mutex;
std::string g_log;
constexpr size_t kLogMax = 16 * 1024;

void on_log(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG, "llama", "%s", text);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log += text;
    if (g_log.size() > kLogMax) g_log.erase(0, g_log.size() - kLogMax);
}

bool should_abort(void *) { return g_cancel.load(); }

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    // Estadísticas de la última generación.
    int prompt_tokens = 0;
    int generated_tokens = 0;
    double prompt_ms = 0;
    double generate_ms = 0;
    bool truncated = false;
};

std::string to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) out.push_back(static_cast<char>(cp));
        else if (cp < 0x800) { out.push_back(static_cast<char>(0xC0 | (cp >> 6))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else if (cp < 0x10000) { out.push_back(static_cast<char>(0xE0 | (cp >> 12))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else { out.push_back(static_cast<char>(0xF0 | (cp >> 18))); out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// UTF-8 → UTF-16 a mano: NewStringUTF solo acepta "UTF-8 modificado" y fallaría con emojis.
jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = s[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { i++; continue; }
        if (i + len > s.size()) break;
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            const unsigned char cc = s[i + k];
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

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    const int needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(needed, 0)));
    if (needed <= 0) return tokens;
    const int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), needed, add_special, true);
    tokens.resize(static_cast<size_t>(std::max(n, 0)));
    return tokens;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_escriba_llm_LlamaNative_nativeLoadFd(JNIEnv *, jobject, jint fd, jint n_ctx, jint n_threads, jboolean flash_attn) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(on_log, nullptr);
        llama_backend_init();
    });

    // dup: llama.cpp cierra el FILE al terminar, y el descriptor original es de Java.
    FILE *file = fdopen(dup(fd), "rb");
    if (file == nullptr) {
        LOGE("No se pudo abrir el descriptor %d", fd);
        return 0;
    }
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // solo CPU, como Whisper
    // Leer el modelo entero en RAM al cargar. Con mmap, las páginas se leían bajo demanda a través
    // de la capa de almacenamiento de Android (FUSE) durante el primer cálculo, y el prompt iba a
    // 40 tokens/s en vez de a varios cientos.
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model *model = llama_model_load_from_file_ptr(file, mparams);
    fclose(file);
    if (model == nullptr) {
        LOGE("No se pudo cargar el modelo");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = flash_attn == JNI_TRUE ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    if (flash_attn == JNI_TRUE) {
        // Con flash attention la caché KV puede ir cuantizada: la mitad de memoria que en F16.
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }
    cparams.abort_callback = should_abort;
    cparams.abort_callback_data = nullptr;
    cparams.no_perf = false;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("No se pudo crear el contexto (n_ctx=%d)", n_ctx);
        llama_model_free(model);
        return 0;
    }
    auto *h = new Handle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    h->n_batch = 512;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_escriba_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_escriba_llm_LlamaNative_nativeCancel(JNIEnv *, jobject) { g_cancel.store(true); }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_llm_LlamaNative_nativeLog(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return from_utf8(env, g_log);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_llm_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return from_utf8(env, llama_print_system_info());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_escriba_llm_LlamaNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    return h ? h->n_ctx : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_escriba_llm_LlamaNative_nativeCountTokens(JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return -1;
    return static_cast<jint>(tokenize(h->vocab, to_utf8(env, text), false).size());
}

// "tokens_prompt,tokens_generados,ms_prompt,ms_generacion,truncado" de la última generación.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_llm_LlamaNative_nativeLastStats(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return nullptr;
    const std::string s = std::to_string(h->prompt_tokens) + "," + std::to_string(h->generated_tokens) + "," +
                          std::to_string(h->prompt_ms) + "," + std::to_string(h->generate_ms) + "," + (h->truncated ? "1" : "0");
    return from_utf8(env, s);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_escriba_llm_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring system, jstring user, jstring grammar,
        jint max_tokens, jfloat temperature) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return nullptr;
    g_cancel.store(false);
    h->truncated = false;

    // 1. Mensajes con la plantilla de chat que trae el propio modelo.
    const std::string sys = to_utf8(env, system);
    const std::string usr = to_utf8(env, user);
    std::vector<llama_chat_message> messages;
    if (!sys.empty()) messages.push_back({"system", sys.c_str()});
    messages.push_back({"user", usr.c_str()});
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    std::vector<char> buf(sys.size() + usr.size() + 1024);
    int n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        LOGE("El modelo no tiene una plantilla de chat utilizable");
        return nullptr;
    }
    const std::string prompt(buf.data(), static_cast<size_t>(n));

    // 2. Tokens del prompt: tienen que caber junto con la respuesta.
    std::vector<llama_token> tokens = tokenize(h->vocab, prompt, true);
    if (static_cast<int>(tokens.size()) + max_tokens > h->n_ctx) {
        LOGE("Prompt demasiado largo: %zu tokens + %d de respuesta > contexto %d", tokens.size(), max_tokens, h->n_ctx);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_perf_context_reset(h->ctx);

    // 3. Muestreo. La gramática va aparte: comprobarla contra todo el vocabulario en cada token
    //    (150–260 mil tokens) es carísimo. Se elige primero el token y solo se mira si la gramática
    //    lo acepta; únicamente si lo rechaza se filtra el vocabulario entero (como hace llama.cpp
    //    en sus herramientas).
    const std::string gbnf = to_utf8(env, grammar);
    llama_sampler *grammar_sampler = nullptr;
    if (!gbnf.empty()) {
        grammar_sampler = llama_sampler_init_grammar(h->vocab, gbnf.c_str(), "root");
        if (grammar_sampler == nullptr) {
            LOGE("Gramática GBNF inválida");
            return nullptr;
        }
    }
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }

    // 4. Prompt por lotes y generación token a token.
    std::string out;
    bool failed = false;
    for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(h->n_batch)) {
        const int count = static_cast<int>(std::min(tokens.size() - i, static_cast<size_t>(h->n_batch)));
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) {
            failed = true;
            break;
        }
    }
    int generated = 0;
    char piece[256];
    const int n_vocab = llama_vocab_n_tokens(h->vocab);
    std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));
    while (!failed && generated < max_tokens) {
        llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (grammar_sampler != nullptr) {
            // ¿Lo acepta la gramática? Prueba con un único candidato.
            const float *logits = llama_get_logits_ith(h->ctx, -1);
            llama_token_data single{tok, logits[tok], 0.0f};
            llama_token_data_array one{&single, 1, -1, false};
            llama_sampler_apply(grammar_sampler, &one);
            if (std::isinf(single.logit) && single.logit < 0) {
                // No: se aplica la gramática a todo el vocabulario y se toma el mejor permitido.
                for (int t = 0; t < n_vocab; t++) candidates[static_cast<size_t>(t)] = {t, logits[t], 0.0f};
                llama_token_data_array all{candidates.data(), candidates.size(), -1, false};
                llama_sampler_apply(grammar_sampler, &all);
                float best = -INFINITY;
                for (size_t k = 0; k < all.size; k++) {
                    if (all.data[k].logit > best) {
                        best = all.data[k].logit;
                        tok = all.data[k].id;
                    }
                }
                if (std::isinf(best)) {
                    LOGE("La gramática no admite ningún token: se corta la generación");
                    break;
                }
            }
            llama_sampler_accept(grammar_sampler, tok);
        }
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        const int len = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, static_cast<size_t>(len));
        generated++;
        if (g_cancel.load() || llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            failed = true;
            break;
        }
    }
    if (generated >= max_tokens) h->truncated = true;
    llama_sampler_free(chain);
    if (grammar_sampler != nullptr) llama_sampler_free(grammar_sampler);

    const llama_perf_context_data perf = llama_perf_context(h->ctx);
    h->prompt_tokens = perf.n_p_eval;
    h->generated_tokens = perf.n_eval;
    h->prompt_ms = perf.t_p_eval_ms;
    h->generate_ms = perf.t_eval_ms;

    if (failed) {
        LOGE("La generación se interrumpió (cancelada o error de llama_decode)");
        return nullptr;
    }
    return from_utf8(env, out);
}
