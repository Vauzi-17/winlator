#include <jni.h>
#include <libgen.h>
#include <sys/stat.h>
#include <limits.h>
#include <stdio.h>
#include <android/hardware_buffer.h>

#include "vk_context.h"
#include "vortek_serializer.h"
#include "request_handler.h"
#include "vulkan_helper.h"
#include "jni_utils.h"

#include "adrenotools/driver.h"

VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;

static void* openVulkanLibrary(JNIEnv* env, jstring nativeLibraryDir, jstring libvulkanPath) {
    void* libvulkan;
    if (libvulkanPath) {
        const char* nativeLibraryDirC = (*env)->GetStringUTFChars(env, nativeLibraryDir, NULL);
        const char* libvulkanPathC = (*env)->GetStringUTFChars(env, libvulkanPath, NULL);
        const char* libvulkanName = basename(libvulkanPathC);

        char libvulkanDir[PATH_MAX] = {0};
        strcpy(libvulkanDir, dirname(libvulkanPathC));
        strcat(libvulkanDir, "/");

        char* tmpDir;
        asprintf(&tmpDir, "%s%s", libvulkanDir, "tmp");
        mkdir(tmpDir, S_IRWXU | S_IRWXG);

        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibraryDirC, libvulkanDir, libvulkanName, NULL, NULL);

        (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, nativeLibraryDirC);
        (*env)->ReleaseStringUTFChars(env, libvulkanPath, libvulkanPathC);
    }
    else libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);

    if (!libvulkan) println("vortek: unable to open libvulkan: %s", dlerror());
    return libvulkan;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_createVkContext(JNIEnv *env,
                                                                                  jobject obj,
                                                                                  jint clientFd,
                                                                                  jobject options) {
    VkContext* context = createVkContext(env, obj, clientFd, options);
    return context ? (jlong)context : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_destroyVkContext(JNIEnv *env,
                                                                                   jobject obj,
                                                                                   jlong contextPtr) {
    destroyVkContext(env, (VkContext*)contextPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_initVulkanWrapper(JNIEnv *env,
                                                                                    jobject obj,
                                                                                    jstring nativeLibraryDir,
                                                                                    jstring libvulkanPath) {
    void* libvulkan = openVulkanLibrary(env, nativeLibraryDir, libvulkanPath);
    initVulkanWrapper(&vulkanWrapper, libvulkan);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_handleExtraDataRequest(JNIEnv *env,
                                                                                         jobject obj,
                                                                                         jlong contextPtr,
                                                                                         int requestId,
                                                                                         int requestLength) {
    return handleExtraDataRequest((VkContext*)contextPtr, requestId, requestLength);
}

// Window buffers the swapchain renders into, shown by the X server renderer as direct content.
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_createWindowBuffer(JNIEnv *env,
                                                                                       jclass cls,
                                                                                       jint width,
                                                                                       jint height,
                                                                                       jint format) {
    AHardwareBuffer_Desc desc = {0};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = format;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;

    AHardwareBuffer* hardwareBuffer = NULL;
    if (AHardwareBuffer_allocate(&desc, &hardwareBuffer) != 0) {
        println("vortek: unable to allocate a %dx%d window buffer", width, height);
        return 0;
    }
    return (jlong)hardwareBuffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_releaseWindowBuffer(JNIEnv *env,
                                                                                        jclass cls,
                                                                                        jlong hardwareBufferPtr) {
    if (hardwareBufferPtr) AHardwareBuffer_release((AHardwareBuffer*)hardwareBufferPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xenvironment_components_VortekRendererComponent_setCacheDir(JNIEnv *env,
                                                                                jclass cls,
                                                                                jstring cacheDir) {
    const char* cacheDirC = (*env)->GetStringUTFChars(env, cacheDir, NULL);
    snprintf(vortekAppCacheDir, PATH_MAX, "%s", cacheDirC);
    (*env)->ReleaseStringUTFChars(env, cacheDir, cacheDirC);
}
