#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AlphaLLM", __VA_ARGS__)

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
};

static bool g_inited = false;

static size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        int need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        if (need == 1) return n;
        if ((int)(n - (i - 1)) >= need) return n;
        return i - 1;
    }
    return n;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_alpha_hub_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint nCtx, jint nThreads, jboolean useMmap) {
    if (!g_inited) { llama_backend_init(); g_inited = true; }
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = useMmap;
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 256;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("context init failed"); return 0; }
    Engine * e = new Engine();
    e->model = model; e->ctx = ctx; e->vocab = llama_model_get_vocab(model); e->n_ctx = (int) llama_n_ctx(ctx);
    return (jlong) e;
}

JNIEXPORT void JNICALL
Java_org_alpha_hub_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong h) {
    Engine * e = (Engine *) h;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jint JNICALL
Java_org_alpha_hub_LlamaBridge_nativeGenerate(JNIEnv * env, jobject, jlong h, jobjectArray roles, jobjectArray contents,
                                              jint maxTokens, jfloat temp, jobject cb) {
    Engine * e = (Engine *) h;
    if (!e) return -1;
    jclass cbc = env->GetObjectClass(cb);
    jmethodID onTok = env->GetMethodID(cbc, "onToken", "(Ljava/lang/String;)Z");
    if (!onTok) return -1;

    int n = env->GetArrayLength(roles);
    std::vector<std::string> rs(n), cs(n);
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(roles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(contents, i);
        const char * r = env->GetStringUTFChars(jr, nullptr);
        const char * c = env->GetStringUTFChars(jc, nullptr);
        rs[i] = r; cs[i] = c;
        env->ReleaseStringUTFChars(jr, r); env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr); env->DeleteLocalRef(jc);
    }
    for (int i = 0; i < n; i++) { msgs[i].role = rs[i].c_str(); msgs[i].content = cs[i].c_str(); }

    const char * tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) tmpl = "chatml";
    std::vector<char> buf(1 << 14);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len + 16);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (len < 0) {
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
        if (len < 0) return -3;
    }
    std::string prompt(buf.data(), len);

    int cap = (int) prompt.size() + 16;
    std::vector<llama_token> toks(cap);
    int nt = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), cap, true, true);
    if (nt < 0) { toks.resize(-nt); nt = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true); }
    if (nt <= 0) return -3;
    if (nt + 8 > e->n_ctx) return -2;
    int room = e->n_ctx - nt - 1;
    int limit = std::min((int) maxTokens, room);
    if (limit < 1) return -2;

    llama_memory_clear(llama_get_memory(e->ctx), true);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(1));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) 1234567));

    int result = 0;
    int batchMax = 256;
    for (int i = 0; i < nt; i += batchMax) {
        int cnt = std::min(batchMax, nt - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
        if (llama_decode(e->ctx, b) != 0) { llama_sampler_free(smpl); return -4; }
    }

    std::string pending;
    int produced = 0;
    bool stopped = false;
    for (; produced < limit; produced++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        char piece[256];
        int pl = llama_token_to_piece(e->vocab, t, piece, sizeof(piece), 0, false);
        if (pl > 0) pending.append(piece, pl);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            std::string out = pending.substr(0, ok);
            pending.erase(0, ok);
            jstring js = env->NewStringUTF(out.c_str());
            jboolean cont = env->CallBooleanMethod(cb, onTok, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); stopped = true; break; }
            if (!cont) { stopped = true; break; }
        }
        llama_batch nb = llama_batch_get_one(&t, 1);
        if (llama_decode(e->ctx, nb) != 0) { result = -4; break; }
    }
    if (result == 0 && stopped) result = 1;
    else if (result == 0 && produced >= limit) result = 2;
    llama_sampler_free(smpl);
    return result;
}

}
