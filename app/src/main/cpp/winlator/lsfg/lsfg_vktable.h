#pragma once
// ============================================================================
// The Vulkan entry points of the app's own frame generation device, loaded from
// the system libvulkan. Bannerlator keeps the same table in its Vulkan
// compositor (VulkanRendererContext.h); lsfg_vkd.cpp and lsfg_probe.cpp read
// it by these names.
// ============================================================================

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#define LSFG_VK_INSTANCE_FUNCTIONS(X)            \
    X(DestroyInstance)                           \
    X(EnumeratePhysicalDevices)                  \
    X(GetPhysicalDeviceProperties)               \
    X(GetPhysicalDeviceFeatures2)                \
    X(GetPhysicalDeviceFormatProperties)         \
    X(GetPhysicalDeviceMemoryProperties)         \
    X(GetPhysicalDeviceQueueFamilyProperties)    \
    X(EnumerateDeviceExtensionProperties)        \
    X(CreateDevice)                              \
    X(GetDeviceProcAddr)

#define LSFG_VK_DEVICE_FUNCTIONS(X)              \
    X(DestroyDevice)                             \
    X(GetDeviceQueue)                            \
    X(DeviceWaitIdle)                            \
    X(QueueSubmit)                               \
    X(QueueWaitIdle)                             \
    X(CreateCommandPool)                         \
    X(DestroyCommandPool)                        \
    X(AllocateCommandBuffers)                    \
    X(ResetCommandBuffer)                        \
    X(BeginCommandBuffer)                        \
    X(EndCommandBuffer)                          \
    X(CreateFence)                               \
    X(DestroyFence)                              \
    X(ResetFences)                               \
    X(WaitForFences)                             \
    X(GetFenceFdKHR)                             \
    X(GetAndroidHardwareBufferPropertiesANDROID) \
    X(AllocateDescriptorSets)                    \
    X(AllocateMemory)                            \
    X(BindBufferMemory)                          \
    X(BindImageMemory)                           \
    X(CmdBindDescriptorSets)                     \
    X(CmdBindPipeline)                           \
    X(CmdCopyImage)                              \
    X(CmdBlitImage)                              \
    X(CmdDispatch)                               \
    X(CmdPipelineBarrier)                        \
    X(CreateBuffer)                              \
    X(CreateComputePipelines)                    \
    X(CreateDescriptorPool)                      \
    X(CreateDescriptorSetLayout)                 \
    X(CreateImage)                               \
    X(CreateImageView)                           \
    X(CreatePipelineLayout)                      \
    X(CreateSampler)                             \
    X(CreateShaderModule)                        \
    X(DestroyBuffer)                             \
    X(DestroyDescriptorPool)                     \
    X(DestroyDescriptorSetLayout)                \
    X(DestroyImage)                              \
    X(DestroyImageView)                          \
    X(DestroyPipeline)                           \
    X(DestroyPipelineLayout)                     \
    X(DestroySampler)                            \
    X(DestroyShaderModule)                       \
    X(FreeMemory)                                \
    X(GetBufferMemoryRequirements)               \
    X(GetImageMemoryRequirements)                \
    X(MapMemory)                                 \
    X(UnmapMemory)                               \
    X(UpdateDescriptorSets)

struct VkTable {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_vkCreateInstance CreateInstance = nullptr;
    PFN_vkEnumerateInstanceVersion EnumerateInstanceVersion = nullptr;
#define LSFG_VK_MEMBER(fn) PFN_vk##fn fn = nullptr;
    LSFG_VK_INSTANCE_FUNCTIONS(LSFG_VK_MEMBER)
    LSFG_VK_DEVICE_FUNCTIONS(LSFG_VK_MEMBER)
#undef LSFG_VK_MEMBER
};
