// Minimal JNI surface for llama.cpp on Android arm64 (CPU backend).
// Kotlin: com.saeed.minikeys.core.llama.LlamaCppJni
//  loadModel(path, nCtx, nThreads) -> jlong handle
//  describe(handle) -> String
//  chat(handle, roles, contents, maxTokens, temp, topK, topP,
//       penaltyLastN, repeatPenalty, seed) -> String reply
//  free(handle)
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <map>
#include <memory>
#include "llama.h"
#include "ggml.h"
#define LOG_TAG "llama-minikeys"

static void log_to_android(ggml_log_level level, const char *text, void *user) {
    if (!text) return;
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, LOG_TAG, "%s", text);
}

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex mutex;
};
// Handle registry: handles are opaque ids, never raw pointers. lookup() hands out
// shared_ptr copies, so a chat() racing with free() can never touch freed memory —
// the Session struct dies only when the last in-flight call releases it. This replaces
// the old raw-pointer scheme whose free() deleted the Session while its own mutex was
// still held (undefined behaviour -> intermittent native crashes).
std::mutex g_registry_mutex;
std::map<jlong, std::shared_ptr<Session>> g_registry;
jlong g_next_handle = 1;
std::shared_ptr<Session> lookup(jlong h) {
    std::lock_guard<std::mutex> g(g_registry_mutex);
    auto it = g_registry.find(h);
    return (it == g_registry.end()) ? nullptr : it->second;
}

void throw_java(JNIEnv *env, const std::string &msg) {
    jclass rc = env->FindClass("java/lang/RuntimeException");
    if (rc != nullptr) {
        env->ThrowNew(rc, msg.c_str());
        env->DeleteLocalRef(rc);
    }
}

std::string to_std(JNIEnv *env, jstring js) {
    if (js == nullptr) return {};
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s = (c != nullptr) ? c : "";
    if (c != nullptr) env->ReleaseStringUTFChars(js, c);
    return s;
}

jstring to_java(JNIEnv *env, const std::string &utf8) {
    std::vector<uint16_t> out;
    out.reserve(utf8.size());
    size_t i = 0, n = utf8.size();
    while (i < n) {
        unsigned char c = (unsigned char) utf8[i];
        if (c < 0x80) { out.push_back(c); i++; continue; }
        int need = 0;
        if ((c & 0xE0) == 0xC0) need = 1;
        else if ((c & 0xF0) == 0xE0) need = 2;
        else if ((c & 0xF8) == 0xF0) need = 3;
        if (need == 0 || i + need >= n) { i++; continue; }
        uint32_t cp = (need == 1) ? (c & 0x1F) : (need == 2) ? (c & 0x0F) : (c & 0x07);
        bool ok = true;
        for (int k = 1; k <= need; k++) {
            unsigned char cc = (unsigned char) utf8[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok || (need == 1 && cp < 0x80) || (need == 2 && cp < 0x800) ||
            (need == 3 && cp < 0x10000) || cp > 0x10FFFF ||
            (cp >= 0xD800 && cp <= 0xDFFF)) { i++; continue; }
        i += (size_t) need + 1;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((uint16_t) (0xD800 | (cp >> 10)));
            out.push_back((uint16_t) (0xDC00 | (cp & 0x3FF)));
        } else {
            out.push_back((uint16_t) cp);
        }
    }
    if (out.empty()) return env->NewStringUTF("");
    return env->NewString(out.data(), (jsize) out.size());
}

void ensure_backend() {
    static std::once_flag flag;
    std::call_once(flag, [] {
        ggml_log_set(log_to_android, nullptr);
        llama_backend_init();
    });
}

std::string gemma_fallback(const std::vector<llama_chat_message> &msgs) {
    std::string NL(1, (char) 10);
    std::string out;
    for (const auto &m : msgs) {
        bool is_model = strcmp(m.role, "assistant") == 0;
        out += is_model ? "<start_of_turn>model" + NL : "<start_of_turn>user" + NL;
        out += m.content;
        out += "<end_of_turn>" + NL;
    }
    out += "<start_of_turn>model" + NL;
    return out;
}

std::string chatml_fallback(const std::vector<llama_chat_message> &msgs) {
    std::string NL(1, (char) 10);
    std::string out;
    for (const auto &m : msgs) {
        out += std::string("<|im_start|>") + m.role + NL;
        out += m.content;
        out += "<|im_end|>" + NL;
    }
    out += "<|im_start|>assistant" + NL;
    return out;
}

std::string format_prompt(const Session *s, const std::vector<llama_chat_message> &msgs) {
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl != nullptr && tmpl[0] != 0) {
        int64_t want = 0;
        for (const auto &m : msgs) want += (int64_t) strlen(m.content) + (int64_t) strlen(m.role) + 8;
        for (int attempt = 0; attempt < 3; attempt++) {
            std::vector<char> buf((size_t) (want * 2 + 4096));
            int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), false,
                                                   buf.data(), (int32_t) buf.size());
            if (len >= 0 && (uint32_t) len < buf.size()) return std::string(buf.data());
            if (len < 0) break;
            want = len + 1;
        }
    }
    char desc[256] = {0};
    if (llama_model_desc(s->model, desc, sizeof(desc)) > 0) {
        std::string d(desc);
        if (d.find("gemma") != std::string::npos) return gemma_fallback(msgs);
    }
    return chatml_fallback(msgs);
}

std::string detokenize_range(const Session *s, const std::vector<llama_token> &toks) {
    std::string out;
    char piece[128];
    for (llama_token t : toks) {
        int32_t n = llama_token_to_piece(s->vocab, t, piece, (int32_t) sizeof(piece), 0, false);
        if (n > 0) out.append(piece, (size_t) n);
    }
    return out;
}

} // namespace

#define JC(name) Java_com_saeed_minikeys_core_llama_LlamaCppJni_##name

extern "C" JNIEXPORT jlong JNICALL
JC(loadModel)(JNIEnv *env, jobject, jstring jpath, jint jnCtx, jint jnThreads) {
    ensure_backend();
    auto sess_ = std::make_shared<Session>();
    Session *s = sess_.get();
    std::string path = to_std(env, jpath);
    s->model = llama_model_load_from_file(path.c_str(), llama_model_default_params());
    if (s->model == nullptr) {
        throw_java(env, "failed to load the model file (corrupt GGUF, unsupported arch, or out of memory)");
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (jnCtx > 0) ? (uint32_t) jnCtx : 2048;
    cp.n_batch = cp.n_ctx;
    cp.n_threads = (jnThreads > 0) ? jnThreads : 4;
    cp.n_threads_batch = cp.n_threads;
    s->ctx = llama_init_from_model(s->model, cp);
    if (s->ctx == nullptr) {
        llama_model_free(s->model);
        s->model = nullptr;
        throw_java(env, "failed to create the inference context (out of memory)");
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);
    std::lock_guard<std::mutex> g(g_registry_mutex);
    jlong handle = g_next_handle++;
    g_registry.emplace(handle, sess_);
    return handle;
}

extern "C" JNIEXPORT jstring JNICALL
JC(describe)(JNIEnv *env, jobject, jlong h) {
    std::shared_ptr<Session> sess_ = lookup(h);
    Session *s = sess_.get();
    if (s == nullptr) {
        throw_java(env, "model not loaded");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->model == nullptr || s->ctx == nullptr) {
        throw_java(env, "model not loaded");
        return nullptr;
    }
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    std::string out = desc;
    out += " | ctx = " + std::to_string((int) llama_n_ctx(s->ctx));
    return to_java(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
JC(chat)(JNIEnv *env, jobject, jlong h, jobjectArray jroles, jobjectArray jcontents,
        jint maxTokens, jfloat temp, jint topK, jfloat topP,
        jint penaltyLastN, jfloat repeatPenalty, jlong seed) {
    std::shared_ptr<Session> sess_ = lookup(h);
    Session *s = sess_.get();
    if (s == nullptr) {
        throw_java(env, "model not loaded");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->ctx == nullptr) {
        throw_java(env, "model not loaded");
        return nullptr;
    }
    // Each chat() is a fresh, independent request: drop the previous conversation from the
    // KV cache. Without this, every call keeps accumulating tokens until llama_decode can no
    // longer find a KV slot (rc = 1) and generation silently dies — the 'works a few times,
    // then suddenly stops' failure mode.
    llama_memory_clear(llama_get_memory(s->ctx), true);
    int n = env->GetArrayLength(jroles);
    if (n == 0) {
        throw_java(env, "no messages given");
        return nullptr;
    }
    std::vector<std::string> roles((size_t) n), contents((size_t) n);
    std::vector<llama_chat_message> msgs((size_t) n);
    for (int i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[(size_t) i] = to_std(env, jr);
        contents[(size_t) i] = to_std(env, jc);
        if (jr != nullptr) env->DeleteLocalRef(jr);
        if (jc != nullptr) env->DeleteLocalRef(jc);
        if (roles[(size_t) i].empty() || contents[(size_t) i].empty()) {
            throw_java(env, "empty role or content in messages");
            return nullptr;
        }
        msgs[(size_t) i] = {roles[(size_t) i].c_str(), contents[(size_t) i].c_str()};
    }
    std::string prompt = format_prompt(s, msgs);
    if (prompt.empty()) {
        throw_java(env, "could not render a chat template for this model");
        return nullptr;
    }
    // Tokenize the rendered prompt. A null buffer answers with the NEGATIVE of the token
    // count (per llama.h), so the sign must be flipped before sizing the vector.
    int32_t probe = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    int32_t count = (probe < 0) ? -probe : probe;
    if (count <= 0 || count > 1048576) {
        throw_java(env, "tokenization failed");
        return nullptr;
    }
    std::vector<llama_token> tokens((size_t) count);
    int32_t got = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                                 tokens.data(), count, true, true);
    if (got < 0) {
        int32_t want = -got;
        if (want <= 0 || want > 1048576) {
            throw_java(env, "tokenization failed");
            return nullptr;
        }
        tokens.resize((size_t) want);
        got = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                             tokens.data(), want, true, true);
        if (got < 0) {
            throw_java(env, "tokenization failed");
            return nullptr;
        }
    }
    tokens.resize((size_t) got);
    // Prefill the whole prompt in one decode call (positions are tracked internally).
    llama_batch prefill = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    int32_t rc = llama_decode(s->ctx, prefill);
    if (rc < 0) {
        throw_java(env, "prompt decode failed (rc = " + std::to_string(rc) + ")");
        return nullptr;
    }
    if (rc > 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "prefill warning rc=%d", rc);
    }
    // Sampling chain: penalties (optional) -> top-k -> top-p -> temp -> dist/greedy.
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(sp);
    int32_t n_vocab = llama_vocab_n_tokens(s->vocab);
    if (repeatPenalty > 1.0f && penaltyLastN > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(n_vocab, penaltyLastN, repeatPenalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));
    }
    std::vector<llama_token> out;
    out.reserve((size_t) maxTokens);
    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(chain, s->ctx, -1);
        if (tok < 0 || (uint32_t) tok > (uint32_t) n_vocab + 128) break; // LLAMA_TOKEN_NULL guard
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        out.push_back(tok);
        llama_batch next = llama_batch_get_one(&tok, 1);
        int32_t grc = llama_decode(s->ctx, next);
        if (grc != 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "decode rc=%d at step %d", grc, i);
            break;
        }
    }
    llama_sampler_free(chain);
    if (out.empty()) {
        // Surface the failure instead of returning an empty reply that looks like a model
        // that 'suddenly stopped'.
        throw_java(env, "generation produced no tokens (decode failed or context exhausted)");
        return nullptr;
    }
    return to_java(env, detokenize_range(s, out));
}

extern "C" JNIEXPORT void JNICALL
JC(free)(JNIEnv *env, jobject, jlong h) {
    std::shared_ptr<Session> sess_;
    {
        // Unregister first: a second free() of the same handle becomes a harmless no-op.
        std::lock_guard<std::mutex> g(g_registry_mutex);
        auto it = g_registry.find(h);
        if (it == g_registry.end()) return;
        sess_ = it->second;
        g_registry.erase(it);
    }
    // Wait for any in-flight chat() to finish (it holds s->mutex), then release the heavy
    // resources. The Session struct itself is kept alive by whichever shared_ptr copies are
    // still outstanding — never deleted under its own mutex.
    std::lock_guard<std::mutex> lock(sess_->mutex);
    if (sess_->ctx != nullptr) { llama_free(sess_->ctx); sess_->ctx = nullptr; }
    if (sess_->model != nullptr) { llama_model_free(sess_->model); sess_->model = nullptr; }
}
