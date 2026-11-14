// JNI bridge for pinning threads to CPUs. ONNX Runtime and XNNPACK start their worker threads from the thread that
// creates a model, and new threads inherit its CPU mask, so pinning that thread keeps all their work on the fast cores.
#define _GNU_SOURCE
#include <jni.h>
#include <sched.h>

// Pins the calling thread to mask's CPUs (bit n = cpu n). Returns the previous mask (first 64 CPUs), or 0 if either
// call failed.
JNIEXPORT jlong JNICALL Java_dev_opencode_voiceengine_CpuAffinity_set(JNIEnv * env, jobject self, jlong mask) {
    (void) env;
    (void) self;
    cpu_set_t previous;
    if (sched_getaffinity(0, sizeof previous, &previous) != 0) return 0;
    cpu_set_t wanted;
    CPU_ZERO(&wanted);
    for (int cpu = 0; cpu < 64; cpu++) {
        if (mask & (1LL << cpu)) CPU_SET(cpu, &wanted);
    }
    if (sched_setaffinity(0, sizeof wanted, &wanted) != 0) return 0;
    jlong old = 0;
    for (int cpu = 0; cpu < 64; cpu++) {
        if (CPU_ISSET(cpu, &previous)) old |= 1LL << cpu;
    }
    return old;
}
