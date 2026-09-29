cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)
add_subdirectory(adrenotools)
add_subdirectory(vortekrenderer)

# DXVK's DXBC->SPIR-V translator (zlib). LSFG Native translates the frame
# generation shaders from the user's own Lossless.dll with it on device.
add_subdirectory(thirdparty/dxbc)

# Add Winlator shared library
add_library(winlator SHARED
        xr/engine.c
        xr/framebuffer.c
        xr/input.c
        xr/main.c
        xr/math.c
        xr/renderer.c
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/vulkan.c
        winlator/renderer/egl.cpp
        winlator/renderer/renderer_jni.cpp
        winlator/renderer/shader.cpp
        winlator/renderer/window.cpp
        winlator/renderer/cursor.cpp
        winlator/renderer/xform.cpp
        winlator/renderer/view_transformation.cpp
        winlator/renderer/displayx.cpp
        winlator/renderer/effectcomposer/effect_composer.cpp
        winlator/renderer/framegen/frame_generator.cpp
        winlator/lsfg/lsfg_probe.cpp
        winlator/lsfg/lsfg_dll.cpp
        winlator/lsfg/lsfg_dxbc.cpp
        winlator/lsfg/lsfg_vkd.cpp
        winlator/lsfg/lsfg_common.cpp
        winlator/lsfg/lsfg_shaders.cpp
        winlator/lsfg/lsfg_mipmaps.cpp
        winlator/lsfg/lsfg_alpha.cpp
        winlator/lsfg/lsfg_beta.cpp
        winlator/lsfg/lsfg_gamma.cpp
        winlator/lsfg/lsfg_delta.cpp
        winlator/lsfg/lsfg_generate.cpp
        winlator/lsfg/lsfg_chain.cpp
        winlator/lsfg/lsfg_pacer.cpp
        winlator/lsfg/lsfg_engine.cpp
        winlator/lsfg/lsfg_governor.cpp
        winlator/lsfg/lsfg_jni.cpp
)

target_compile_options(winlator PRIVATE -Wall -Wextra)
set_target_properties(winlator PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(winlator PRIVATE winlator/renderer winlator/renderer/effectcomposer winlator/renderer/framegen winlator/lsfg)

target_link_libraries(winlator
        log
        android
        jnigraphics
        openxr_loader
        aaudio
        EGL
        GLESv2
        GLESv3
        adrenotools
        vulkan
        dxbc
)
