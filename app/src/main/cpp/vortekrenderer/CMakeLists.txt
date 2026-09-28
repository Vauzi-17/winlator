# Vortek renderer (server side of the Vortek Vulkan driver for glibc containers), from
# brunodev85/winlator-app 3981d86 (LGPL-2.1, see LICENSE). Built as its own library with hidden
# symbols so its helpers never clash with libwinlator's.
add_library(vortekrenderer SHARED
            src/main.c
            src/vk_object.c
            src/request_handler.c
            src/vulkan_helper.c
            src/vk_context.c
            src/resource_memory.c
            src/xwindow_swapchain.c
            src/texture_decoder.c
            src/shader_inspector.c
            src/async_pipeline_creator.c
            src/timeline_semaphore.c
            support/src/arrays.c
            support/src/ring_buffer.c
            support/src/vortek_support.c)

# Upstream builds with NDK r24 (clang 14); newer clang turned these warnings into errors.
target_compile_options(vortekrenderer PRIVATE -O2 -fvisibility=hidden -Wno-unused-function
                       -Wno-error=implicit-function-declaration -Wno-error=int-conversion
                       -Wno-error=incompatible-function-pointer-types -Wno-error=incompatible-pointer-types
                       -Wno-incompatible-pointer-types-discards-qualifiers -DVK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(vortekrenderer PRIVATE include support/include)
target_link_libraries(vortekrenderer adrenotools log android)
