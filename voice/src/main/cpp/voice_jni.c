// JNI bridge between Whisper.kt and whisper.cpp: load a model, transcribe 16 kHz mono samples, free it.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "ShebangVoice"

JNIEXPORT jlong JNICALL
Java_dev_shebang_devboard_voice_Whisper_nativeInit(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (ctx == NULL) __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the model");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_dev_shebang_devboard_voice_Whisper_nativeFree(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) whisper_free((struct whisper_context *) (intptr_t) handle);
}

// English, greedy decoding, no timestamps; the text of every segment, joined.
JNIEXPORT jstring JNICALL
Java_dev_shebang_devboard_voice_Whisper_nativeTranscribe(JNIEnv *env, jclass cls, jlong handle, jfloatArray samples, jint threads) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (ctx == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = "en";
    p.translate = false;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;

    int rc = whisper_full(ctx, p, pcm, n);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return NULL;
    }
    size_t cap = 256, len = 0;
    char *out = malloc(cap);
    if (out == NULL) return NULL;
    out[0] = 0;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        size_t tl = strlen(t);
        if (len + tl + 1 > cap) {
            while (len + tl + 1 > cap) cap *= 2;
            char *grown = realloc(out, cap);
            if (grown == NULL) { free(out); return NULL; }
            out = grown;
        }
        memcpy(out + len, t, tl + 1);
        len += tl;
    }
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
