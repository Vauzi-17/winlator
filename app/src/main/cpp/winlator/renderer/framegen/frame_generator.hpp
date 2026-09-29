#pragma once
// ============================================================================
// FrameGenerator - LSFG Native frame generation for the DisplayX present path.
//
// DisplayX hands each game frame (an AHardwareBuffer from the displayx layer)
// to process() on its present thread. The frame is imported into the app's own
// Vulkan device (the system driver, not the guest's), fed to the LSFG engine
// (winlator/lsfg), and the interpolated frames plus a copy of the real frame are
// written into AHardwareBuffers this class owns. DisplayX then scans those out
// one per vsync: generated frames first, the real frame last.
//
// The shaders are not shipped: they are translated on device from the user's
// own Lossless.dll (LsfgNative.java), and the path to that cache is passed in.
// ============================================================================

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "lsfg_vktable.h"

namespace lsfg { class Engine; }

class FrameGenerator {
public:
    // Mirrors FrameGeneration.STATUS_* on the Java side.
    enum Status : int {
        STATUS_OFF = 0,
        STATUS_WAITING = 1,       // on, no game frame seen yet
        STATUS_RUNNING = 2,
        STATUS_NO_SHADERS = 3,    // no shader cache: Lossless.dll not imported
        STATUS_UNSUPPORTED = 4,   // the device fails a capability gate
        STATUS_FAILED = 5,        // the engine or its chain could not be built
    };

    struct Config {
        bool enabled = false;
        int multiplier = 2;       // 2..4
        float flowScale = 0.8f;   // 0.25..1.0, lower is faster
        bool adaptive = false;    // let the governor earn each generation
    };

    FrameGenerator();
    ~FrameGenerator();

    FrameGenerator(const FrameGenerator&) = delete;
    FrameGenerator& operator=(const FrameGenerator&) = delete;

    // Any thread.
    void setConfig(const Config& config, const std::string& cachePath);
    void setRefreshRate(float refreshRate);
    bool isEnabled() const { return enabled.load(); }
    int getStatus() const { return status.load(); }
    std::string getStatusText();
    float getPresentedRate() const { return presentedRate.load(); }

    // Present thread only. Returns false when the frame should be presented as
    // usual (generation off or impossible); `syncFence` is then untouched.
    // Returns true once `outputs` holds the frames to show, generated ones
    // first and the real frame last, all complete (no fence); `syncFence` has
    // been waited on and closed.
    bool process(AHardwareBuffer *source, int syncFence, std::vector<AHardwareBuffer *> &outputs);

    // Present thread only: count what reached the screen, for the pacer.
    void onPresented(int frames);

    // Present thread only: drop every GPU object (the engine is rebuilt on
    // demand).
    void release();

private:
    struct SourceImage {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t ahbFormat = 0;
        uint64_t lastUsed = 0;
    };

    struct OutputImage {
        AHardwareBuffer *ahb = nullptr;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
    };

    struct GenImage {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        bool fresh = true;
    };

    std::mutex configLock;
    Config config;
    std::string cachePath;
    bool configDirty = true;
    std::atomic_bool enabled{false};
    std::atomic_bool retryRequested{false};
    std::atomic<float> refreshRate{60.0f};
    std::atomic_int status{STATUS_OFF};
    std::atomic<float> presentedRate{0.0f};
    char reason[192] = "";

    // Vulkan, all owned by the present thread.
    bool deviceTried = false;
    bool deviceReady = false;
    VkTable vk{};
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    uint32_t externalQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    uint32_t spirvTarget = 0;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties{};

    std::unique_ptr<lsfg::Engine> engine;
    std::string engineCachePath;
    bool engineFailed = false;

    std::unordered_map<AHardwareBuffer *, SourceImage> sources;
    std::vector<OutputImage> outputRing;
    uint32_t outputNext = 0;
    uint32_t outputWidth = 0, outputHeight = 0, outputAhbFormat = 0;
    std::vector<GenImage> genRing;
    uint32_t genWidth = 0, genHeight = 0;
    VkFormat genFormat = VK_FORMAT_UNDEFINED;

    uint64_t frameCounter = 0;
    uint64_t sourceFrames = 0;
    int64_t rateWindowStart = 0;
    uint32_t rateAccum = 0;

    void setStatus(Status value, const char *why);
    bool ensureDevice();
    bool ensureEngine(const std::string &path);
    SourceImage *importSource(AHardwareBuffer *ahb);
    bool ensureOutputs(const AHardwareBuffer_Desc &desc, uint32_t count);
    bool ensureGenRing(uint32_t width, uint32_t height, VkFormat format, uint32_t count);
    VkFormat chainFormatFor(VkFormat format);
    int findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags);
    bool importAhb(AHardwareBuffer *ahb, VkImageUsageFlags usage, VkImage &image, VkDeviceMemory &memory, VkFormat *format);
    void destroySources();
    void destroyOutputs();
    void destroyGenRing();
    void destroyDevice();
};
