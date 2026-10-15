# libmain.so: the LAS Recomp runtime for Android (what the Makefile builds into the desktop executable), with the
# Vulkan backend and the ImGui UI. The game code is not in it: lasgame.so, generated with gen.py --dyn-base and built
# from the player's own game files, is loaded at run time (docs/ANDROID-PORT.md).
cmake_minimum_required(VERSION 3.22)
project(lasrecomp C CXX)

set(R ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)   # the repository
get_filename_component(R ${R} ABSOLUTE)
set(DEPS ${R}/android/deps/out)                        # android/deps/build_deps.sh
if(NOT EXISTS ${DEPS}/lib/libSDL3.so)
    message(FATAL_ERROR "run android/deps/build_deps.sh first")
endif()
if(NOT LAS_GEN_DIR)
    message(FATAL_ERROR "LAS_GEN_DIR (the Gradle build's generated files: smaa_hlsl.inc) is not set")
endif()

# GAMELIB_ID (runtime/gamelib.h): what the game code is built from, as in the Makefile (the generator, the headers
# the generated code is compiled against, and its flags), so lasgame.so and this library agree
set(GAMELIB_FLAGS "-O1 -fno-strict-aliasing -Wno-return-type -Wno-unused-label -fvisibility=hidden -Iruntime -fPIC --dyn-base")
set(GAMELIB_SRCS tools/gen.py tools/lifter.py tools/analyze.py tools/nso.py tools/passes.py tools/patches.py
    tools/simd_c.py tools/rando/patch_variants.json tools/gamelib_entry.c runtime/hle/hle_symbols.txt
    runtime/cpu.h runtime/simd.h runtime/simd_fp.h runtime/gamelib.h)
set(id_text "")
foreach(f ${GAMELIB_SRCS})
    file(READ ${R}/${f} t)
    string(APPEND id_text "${t}")
endforeach()
string(SHA256 id "${id_text}${GAMELIB_FLAGS}")
string(SUBSTRING ${id} 0 16 GAMELIB_ID)
message(STATUS "GAMELIB_ID ${GAMELIB_ID}")

add_subdirectory(${R}/third_party/libadrenotools ${CMAKE_BINARY_DIR}/adrenotools)

add_library(SDL3 SHARED IMPORTED)
set_target_properties(SDL3 PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/libSDL3.so)
add_library(shaderc_shared SHARED IMPORTED)
set_target_properties(shaderc_shared PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/libshaderc_shared.so)

file(GLOB RT_SRC ${R}/runtime/*.c ${R}/runtime/hle/*.c ${R}/runtime/services/*.c ${R}/runtime/nvn/*.c
     ${R}/runtime/nvn/maxwell/*.c)
list(REMOVE_ITEM RT_SRC ${R}/runtime/hle/movie.c)  # (FFmpeg: Fase 2)
file(GLOB VK_SRC ${R}/runtime/nvn/vulkan/*.c ${R}/runtime/nvn/vulkan/*.cpp)
file(GLOB UI_SRC ${R}/runtime/ui/*.mm)
set(IMGUI ${R}/third_party/imgui)
set(IMGUI_SRC ${IMGUI}/imgui.cpp ${IMGUI}/imgui_draw.cpp ${IMGUI}/imgui_tables.cpp ${IMGUI}/imgui_widgets.cpp
    ${IMGUI}/backends/imgui_impl_sdl3.cpp ${IMGUI}/backends/imgui_impl_vulkan.cpp)

add_library(main SHARED ${RT_SRC} ${R}/runtime/platform/android.c ${VK_SRC} ${UI_SRC} ${IMGUI_SRC}
            ${DEPS}/volk/volk.c ${R}/android/common/guest_window.c)
# the UI sources are plain C++ apart from __APPLE__ blocks (as on Linux / Windows)
set_source_files_properties(${UI_SRC} PROPERTIES LANGUAGE CXX COMPILE_OPTIONS "-xc++")
set_source_files_properties(${R}/runtime/gamelib.c PROPERTIES COMPILE_DEFINITIONS "GAMELIB_ID=\"${GAMELIB_ID}\"")
set_source_files_properties(${R}/runtime/gamebuild.c PROPERTIES COMPILE_DEFINITIONS "GAMELIB_ID=\"${GAMELIB_ID}\"")

target_include_directories(main PRIVATE ${R}/runtime ${R}/runtime/nvn ${IMGUI} ${IMGUI}/backends ${DEPS}/include
                           ${DEPS}/volk ${LAS_GEN_DIR} ${R}/third_party/smaa/Textures ${R}/android/common)
target_compile_definitions(main PRIVATE
    VK_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR IMGUI_IMPL_VULKAN_USE_VOLK   # Vulkan from the loaded driver (volk)
    VMA_STATIC_VULKAN_FUNCTIONS=0 VMA_DYNAMIC_VULKAN_FUNCTIONS=1
    LARECOMP_NO_FFMPEG)
target_compile_options(main PRIVATE -fno-strict-aliasing -Wall -Wno-unused-function -Wno-format  # (as on Linux: %llx for uint64_t)
   
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>)
target_link_libraries(main PRIVATE SDL3 shaderc_shared adrenotools android log dl m)
# (runtime/hle.h, ipc.h, main.c: tables in named sections found through __start_ / __stop_ symbols)
target_link_options(main PRIVATE -Wl,-z,nostart-stop-gc)
