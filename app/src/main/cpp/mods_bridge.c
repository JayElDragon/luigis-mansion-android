// Switches on the engine's built-in HD texture replacement for a chosen folder.
// Plain C on purpose: no C++ library needed, so this file builds without the Android NDK.
// It only calls functions that already exist inside libmain.so; nothing in the engine is changed.
#include <jni.h>

// libc / libdl (resolved by the Android linker at load time)
extern void* dlopen(const char*, int);
extern void* dlsym(void*, const char*);

typedef unsigned long usize;
typedef struct { void* w[3]; } Path;      // std::filesystem::path (one std::string, 24 bytes)
typedef struct { void* w[3]; } Group;     // aurora::texture::ReplacementGroup (one std::vector, 24 bytes)

// aurora::io::fs_path_from_string(std::string_view)  -> path  (string_view = pointer + length in two registers)
typedef Path (*FromStringFn)(const char*, usize);
// aurora::texture::load_replacement_directory(const path&, ReplacementOptions{int priority})  -> group
typedef Group (*LoadFn)(const Path*, int);
// aurora::texture::unregister_replacements(const ReplacementGroup&)
typedef void (*UnregisterFn)(const Group*);
typedef int (*RunOnMainFn)(void (*)(void*), void*, int);

static const char kFromString[] = "_ZN6aurora2io19fs_path_from_stringENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEE";
static const char kLoad[] =
    "_ZN6aurora7texture26load_replacement_directoryERKNSt6__ndk14__fs10filesystem4pathENS0_18ReplacementOptionsE";
static const char kUnregister[] = "_ZN6aurora7texture23unregister_replacementsERKNS0_16ReplacementGroupE";

static void* lib;
static Group current;
static int haveGroup;
static char pending[4096];

static void deliver(void* unused) {   // runs on the game's own thread
    (void)unused;
    FromStringFn fromString = (FromStringFn)dlsym(lib, kFromString);
    LoadFn load = (LoadFn)dlsym(lib, kLoad);
    UnregisterFn unregister = (UnregisterFn)dlsym(lib, kUnregister);
    if (haveGroup && unregister) {
        unregister(&current);
        haveGroup = 0;
    }
    if (pending[0] && fromString && load) {
        usize n = 0;
        while (pending[n]) n++;
        Path p = fromString(pending, n);
        current = load(&p, 0);
        haveGroup = 1;
    }
}

// returns 0 = queued, <0 = this engine build can't do it
JNIEXPORT jint JNICALL Java_org_libsdl_app_TexturePacks_nativeApply(JNIEnv* env, jclass cls, jstring path) {
    (void)cls;
    if (!lib) lib = dlopen("libmain.so", 2 /* RTLD_NOW */);
    if (!lib) return -1;
    if (!dlsym(lib, kFromString) || !dlsym(lib, kLoad) || !dlsym(lib, kUnregister)) return -2;
    RunOnMainFn run = (RunOnMainFn)dlsym(lib, "SDL_RunOnMainThread");
    if (!run) return -3;
    const char* p = (*env)->GetStringUTFChars(env, path, 0);
    usize i = 0;
    if (p) {
        while (p[i] && i < sizeof(pending) - 1) { pending[i] = p[i]; i++; }
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    pending[i] = 0;
    if (!run(deliver, 0, 0)) return -4;
    return 0;
}

