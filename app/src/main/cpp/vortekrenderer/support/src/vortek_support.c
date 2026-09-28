// Helpers Vortek takes from brunodev85's libwinlator (gpu_image.c, sysvshared_memory.c), which
// this app does not ship, plus the runtime cache directory.
#include <android/hardware_buffer.h>
#include <android/sharedmem.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>

#include "native_handle.h"
#include "winlator.h"

char vortekAppCacheDir[PATH_MAX] = "/data/local/tmp";

int ashmemCreateRegion(const char* name, int64_t size) {
    int fd = ASharedMemory_create(name, size);
    return fd < 0 ? -1 : fd;
}

// Not in the NDK stubs, so looked up at runtime.
int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    static const native_handle_t* (*getNativeHandle)(const AHardwareBuffer*) = NULL;
    if (!getNativeHandle) {
        void* handle = dlopen("libnativewindow.so", RTLD_NOW | RTLD_NOLOAD);
        if (!handle) handle = dlopen("libnativewindow.so", RTLD_NOW);
        if (handle) getNativeHandle = dlsym(handle, "AHardwareBuffer_getNativeHandle");
        if (!getNativeHandle) return -1;
    }

    const native_handle_t* nativeHandle = getNativeHandle(hardwareBuffer);
    return nativeHandle && nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}
