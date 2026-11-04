// JNI bridge from Kotlin to transcribe.cpp: open a model, transcribe 16 kHz mono float PCM, close.
#define _GNU_SOURCE
#include <jni.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>

#include "transcribe.h"

static void throw_status(JNIEnv * env, const char * what, transcribe_status st) {
    char message[256];
    snprintf(message, sizeof message, "%s: %s", what, transcribe_status_string(st));
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    (*env)->ThrowNew(env, cls, message);
}

JNIEXPORT jlong JNICALL Java_dev_opencode_voiceengine_Native_open(JNIEnv * env, jobject self, jstring path, jint threads) {
    (void) self;
    const char * model_path = (*env)->GetStringUTFChars(env, path, NULL);
    struct transcribe_session_params params;
    transcribe_session_params_init(&params);
    params.n_threads = threads;
    struct transcribe_session * session = NULL;
    transcribe_status st = transcribe_open(model_path, NULL, &params, &session);
    (*env)->ReleaseStringUTFChars(env, path, model_path);
    if (st != TRANSCRIBE_OK) {
        throw_status(env, "Could not load the speech model", st);
        return 0;
    }
    return (jlong) (intptr_t) session;
}

// cpu_mask: CPUs (bit n = cpu n) the run may use, 0 for any. ggml starts its worker threads from the calling
// thread during the run, so they inherit this mask; the caller's own mask is restored afterwards.
JNIEXPORT jstring JNICALL Java_dev_opencode_voiceengine_Native_transcribe(JNIEnv * env, jobject self, jlong handle, jfloatArray pcm, jlong cpu_mask) {
    (void) self;
    struct transcribe_session * session = (struct transcribe_session *) (intptr_t) handle;
    jsize n = (*env)->GetArrayLength(env, pcm);
    jfloat * samples = (*env)->GetFloatArrayElements(env, pcm, NULL);
    cpu_set_t previous;
    int pinned = 0;
    if (cpu_mask != 0 && sched_getaffinity(0, sizeof previous, &previous) == 0) {
        cpu_set_t wanted;
        CPU_ZERO(&wanted);
        for (int cpu = 0; cpu < 64; cpu++) {
            if (cpu_mask & (1LL << cpu)) CPU_SET(cpu, &wanted);
        }
        pinned = sched_setaffinity(0, sizeof wanted, &wanted) == 0;
    }
    transcribe_status st = transcribe_run(session, samples, n, NULL);
    if (pinned) sched_setaffinity(0, sizeof previous, &previous);
    (*env)->ReleaseFloatArrayElements(env, pcm, samples, JNI_ABORT);
    if (st != TRANSCRIBE_OK) {
        throw_status(env, "Transcription failed", st);
        return NULL;
    }
    return (*env)->NewStringUTF(env, transcribe_full_text(session));
}

JNIEXPORT void JNICALL Java_dev_opencode_voiceengine_Native_close(JNIEnv * env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    transcribe_close((struct transcribe_session *) (intptr_t) handle);
}
