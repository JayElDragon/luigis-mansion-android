#include <jni.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <android/log.h>

namespace {
void* lib;
template<class T> T sym(const char* n) { return reinterpret_cast<T>(dlsym(lib,n)); }
char settings[2048];
char selected[80] = "auto";
struct Guid { unsigned char bytes[16]; };
struct Event { int buttons; float lx,ly,rx,ry,lt,rt; };
void selectInput(const char* device) {
    if (!strcmp(selected,device)) return;
    FILE* in=fopen(settings,"r");
    if(!in) return;
    char temporary[2080]; snprintf(temporary,sizeof temporary,"%s.input.tmp",settings);
    FILE* out=fopen(temporary,"w");
    if(!out) { fclose(in); return; }
    char line[4096]; bool controls=false, written=false;
    while(fgets(line,sizeof line,in)) {
        if(line[0]=='[') controls=!strncmp(line,"[controls]",10);
        if(controls && !strncmp(line,"port1_device=",13)) {
            fprintf(out,"port1_device=%s\n",device); written=true;
        } else fputs(line,out);
    }
    if(!written) fprintf(out,"\n[controls]\nport1_device=%s\n",device);
    bool ok=!ferror(out); fclose(in); ok=fclose(out)==0 && ok;
    if(!ok || rename(temporary,settings)) { remove(temporary); return; }
    auto reload=sym<void(*)()>("_ZN4port12loadSettingsEv");
    if(reload) { reload(); snprintf(selected,sizeof selected,"%s",device); }
    __android_log_print(ANDROID_LOG_INFO,"LMTouch","Player 1 input: %s",selected);
}
void deliver(void* ptr) {
    Event e=*static_cast<Event*>(ptr); delete static_cast<Event*>(ptr);
    if(e.buttons==-2) { selectInput("auto"); return; }
    if(e.buttons==-3) { auto f=sym<void(*)()>("_ZN4port12loadSettingsEv"); if(f) f(); return; }
    auto touch=sym<void(*)(JNIEnv*,jclass,int,float,float,float,float,float,float)>("Java_org_libsdl_app_MyGame_nativeTouchPad");
    if(!touch) return;
    // Create the virtual pad before routing it to player one. All operations run on SDL's thread.
    touch(nullptr,nullptr,e.buttons,e.lx,e.ly,e.rx,e.ry,e.lt,e.rt);
    if(e.buttons<0) { selectInput("auto"); return; }
    int count=0;
    auto list=sym<unsigned*(*)(int*)>("SDL_GetJoysticks");
    auto isVirtual=sym<bool(*)(unsigned)>("SDL_IsJoystickVirtual");
    unsigned* ids=list?list(&count):nullptr;
    for(int i=0;ids && i<count;i++) if(isVirtual && isVirtual(ids[i])) {
        auto opened=sym<void*(*)(unsigned)>("SDL_GetGamepadFromID");
        auto open=sym<void*(*)(unsigned)>("SDL_OpenGamepad");
        if(opened && !opened(ids[i]) && open) open(ids[i]);
        auto guid=sym<Guid(*)(unsigned)>("SDL_GetGamepadGUIDForID");
        auto str=sym<void(*)(Guid,char*,int)>("SDL_GUIDToString");
        if(guid && str) { char g[33]={}, device[80]; str(guid(ids[i]),g,33);
            snprintf(device,sizeof device,"pad:%s#0",g); selectInput(device); }
        break;
    }
    auto freeIds=sym<void(*)(void*)>("SDL_free"); if(freeIds) freeIds(ids);
}
}
extern "C" JNIEXPORT void JNICALL Java_org_libsdl_app_MyGame_nativeBridgeInit(JNIEnv* env,jclass,jstring path) {
    lib=dlopen("libmain.so",RTLD_NOW);
    const char* p=env->GetStringUTFChars(path,nullptr);
    snprintf(settings,sizeof settings,"%s",p); env->ReleaseStringUTFChars(path,p);
}
extern "C" JNIEXPORT void JNICALL Java_org_libsdl_app_MyGame_nativeBridgeInput(JNIEnv*,jclass,jint b,jfloat lx,jfloat ly,jfloat rx,jfloat ry,jfloat lt,jfloat rt) {
    if(!lib) return;
    auto run=sym<bool(*)(void(*)(void*),void*,bool)>("SDL_RunOnMainThread");
    auto e=new Event{b,lx,ly,rx,ry,lt,rt};
    if(!run || !run(deliver,e,false)) delete e;
}
