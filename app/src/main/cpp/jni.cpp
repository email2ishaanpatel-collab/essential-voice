// JNI bridge for whisper.cpp.  One transcribe call returns the whole joined
// transcript so a dictation costs exactly two JNI crossings, not one per segment.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <cstring>
#include <malloc.h>

#include "whisper.h"
#include "ggml.h"

#define TAG "EVWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Set from Kotlin when the user cancels mid-transcribe; ggml polls it between graphs.
std::atomic<bool> g_abort{false};

bool abort_cb(void * /*user_data*/) {
    return g_abort.load(std::memory_order_relaxed);
}

// Whisper likes to emit "[BLANK_AUDIO]", "(wind blowing)" and friends on silence.
// Those are never something you meant to dictate, so drop any segment that is
// entirely wrapped in brackets, and trim the rest.
bool is_noise_segment(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\n");
    if (b == std::string::npos) return true;
    size_t e = s.find_last_not_of(" \t\n");
    char first = s[b], last = s[e];
    // U+266A / U+266B, whisper's music markers. They are the reason this
    // function is worth extending: they are not bracketed, so they used to be
    // treated as dictated text, and they are multi-byte — which is how a
    // half-written one ever reached NewStringUTF. Matched on the UTF-8 bytes
    // because std::string here is bytes, not characters.
    static const char *MUSIC[] = {"\xE2\x99\xAA", "\xE2\x99\xAB"};
    for (const char *m : MUSIC) {
        if (s.compare(b, 3, m, 3) == 0) return true;
    }
    return (first == '[' && last == ']') ||
           (first == '(' && last == ')') ||
           (first == '*' && last == '*');
}

std::string trim(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\n");
    return s.substr(b, e - b + 1);
}

// Make `s` safe to hand to NewStringUTF.
//
// This is not defensive tidying; it is a crash fix. whisper emits its
// non-speech markers as multi-byte characters — the music note U+266A among
// them — and a segment boundary can fall *inside* one, so
// whisper_full_get_segment_text hands back a string ending in a half-written
// character. NewStringUTF does not tolerate that: it is specified over
// *Modified* UTF-8, ART checks it, and an invalid sequence is a JNI abort —
// SIGABRT, the whole process, no catch possible.
//
// That is what "the Essential Key randomly stops working" was. The process
// dying takes the accessibility service with it, and this phone does not
// merely rebind it: the service drops out of Bound services and has to be
// switched on by hand again. Hold the key somewhere with music playing and
// nothing said, and the key is dead until Settings is opened.
//
// Two differences from plain UTF-8 validation are load-bearing:
//   * astral characters (4-byte, emoji) must be re-encoded as a CESU-8
//     surrogate pair, because Modified UTF-8 has no 4-byte form and passing
//     one aborts exactly like a truncated sequence does;
//   * an embedded NUL would silently truncate, so it is dropped.
// Anything malformed is dropped rather than replaced: a transcript is text the
// user is about to send to somebody, and U+FFFD in it is worse than nothing.
std::string to_modified_utf8(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    const unsigned char *p = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;

    auto cont = [&](size_t at, unsigned char lo, unsigned char hi) {
        return at < n && p[at] >= lo && p[at] <= hi;
    };

    while (i < n) {
        const unsigned char c = p[i];

        if (c == 0x00) { i++; continue; }              // would truncate the string
        if (c < 0x80) { out += (char) c; i++; continue; }

        size_t len = 0;
        unsigned char lo = 0x80, hi = 0xBF;
        if (c >= 0xC2 && c <= 0xDF) { len = 2; }
        else if (c == 0xE0)         { len = 3; lo = 0xA0; }
        else if (c >= 0xE1 && c <= 0xEC) { len = 3; }
        else if (c == 0xED)         { len = 3; hi = 0x9F; } // no surrogates
        else if (c >= 0xEE && c <= 0xEF) { len = 3; }
        else if (c == 0xF0)         { len = 4; lo = 0x90; }
        else if (c >= 0xF1 && c <= 0xF3) { len = 4; }
        else if (c == 0xF4)         { len = 4; hi = 0x8F; }
        else { i++; continue; }                        // stray continuation / 0xC0,0xC1,0xF5+

        if (!cont(i + 1, lo, hi)) { i++; continue; }
        bool ok = true;
        for (size_t k = 2; k < len; k++) {
            if (!cont(i + k, 0x80, 0xBF)) { ok = false; break; }
        }
        // The truncated-at-the-end case, which is the one that crashed.
        if (!ok) { i++; continue; }

        if (len < 4) {
            out.append(reinterpret_cast<const char *>(p + i), len);
        } else {
            // Decode and re-emit as a CESU-8 surrogate pair.
            unsigned int cp = ((c & 0x07u) << 18)
                            | ((p[i + 1] & 0x3Fu) << 12)
                            | ((p[i + 2] & 0x3Fu) << 6)
                            | (p[i + 3] & 0x3Fu);
            cp -= 0x10000u;
            unsigned int hiS = 0xD800u + (cp >> 10);
            unsigned int loS = 0xDC00u + (cp & 0x3FFu);
            for (unsigned int su : {hiS, loS}) {
                out += (char) (0xE0u | (su >> 12));
                out += (char) (0x80u | ((su >> 6) & 0x3Fu));
                out += (char) (0x80u | (su & 0x3Fu));
            }
        }
        i += len;
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeInit(
        JNIEnv *env, jclass, jstring model_path, jboolean use_gpu) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu;
    cparams.flash_attn = false;

    LOGI("loading model: %s (gpu=%d)", path, (int) use_gpu);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!ctx) LOGW("whisper_init failed");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeFree(
        JNIEnv *, jclass, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeAbort(
        JNIEnv *, jclass, jboolean on) {
    g_abort.store(on, std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass,
        jlong ptr,
        jfloatArray audio,
        jint n_threads,
        jstring language,
        jboolean translate,
        jint beam_size,
        jint best_of,
        jfloat no_speech_thold,
        jstring initial_prompt,
        jint audio_ctx,
        jboolean single_segment) {

    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize n_samples = env->GetArrayLength(audio);

    whisper_full_params p = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);

    p.n_threads         = n_threads;
    p.translate         = translate;
    p.no_context        = true;    // each dictation is independent
    p.single_segment    = single_segment;

    // The encoder's cost is fixed by the audio context, not by how much audio
    // there is: whisper pads every clip out to thirty seconds, so a two-second
    // dictation runs exactly the same matrix multiplies as a thirty-second one.
    // Shrinking the context to fit the clip is the single largest saving
    // available here, and the caller is the one that knows how long the clip is
    // (0 leaves the model's own 1500 alone).
    if (audio_ctx > 0) p.audio_ctx = audio_ctx;
    p.no_timestamps     = true;
    p.print_realtime    = false;
    p.print_progress    = false;
    p.print_timestamps  = false;
    p.print_special     = false;
    p.suppress_blank    = true;
    p.suppress_nst      = true;    // drop (music), (laughter) style tokens
    p.temperature       = 0.0f;
    p.no_speech_thold   = no_speech_thold;

    if (beam_size > 1) {
        p.beam_search.beam_size = beam_size;
        p.greedy.best_of = best_of;
    } else {
        p.greedy.best_of = best_of;
    }

    const char *lang = nullptr;
    if (language) {
        lang = env->GetStringUTFChars(language, nullptr);
        p.language = lang;
        p.detect_language = (std::strcmp(lang, "auto") == 0);
    }

    const char *prompt = nullptr;
    if (initial_prompt) {
        prompt = env->GetStringUTFChars(initial_prompt, nullptr);
        if (std::strlen(prompt) > 0) p.initial_prompt = prompt;
    }

    g_abort.store(false, std::memory_order_relaxed);
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    std::string out;
    if (whisper_full(ctx, p, samples, n_samples) != 0) {
        LOGW("whisper_full failed");
    } else {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; i++) {
            std::string seg = whisper_full_get_segment_text(ctx, i);
            if (is_noise_segment(seg)) continue;
            seg = trim(seg);
            if (seg.empty()) continue;
            if (!out.empty()) out += " ";
            out += seg;
        }
    }

    if (lang)   env->ReleaseStringUTFChars(language, lang);
    if (prompt) env->ReleaseStringUTFChars(initial_prompt, prompt);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    // Never hand `out` straight to NewStringUTF: an invalid sequence there is a
    // JNI abort, not an exception. See to_modified_utf8.
    const std::string safe = to_modified_utf8(out);
    if (safe.size() != out.size()) {
        LOGW("transcript held %zu bytes of invalid UTF-8; dropped",
             out.size() - safe.size());
    }
    return env->NewStringUTF(safe.c_str());
}

// Bionic keeps freed arenas on its own free lists, exactly like glibc does; without
// this an unloaded model stays charged to the process RSS and helps nobody.
JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeTrimHeap(JNIEnv *, jclass) {
#ifdef M_PURGE
    mallopt(M_PURGE, 0);
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
