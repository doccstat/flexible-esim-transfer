#include <string>
#include <vector>
#include <mutex>
#include <array>
#include <sys/socket.h>
#include <sys/time.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include "zygisk.hpp"
#include "lsplant.hpp"
#include "dobby.h"
#include "policy.hpp"
#include "art_symbols.hpp"

namespace {
int settings_fd = -1;
std::mutex settings_mutex;
constexpr const char *config_path = "/data/adb/flexible-esim-transfer/config";
constexpr const char *tag = "FlexibleEsimTransfer";

bool transfer(int fd, void *bytes, size_t length, bool write) {
    auto p = static_cast<char *>(bytes);
    while (length) {
        ssize_t count = write ? send(fd, p, length, MSG_NOSIGNAL) : recv(fd, p, length, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        p += count; length -= count;
    }
    return true;
}
std::string read_config() {
    int fd = open(config_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return {};
    struct stat st{};
    std::array<char, 129> bytes{};
    ssize_t n = -1;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 && (st.st_mode & 077) == 0)
        n = read(fd, bytes.data(), bytes.size());
    close(fd);
    return n > 0 && n <= 128 ? std::string(bytes.data(), n) : std::string{};
}
void companion(int fd) {
    // An inherited, read-only connection: no command may mutate state or address arbitrary files.
    for (char command; transfer(fd, &command, 1, false) && command == 'R';) {
        std::string text = read_config();
        uint8_t length = text.size();
        if (!transfer(fd, &length, 1, true) || !transfer(fd, text.data(), text.size(), true)) break;
    }
}
jstring settings(JNIEnv *env, jclass) {
    std::lock_guard lock(settings_mutex);
    char command = 'R'; uint8_t length = 0;
    std::array<char, 129> bytes{};
    if (settings_fd < 0 || !transfer(settings_fd, &command, 1, true)
            || !transfer(settings_fd, &length, 1, false) || !length || length > 128
            || !transfer(settings_fd, bytes.data(), length, false)) {
        if (settings_fd >= 0) close(settings_fd);
        settings_fd = -1; // A partial response cannot become a later, stale settings snapshot.
        return nullptr;
    }
    for (size_t i = 0; i < length; i++) if (bytes[i] != '\n' && (bytes[i] < '0' || bytes[i] > 'z')) return nullptr;
    return env->NewStringUTF(bytes.data());
}
jobject hook(JNIEnv *env, jclass, jobject target, jobject holder, jobject callback) {
    return lsplant::Hook(env, target, holder, callback);
}
jboolean deoptimize(JNIEnv *env, jclass, jobject method) { return lsplant::Deoptimize(env, method); }

bool bootstrap(JNIEnv *env, const std::vector<jbyte> &dex, const std::string &package) {
    if (env->PushLocalFrame(32) != JNI_OK) {
        __android_log_print(ANDROID_LOG_INFO, tag, "bootstrap: FAILED:DEX_LOADER");
        return false;
    }
    const char *stage = "DEX_LOADER";
    bool result = [&]() {
        auto bytes = env->NewByteArray(dex.size());
        if (!bytes) return false;
        env->SetByteArrayRegion(bytes, 0, dex.size(), dex.data());
        if (env->ExceptionCheck()) return false;
        auto buffer_type = env->FindClass("java/nio/ByteBuffer");
        if (!buffer_type) return false;
        auto wrap = env->GetStaticMethodID(buffer_type, "wrap", "([B)Ljava/nio/ByteBuffer;");
        if (!wrap) return false;
        auto buffer = env->CallStaticObjectMethod(buffer_type, wrap, bytes);
        if (!buffer || env->ExceptionCheck()) return false;
        auto loader_type = env->FindClass("java/lang/ClassLoader");
        if (!loader_type) return false;
        auto system = env->GetStaticMethodID(loader_type, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        if (!system) return false;
        auto parent = env->CallStaticObjectMethod(loader_type, system);
        if (env->ExceptionCheck()) return false;
        auto memory_type = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (!memory_type) return false;
        auto constructor = env->GetMethodID(memory_type, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        if (!constructor) return false;
        auto loader = env->NewObject(memory_type, constructor, buffer, parent);
        if (!loader || env->ExceptionCheck()) return false;
        auto load = env->GetMethodID(loader_type, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (!load) return false;
        auto load_class = [&](const char *name) { return static_cast<jclass>(env->CallObjectMethod(loader, load, env->NewStringUTF(name))); };
        if (env->ExceptionCheck()) return false;
        auto native = load_class("com.lixingchi.flexibleesimtransfer.NativeHooks");
        if (!native || env->ExceptionCheck()) return false;
        JNINativeMethod methods[] = {
            {const_cast<char *>("hook"), const_cast<char *>("(Ljava/lang/reflect/Method;Lcom/lixingchi/flexibleesimtransfer/NativeHooks;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;"), reinterpret_cast<void *>(hook)},
            {const_cast<char *>("deoptimize"), const_cast<char *>("(Ljava/lang/reflect/Executable;)Z"), reinterpret_cast<void *>(deoptimize)},
            {const_cast<char *>("settings"), const_cast<char *>("()Ljava/lang/String;"), reinterpret_cast<void *>(settings)}
        };
        if (env->RegisterNatives(native, methods, 3) != JNI_OK) return false;
        stage = "ART_INIT";
        fet::ArtSymbols art;
        lsplant::InitInfo info{
            .inline_hooker = [](void *target, void *replace) -> void * { void *original = nullptr; return DobbyHook(target, replace, &original) == 0 ? original : nullptr; },
            .inline_unhooker = [](void *target) { return DobbyDestroy(target) == 0; },
            .art_symbol_resolver = [&](std::string_view symbol) { return art.find(symbol); },
            .art_symbol_prefix_resolver = [&](std::string_view symbol) { return art.find(symbol, true); }
        };
        if (!lsplant::Init(env, info)) return false;
        stage = "DEX_TRUST";
        // Trust only this payload's dex cookies, not the application's hidden-API policy.
        auto base_loader = env->FindClass("dalvik/system/BaseDexClassLoader");
        if (!base_loader) return false;
        auto path_field = env->GetFieldID(base_loader, "pathList", "Ldalvik/system/DexPathList;");
        if (!path_field) return false;
        auto path_list = env->GetObjectField(loader, path_field);
        if (!path_list || env->ExceptionCheck()) return false;
        auto list_type = env->FindClass("dalvik/system/DexPathList");
        if (!list_type) return false;
        auto elements_field = env->GetFieldID(list_type, "dexElements", "[Ldalvik/system/DexPathList$Element;");
        if (!elements_field) return false;
        auto elements = static_cast<jobjectArray>(env->GetObjectField(path_list, elements_field));
        if (!elements || env->ExceptionCheck()) return false;
        auto element_type = env->FindClass("dalvik/system/DexPathList$Element");
        if (!element_type) return false;
        auto file_field = env->GetFieldID(element_type, "dexFile", "Ldalvik/system/DexFile;");
        if (!file_field) return false;
        auto file_type = env->FindClass("dalvik/system/DexFile");
        if (!file_type) return false;
        auto cookie_field = env->GetFieldID(file_type, "mCookie", "Ljava/lang/Object;");
        if (!cookie_field) return false;
        for (jsize i = 0; i < env->GetArrayLength(elements); i++) {
            auto element = env->GetObjectArrayElement(elements, i);
            if (!element || env->ExceptionCheck()) return false;
            auto file = env->GetObjectField(element, file_field);
            if (!file || env->ExceptionCheck()) return false;
            auto cookie = env->GetObjectField(file, cookie_field);
            if (!cookie || env->ExceptionCheck() || !lsplant::MakeDexFileTrusted(env, cookie)) return false;
        }
        stage = "JAVA_ENTRY";
        auto entry = load_class("com.lixingchi.flexibleesimtransfer.ZygiskEntry");
        if (!entry || env->ExceptionCheck()) return false;
        auto start = env->GetStaticMethodID(entry, "start", "(Ljava/lang/String;)V");
        if (!start) return false;
        env->CallStaticVoidMethod(entry, start, env->NewStringUTF(package.c_str()));
        return !env->ExceptionCheck();
    }();
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->PopLocalFrame(nullptr);
    if (result) __android_log_print(ANDROID_LOG_INFO, tag, "bootstrap: JAVA_ENTRY");
    else __android_log_print(ANDROID_LOG_INFO, tag, "bootstrap: FAILED:%s", stage);
    return result;
}
class Module : public zygisk::ModuleBase {
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    std::string package;
    const char *failure = nullptr;
    std::vector<jbyte> dex;
public:
    void onLoad(zygisk::Api *a, JNIEnv *e) override { api = a; env = e; }
    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override { api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (args->nice_name && args->app_data_dir) {
            const char *name = env->GetStringUTFChars(args->nice_name, nullptr);
            const char *dir = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (name && dir) package = fet::select(args->uid, args->is_child_zygote && *args->is_child_zygote, name, dir);
            if (name) env->ReleaseStringUTFChars(args->nice_name, name);
            if (dir) env->ReleaseStringUTFChars(args->app_data_dir, dir);
        }
        if (env->ExceptionCheck()) { env->ExceptionClear(); package.clear(); }
        if (package.empty()) { api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        int directory = api->getModuleDir();
        int fd = directory < 0 ? -1 : openat(directory, "payload.dex", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (directory >= 0) close(directory);
        struct stat st{};
        if (fd >= 0 && fstat(fd, &st) == 0 && st.st_size > 0 && st.st_size < 8 * 1024 * 1024) {
            dex.resize(st.st_size); size_t offset = 0;
            while (offset < dex.size()) {
                ssize_t count = read(fd, dex.data() + offset, dex.size() - offset);
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) { dex.clear(); break; }
                offset += count;
            }
        }
        if (fd >= 0) close(fd);
        settings_fd = api->connectCompanion();
        failure = dex.empty() ? "PAYLOAD" : settings_fd < 0 ? "COMPANION"
                : !api->exemptFd(settings_fd) ? "COMPANION_FD" : nullptr;
        if (failure) {
            if (settings_fd >= 0) close(settings_fd);
            settings_fd = -1; dex.clear();
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        } else {
            timeval timeout{2, 0};
            setsockopt(settings_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            setsockopt(settings_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (package.empty()) return;
        // Emit after UID specialization so the opt-in app-UID recorder can retain startup failures.
        __android_log_print(ANDROID_LOG_INFO, tag, "bootstrap: SELECTED");
        if (failure) {
            __android_log_print(ANDROID_LOG_INFO, tag, "bootstrap: FAILED:%s", failure);
            return;
        }
        bool ok = bootstrap(env, dex, package);
        dex.clear(); dex.shrink_to_fit();
        if (!ok) __android_log_print(ANDROID_LOG_INFO, tag, "hooks: INCOMPLETE;new_downloads_blocked_where_guards_loaded");
        // LSPlant may already own native hooks even after partial failure: never unload here.
    }
};
}
REGISTER_ZYGISK_MODULE(Module)
REGISTER_ZYGISK_COMPANION(companion)
