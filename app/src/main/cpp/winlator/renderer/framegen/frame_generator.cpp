#include "frame_generator.hpp"

#include <android/log.h>
#include <poll.h>
#include <unistd.h>
#include <time.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "lsfg_engine.h"
#include "lsfg_probe.h"
#include "lsfg_vkd.h"

#define LOG_TAG "FrameGenerator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// How long a game frame's acquire fence may take before the frame is shown
// without generation, and how long the frame generation work may take.
constexpr int kSourceFenceTimeoutMs = 250;
constexpr uint64_t kWorkTimeoutNs = 1000000000ull;

// A swapchain image nobody has presented for this many frames is forgotten.
constexpr uint64_t kSourceIdleFrames = 600;

int64_t nowNanos() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

bool hasExtension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
    for (const auto &extension : extensions)
        if (strcmp(extension.extensionName, name) == 0) return true;
    return false;
}

VkImageMemoryBarrier imageBarrier(VkImage image, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                                  VkImageLayout oldLayout, VkImageLayout newLayout,
                                  uint32_t srcQueue = VK_QUEUE_FAMILY_IGNORED,
                                  uint32_t dstQueue = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = srcQueue;
    barrier.dstQueueFamilyIndex = dstQueue;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return barrier;
}

} // namespace

FrameGenerator::FrameGenerator() = default;

// The renderer keeps one FrameGenerator for the life of the process. Tearing
// Vulkan down from a static destructor, after the driver may already be gone,
// is riskier than letting process exit reclaim it.
FrameGenerator::~FrameGenerator() = default;

void FrameGenerator::setConfig(const Config &value, const std::string &path) {
    std::lock_guard<std::mutex> guard(configLock);
    config = value;
    config.multiplier = std::clamp(config.multiplier, 2, (int)lsfg::kMaxGenerations + 1);
    config.flowScale = std::clamp(config.flowScale, 0.25f, 1.0f);
    cachePath = path;
    configDirty = true;
    enabled = config.enabled;
    if (!config.enabled) {
        status = STATUS_OFF;
    }
    else if (status == STATUS_OFF || status == STATUS_NO_SHADERS || status == STATUS_FAILED) {
        // A new cache or new settings get a fresh attempt.
        status = STATUS_WAITING;
        retryRequested = true;
    }
}

void FrameGenerator::setRefreshRate(float value) {
    if (value > 1.0f) {
        refreshRate = value;
        std::lock_guard<std::mutex> guard(configLock);
        configDirty = true;
    }
}

std::string FrameGenerator::getStatusText() {
    std::lock_guard<std::mutex> guard(configLock);
    return reason;
}

void FrameGenerator::setStatus(Status value, const char *why) {
    status = value;
    std::lock_guard<std::mutex> guard(configLock);
    snprintf(reason, sizeof(reason), "%s", why ? why : "");
    if (why && *why) LOGI("status %d: %s", (int)value, why);
}

int FrameGenerator::findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (memoryProperties.memoryTypes[i].propertyFlags & flags) == flags)
            return (int)i;
    }
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; i++) {
        if (bits & (1u << i)) return (int)i;
    }
    return -1;
}

bool FrameGenerator::ensureDevice() {
    if (deviceReady) return true;
    if (deviceTried) return false;
    deviceTried = true;

    vk.GetInstanceProcAddr = vkGetInstanceProcAddr;
    vk.CreateInstance = reinterpret_cast<PFN_vkCreateInstance>(vkGetInstanceProcAddr(nullptr, "vkCreateInstance"));
    vk.EnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
    if (!vk.CreateInstance) {
        setStatus(STATUS_UNSUPPORTED, "Vulkan is not available");
        return false;
    }

    uint32_t loaderVersion = VK_API_VERSION_1_1;
    if (vk.EnumerateInstanceVersion) vk.EnumerateInstanceVersion(&loaderVersion);

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "FrameGenerator";
    appInfo.pEngineName = "FrameGenerator";
    appInfo.apiVersion = std::min<uint32_t>(std::max<uint32_t>(loaderVersion, VK_API_VERSION_1_1), VK_API_VERSION_1_3);

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    if (vk.CreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) {
        setStatus(STATUS_UNSUPPORTED, "could not create a Vulkan instance");
        return false;
    }

#define LOAD_INSTANCE(fn) vk.fn = reinterpret_cast<PFN_vk##fn>(vkGetInstanceProcAddr(instance, "vk" #fn));
    LSFG_VK_INSTANCE_FUNCTIONS(LOAD_INSTANCE)
#undef LOAD_INSTANCE

    uint32_t deviceCount = 0;
    if (!vk.EnumeratePhysicalDevices || vk.EnumeratePhysicalDevices(instance, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) {
        setStatus(STATUS_UNSUPPORTED, "no Vulkan device");
        destroyDevice();
        return false;
    }
    std::vector<VkPhysicalDevice> physicalDevices(deviceCount);
    vk.EnumeratePhysicalDevices(instance, &deviceCount, physicalDevices.data());
    physicalDevice = physicalDevices[0];
    vk.GetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);

    uint32_t extensionCount = 0;
    vk.EnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount, nullptr);
    std::vector<VkExtensionProperties> extensions(extensionCount);
    if (extensionCount) vk.EnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount, extensions.data());

    if (!hasExtension(extensions, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) {
        setStatus(STATUS_UNSUPPORTED, "driver cannot import AHardwareBuffers");
        destroyDevice();
        return false;
    }

    // The stock driver of many Adreno phones reports Vulkan 1.1 but carries the
    // extensions that let the chain load as SPIR-V 1.4, so that path is allowed.
    lsfg::Caps caps{};
    caps.features = lsfg::queryFeatures(vk, physicalDevice, extensions, /*allowVk11=*/true);
    if (!caps.features.deviceGatesPass()) {
        caps.featuresEnabled = true;
        caps.storageOnSwapchainFormat = true;
        lsfg::explain(caps);
        setStatus(STATUS_UNSUPPORTED, caps.reason);
        destroyDevice();
        return false;
    }
    spirvTarget = caps.features.spirvTarget;

    uint32_t familyCount = 0;
    vk.GetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    vk.GetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, families.data());
    queueFamily = UINT32_MAX;
    for (uint32_t i = 0; i < familyCount; i++) {
        if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            queueFamily = i;
            if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) break;
        }
    }
    if (queueFamily == UINT32_MAX) {
        setStatus(STATUS_UNSUPPORTED, "no compute queue");
        destroyDevice();
        return false;
    }

    std::vector<const char *> deviceExtensions = {VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME};
    if (hasExtension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        deviceExtensions.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        externalQueueFamily = VK_QUEUE_FAMILY_FOREIGN_EXT;
    }
    else {
        externalQueueFamily = VK_QUEUE_FAMILY_EXTERNAL;
    }
    for (const char *name : lsfg::extensionNames(caps.features)) deviceExtensions.push_back(name);

    VkPhysicalDeviceVulkan12Features features12{};
    VkPhysicalDeviceVulkanMemoryModelFeaturesKHR memoryModel{};
    VkPhysicalDeviceFeatures2 features2{};
    const bool khrPath = caps.features.deviceApiVersion < VK_API_VERSION_1_2;
    if (khrPath) {
        memoryModel.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES_KHR;
        memoryModel.vulkanMemoryModel = VK_TRUE;
        memoryModel.vulkanMemoryModelDeviceScope = caps.features.vulkanMemoryModelDeviceScope ? VK_TRUE : VK_FALSE;
    }
    else {
        features12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        features12.vulkanMemoryModel = VK_TRUE;
        features12.vulkanMemoryModelDeviceScope = caps.features.vulkanMemoryModelDeviceScope ? VK_TRUE : VK_FALSE;
    }
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features2.pNext = khrPath ? (void *)&memoryModel : (void *)&features12;
    features2.features.shaderStorageImageWriteWithoutFormat = VK_TRUE;
    features2.features.shaderStorageImageExtendedFormats = VK_TRUE;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.pNext = &features2;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = (uint32_t)deviceExtensions.size();
    deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();

    VkResult result = vk.CreateDevice(physicalDevice, &deviceInfo, nullptr, &device);
    if (result != VK_SUCCESS) {
        char why[96];
        snprintf(why, sizeof(why), "the driver refused the frame generation device (%d)", (int)result);
        setStatus(STATUS_UNSUPPORTED, why);
        device = VK_NULL_HANDLE;
        destroyDevice();
        return false;
    }

#define LOAD_DEVICE(fn) vk.fn = reinterpret_cast<PFN_vk##fn>(vk.GetDeviceProcAddr(device, "vk" #fn));
    LSFG_VK_DEVICE_FUNCTIONS(LOAD_DEVICE)
#undef LOAD_DEVICE

    if (!lsfgVkdInit(vk) || !vk.GetAndroidHardwareBufferPropertiesANDROID || !vk.QueueSubmit) {
        setStatus(STATUS_UNSUPPORTED, "driver is missing Vulkan entry points");
        destroyDevice();
        return false;
    }

    vk.GetDeviceQueue(device, queueFamily, 0, &queue);

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    VkCommandBufferAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = 1;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

    if (vk.CreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS ||
        (allocateInfo.commandPool = commandPool,
         vk.AllocateCommandBuffers(device, &allocateInfo, &commandBuffer) != VK_SUCCESS) ||
        vk.CreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        setStatus(STATUS_FAILED, "could not create command objects");
        destroyDevice();
        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vk.GetPhysicalDeviceProperties(physicalDevice, &properties);
    LOGI("device ready: %s, Vulkan %u.%u.%u, SPIR-V target 0x%x%s", properties.deviceName,
         VK_VERSION_MAJOR(properties.apiVersion), VK_VERSION_MINOR(properties.apiVersion),
         VK_VERSION_PATCH(properties.apiVersion), spirvTarget,
         caps.features.extensionPath ? " (extension path)" : "");
    deviceReady = true;
    return true;
}

bool FrameGenerator::ensureEngine(const std::string &path) {
    if (engine && engineCachePath == path) return true;
    if (engineFailed && engineCachePath == path) return false;

    if (engine) {
        vk.DeviceWaitIdle(device);
        destroyGenRing();
        engine.reset();
    }
    engineCachePath = path;
    engineFailed = false;

    if (path.empty() || access(path.c_str(), R_OK) != 0) {
        engineFailed = true;
        setStatus(STATUS_NO_SHADERS, "no shader cache: import Lossless.dll in Settings");
        return false;
    }

    auto candidate = std::make_unique<lsfg::Engine>();
    if (!candidate->init(device, physicalDevice, path, spirvTarget)) {
        engineFailed = true;
        setStatus(STATUS_FAILED, "the shader cache could not be loaded on this device");
        return false;
    }
    engine = std::move(candidate);
    sourceFrames = 0;
    std::lock_guard<std::mutex> guard(configLock);
    configDirty = true;
    return true;
}

bool FrameGenerator::importAhb(AHardwareBuffer *ahb, VkImageUsageFlags usage, VkImage &image, VkDeviceMemory &memory, VkFormat *format) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
    formatProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID properties{};
    properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    properties.pNext = &formatProperties;
    if (vk.GetAndroidHardwareBufferPropertiesANDROID(device, ahb, &properties) != VK_SUCCESS) return false;
    // A driver-private (external) format cannot be copied from or into.
    if (formatProperties.format == VK_FORMAT_UNDEFINED) return false;

    VkExternalMemoryImageCreateInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = formatProperties.format;
    imageInfo.extent = {desc.width, desc.height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk.CreateImage(device, &imageInfo, nullptr, &image) != VK_SUCCESS) {
        image = VK_NULL_HANDLE;
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = ahb;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = image;
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.pNext = &dedicatedInfo;
    allocateInfo.allocationSize = properties.allocationSize;
    int memoryType = findMemoryType(properties.memoryTypeBits, 0);
    if (memoryType < 0) {
        vk.DestroyImage(device, image, nullptr);
        image = VK_NULL_HANDLE;
        return false;
    }
    allocateInfo.memoryTypeIndex = (uint32_t)memoryType;
    if (vk.AllocateMemory(device, &allocateInfo, nullptr, &memory) != VK_SUCCESS) {
        vk.DestroyImage(device, image, nullptr);
        image = VK_NULL_HANDLE;
        memory = VK_NULL_HANDLE;
        return false;
    }
    if (vk.BindImageMemory(device, image, memory, 0) != VK_SUCCESS) {
        vk.DestroyImage(device, image, nullptr);
        vk.FreeMemory(device, memory, nullptr);
        image = VK_NULL_HANDLE;
        memory = VK_NULL_HANDLE;
        return false;
    }
    if (format) *format = formatProperties.format;
    return true;
}

FrameGenerator::SourceImage *FrameGenerator::importSource(AHardwareBuffer *ahb) {
    auto it = sources.find(ahb);
    if (it != sources.end()) {
        it->second.lastUsed = frameCounter;
        return &it->second;
    }

    // Forget swapchain images the game no longer presents (it recreated its
    // swapchain). The import holds a reference on each buffer, so a stale
    // pointer can never alias a new buffer while it is still in this map.
    for (auto entry = sources.begin(); entry != sources.end();) {
        if (frameCounter - entry->second.lastUsed > kSourceIdleFrames) {
            vk.DestroyImage(device, entry->second.image, nullptr);
            vk.FreeMemory(device, entry->second.memory, nullptr);
            entry = sources.erase(entry);
        }
        else {
            ++entry;
        }
    }

    SourceImage source{};
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    source.width = desc.width;
    source.height = desc.height;
    source.ahbFormat = desc.format;
    source.lastUsed = frameCounter;
    if (!importAhb(ahb, VK_IMAGE_USAGE_TRANSFER_SRC_BIT, source.image, source.memory, &source.format))
        return nullptr;
    return &(sources[ahb] = source);
}

VkFormat FrameGenerator::chainFormatFor(VkFormat format) {
    auto usable = [&](VkFormat candidate) {
        VkFormatProperties properties{};
        vk.GetPhysicalDeviceFormatProperties(physicalDevice, candidate, &properties);
        const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                          VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                          VK_FORMAT_FEATURE_TRANSFER_SRC_BIT |
                                          VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return (properties.optimalTilingFeatures & need) == need;
    };
    if (usable(format)) return format;
    // Formats that are not storage-capable (sRGB, BGRA) share their texel size
    // with RGBA8, and vkCmdCopyImage copies raw texels between size-compatible
    // formats, so the chain runs on the same bytes in RGBA8.
    switch (format) {
        case VK_FORMAT_R8G8B8A8_SRGB:
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
            return usable(VK_FORMAT_R8G8B8A8_UNORM) ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_UNDEFINED;
        default:
            return VK_FORMAT_UNDEFINED;
    }
}

bool FrameGenerator::ensureOutputs(const AHardwareBuffer_Desc &desc, uint32_t count) {
    if (outputRing.size() >= count && outputWidth == desc.width && outputHeight == desc.height && outputAhbFormat == desc.format)
        return true;
    if (!outputRing.empty()) {
        vk.DeviceWaitIdle(device);
        destroyOutputs();
    }

    AHardwareBuffer_Desc outDesc{};
    outDesc.width = desc.width;
    outDesc.height = desc.height;
    outDesc.layers = 1;
    outDesc.format = desc.format;
    outDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                    AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;

    for (uint32_t i = 0; i < count; i++) {
        OutputImage output{};
        if (AHardwareBuffer_allocate(&outDesc, &output.ahb) != 0) {
            outDesc.usage &= ~AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
            if (AHardwareBuffer_allocate(&outDesc, &output.ahb) != 0) {
                destroyOutputs();
                return false;
            }
        }
        if (!importAhb(output.ahb, VK_IMAGE_USAGE_TRANSFER_DST_BIT, output.image, output.memory, nullptr)) {
            AHardwareBuffer_release(output.ahb);
            destroyOutputs();
            return false;
        }
        outputRing.push_back(output);
    }
    outputWidth = desc.width;
    outputHeight = desc.height;
    outputAhbFormat = desc.format;
    outputNext = 0;
    LOGI("output ring %ux%u x%u", desc.width, desc.height, count);
    return true;
}

bool FrameGenerator::ensureGenRing(uint32_t width, uint32_t height, VkFormat format, uint32_t count) {
    if (genRing.size() == count && genWidth == width && genHeight == height && genFormat == format) return true;
    if (!genRing.empty()) {
        vk.DeviceWaitIdle(device);
        destroyGenRing();
    }

    for (uint32_t i = 0; i < count; i++) {
        GenImage gen{};
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                          VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vk.CreateImage(device, &imageInfo, nullptr, &gen.image) != VK_SUCCESS) {
            destroyGenRing();
            return false;
        }
        genRing.push_back(gen);
        GenImage &slot = genRing.back();

        VkMemoryRequirements requirements{};
        vk.GetImageMemoryRequirements(device, slot.image, &requirements);
        int memoryType = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkMemoryAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocateInfo.allocationSize = requirements.size;
        allocateInfo.memoryTypeIndex = (uint32_t)std::max(memoryType, 0);
        if (memoryType < 0 || vk.AllocateMemory(device, &allocateInfo, nullptr, &slot.memory) != VK_SUCCESS ||
            vk.BindImageMemory(device, slot.image, slot.memory, 0) != VK_SUCCESS) {
            destroyGenRing();
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = slot.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vk.CreateImageView(device, &viewInfo, nullptr, &slot.view) != VK_SUCCESS) {
            destroyGenRing();
            return false;
        }
    }
    genWidth = width;
    genHeight = height;
    genFormat = format;
    return true;
}

bool FrameGenerator::process(AHardwareBuffer *source, int syncFence, std::vector<AHardwareBuffer *> &outputs) {
    outputs.clear();
    if (!enabled || !source) return false;

    Config current;
    std::string path;
    bool dirty;
    {
        std::lock_guard<std::mutex> guard(configLock);
        current = config;
        path = cachePath;
        dirty = configDirty;
        configDirty = false;
    }
    if (!current.enabled) return false;

    if (!ensureDevice()) return false;
    if (retryRequested.exchange(false)) {
        // The user changed something (or imported a new Lossless.dll): rebuild.
        if (engine) {
            vk.DeviceWaitIdle(device);
            destroyGenRing();
            engine.reset();
        }
        engineFailed = false;
    }
    if (!ensureEngine(path)) return false;

    frameCounter++;
    SourceImage *src = importSource(source);
    if (!src) {
        setStatus(STATUS_FAILED, "the game's frames cannot be imported (unsupported format)");
        return false;
    }

    const VkFormat chainFormat = chainFormatFor(src->format);
    if (chainFormat == VK_FORMAT_UNDEFINED) {
        char why[96];
        snprintf(why, sizeof(why), "frame format %d is not supported", (int)src->format);
        setStatus(STATUS_FAILED, why);
        return false;
    }

    if (dirty) {
        engine->configure((uint32_t)current.multiplier, 0, current.flowScale, refreshRate.load());
        engine->setGovernorEnabled(current.adaptive);
    }

    const uint32_t width = src->width, height = src->height;
    engine->setGuestExtent(width, height);
    if (!engine->prepare(width, height, chainFormat)) {
        if (engine->unavailable()) setStatus(STATUS_FAILED, "the frame generation chain could not be built");
        return false;
    }

    const uint32_t maxGenerations = (uint32_t)current.multiplier - 1;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(source, &desc);
    // Up to one batch waiting in DisplayX, one being written and the few the
    // compositor still holds.
    if (!ensureOutputs(desc, 3 * (maxGenerations + 1) + 3) ||
        !ensureGenRing(width, height, chainFormat, maxGenerations)) {
        setStatus(STATUS_FAILED, "out of memory for frame generation buffers");
        return false;
    }

    // The game's frame must be finished before it is read.
    if (syncFence >= 0) {
        struct pollfd pfd{syncFence, POLLIN, 0};
        poll(&pfd, 1, kSourceFenceTimeoutMs);
    }

    engine->setPresentedRate(presentedRate.load());
    uint32_t generations = engine->plan((uint32_t)genRing.size(), ++sourceFrames);
    generations = std::min<uint32_t>(generations, (uint32_t)genRing.size());

    vk.ResetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.BeginCommandBuffer(commandBuffer, &beginInfo);

    // Take the game's buffer from the producer. The engine expects it in GENERAL.
    VkImageMemoryBarrier acquire = imageBarrier(src->image, 0, VK_ACCESS_TRANSFER_READ_BIT,
                                                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                                                externalQueueFamily, queueFamily);
    std::vector<VkImageMemoryBarrier> barriers{acquire};
    for (uint32_t g = 0; g < generations; g++) {
        GenImage &gen = genRing[g];
        barriers.push_back(imageBarrier(gen.image, gen.fresh ? 0 : VK_ACCESS_TRANSFER_READ_BIT,
                                        VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
                                        gen.fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL,
                                        VK_IMAGE_LAYOUT_GENERAL));
        gen.fresh = false;
    }
    vk.CmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                          0, 0, nullptr, 0, nullptr, (uint32_t)barriers.size(), barriers.data());

    engine->process(commandBuffer, src->image, width, height, generations);
    for (uint32_t g = 0; g < generations; g++)
        engine->generateInto(commandBuffer, g, g, genRing[g].image, genRing[g].view, width, height);

    barriers.clear();
    for (uint32_t g = 0; g < generations; g++) {
        barriers.push_back(imageBarrier(genRing[g].image, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL));
    }
    // process() may have copied the source; make sure the copy below sees it settled.
    barriers.push_back(imageBarrier(src->image, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL));

    // The frames to show, in order: every generated frame, then the real one.
    const uint32_t outputCount = generations + 1;
    std::vector<uint32_t> slots(outputCount);
    for (uint32_t i = 0; i < outputCount; i++) {
        slots[i] = outputNext;
        outputNext = (outputNext + 1) % (uint32_t)outputRing.size();
        barriers.push_back(imageBarrier(outputRing[slots[i]].image, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL));
    }
    vk.CmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                          (uint32_t)barriers.size(), barriers.data());

    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {width, height, 1};
    for (uint32_t i = 0; i < outputCount; i++) {
        VkImage from = i < generations ? genRing[i].image : src->image;
        vk.CmdCopyImage(commandBuffer, from, VK_IMAGE_LAYOUT_GENERAL, outputRing[slots[i]].image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    }

    // Hand every buffer back to the compositor and the game.
    barriers.clear();
    for (uint32_t i = 0; i < outputCount; i++) {
        barriers.push_back(imageBarrier(outputRing[slots[i]].image, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                                        queueFamily, externalQueueFamily));
    }
    barriers.push_back(imageBarrier(src->image, VK_ACCESS_TRANSFER_READ_BIT, 0,
                                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                                    queueFamily, externalQueueFamily));
    vk.CmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                          0, 0, nullptr, 0, nullptr, (uint32_t)barriers.size(), barriers.data());

    vk.EndCommandBuffer(commandBuffer);

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    vk.ResetFences(device, 1, &fence);
    VkResult result = vk.QueueSubmit(queue, 1, &submitInfo, fence);
    if (result == VK_SUCCESS) result = vk.WaitForFences(device, 1, &fence, VK_TRUE, kWorkTimeoutNs);
    if (result != VK_SUCCESS) {
        char why[96];
        snprintf(why, sizeof(why), "frame generation work failed (%d)", (int)result);
        setStatus(STATUS_FAILED, why);
        vk.DeviceWaitIdle(device);
        enabled = false;
        return false;
    }

    if (syncFence >= 0) close(syncFence);
    for (uint32_t i = 0; i < outputCount; i++) outputs.push_back(outputRing[slots[i]].ahb);

    if (status != STATUS_RUNNING) {
        char why[128];
        snprintf(why, sizeof(why), "running at %ux%u, x%d, flow scale %.2f%s", width, height,
                 current.multiplier, (double)current.flowScale, current.adaptive ? ", adaptive" : "");
        setStatus(STATUS_RUNNING, why);
    }
    return true;
}

void FrameGenerator::onPresented(int frames) {
    int64_t now = nowNanos();
    if (rateWindowStart == 0) {
        rateWindowStart = now;
        rateAccum = 0;
    }
    rateAccum += (uint32_t)std::max(frames, 0);
    float elapsed = (float)(now - rateWindowStart) / 1e9f;
    if (elapsed < 0.5f) return;
    float rate = (float)rateAccum / elapsed;
    float previous = presentedRate.load();
    presentedRate = previous > 0.0f ? previous + (rate - previous) * 0.25f : rate;
    rateWindowStart = now;
    rateAccum = 0;
}

void FrameGenerator::destroySources() {
    for (auto &entry : sources) {
        if (entry.second.image) vk.DestroyImage(device, entry.second.image, nullptr);
        if (entry.second.memory) vk.FreeMemory(device, entry.second.memory, nullptr);
    }
    sources.clear();
}

void FrameGenerator::destroyOutputs() {
    for (auto &output : outputRing) {
        if (output.image) vk.DestroyImage(device, output.image, nullptr);
        if (output.memory) vk.FreeMemory(device, output.memory, nullptr);
        if (output.ahb) AHardwareBuffer_release(output.ahb);
    }
    outputRing.clear();
    outputNext = 0;
    outputWidth = outputHeight = outputAhbFormat = 0;
}

void FrameGenerator::destroyGenRing() {
    for (auto &gen : genRing) {
        if (gen.view) vk.DestroyImageView(device, gen.view, nullptr);
        if (gen.image) vk.DestroyImage(device, gen.image, nullptr);
        if (gen.memory) vk.FreeMemory(device, gen.memory, nullptr);
    }
    genRing.clear();
    genWidth = genHeight = 0;
    genFormat = VK_FORMAT_UNDEFINED;
    if (engine) engine->forgetTargets();
}

void FrameGenerator::destroyDevice() {
    if (device) {
        if (vk.DeviceWaitIdle) vk.DeviceWaitIdle(device);
        destroyGenRing();
        engine.reset();
        destroySources();
        destroyOutputs();
        if (fence) vk.DestroyFence(device, fence, nullptr);
        if (commandPool) vk.DestroyCommandPool(device, commandPool, nullptr);
        if (vk.DestroyDevice) vk.DestroyDevice(device, nullptr);
    }
    fence = VK_NULL_HANDLE;
    commandPool = VK_NULL_HANDLE;
    commandBuffer = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    if (instance && vk.DestroyInstance) vk.DestroyInstance(instance, nullptr);
    instance = VK_NULL_HANDLE;
    physicalDevice = VK_NULL_HANDLE;
    deviceReady = false;
}

void FrameGenerator::release() {
    destroyDevice();
    engineCachePath.clear();
    engineFailed = false;
    deviceTried = false;
}
