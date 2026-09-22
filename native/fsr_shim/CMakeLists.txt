cmake_minimum_required(VERSION 3.20)
project(fsrshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

get_filename_component(FSR_SHIM_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(FSR_SHIM_OUTPUT_ROOT "${FSR_SHIM_REPO_ROOT}/build/native/fsr_shim"
    CACHE PATH "Directory for built FSR shim artifacts")
set(FSR_SHIM_OUTPUT_DIR "${FSR_SHIM_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")

if(NOT DEFINED ENV{FFX_SDK})
  message(FATAL_ERROR "FFX_SDK environment variable is not set (AMD FidelityFX SDK checkout)")
endif()

set(FFX_SDK_DIR "$ENV{FFX_SDK}")

if(NOT WIN32)
  # The shim links the SDK's signed prebuilt amd_fidelityfx_vk.dll, which the SDK only ships for
  # Windows. A Linux build would compile the ffx-api VK backend from source (ffx-api/CMakeLists.txt
  # supports FFX_API_BACKEND=VK_X64) — deliberately left as follow-up work.
  message(FATAL_ERROR "fsr_shim currently builds on Windows only (prebuilt FFX runtime)")
endif()

add_library(fsrshim SHARED fsr_shim.cpp)

target_include_directories(fsrshim PRIVATE "${FFX_SDK_DIR}/ffx-api/include")
# ffx_api_vk.h pulls in <vulkan/vulkan.h>; on CI/dev setups that is the Vulkan SDK.
if(DEFINED ENV{VULKAN_SDK})
  target_include_directories(fsrshim PRIVATE "$ENV{VULKAN_SDK}/include")
endif()
# No link-time dependency on the AMD runtime: the shim loads amd_fidelityfx_vk.dll at run time
# (ffx_api.h declares its entry points dllexport, which fights linking the import library).
set_property(TARGET fsrshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
set_target_properties(fsrshim PROPERTIES OUTPUT_NAME "fsrshim")

set_target_properties(fsrshim PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${FSR_SHIM_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${FSR_SHIM_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY "${FSR_SHIM_OUTPUT_DIR}")

# Ship the AMD runtime next to the shim: fsr_shim.dll imports it, and the gradle bundling task
# picks both up from this directory (same convention as the NGX shim + feature DLLs).
add_custom_command(TARGET fsrshim POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${FFX_SDK_DIR}/PrebuiltSignedDLL/amd_fidelityfx_vk.dll"
        "${FSR_SHIM_OUTPUT_DIR}/amd_fidelityfx_vk.dll")
