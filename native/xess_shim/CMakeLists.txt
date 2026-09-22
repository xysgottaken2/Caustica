cmake_minimum_required(VERSION 3.20)
project(xessshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

get_filename_component(XESS_SHIM_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(XESS_SHIM_OUTPUT_ROOT "${XESS_SHIM_REPO_ROOT}/build/native/xess_shim"
    CACHE PATH "Directory for built XeSS shim artifacts")
set(XESS_SHIM_OUTPUT_DIR "${XESS_SHIM_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")

if(NOT DEFINED ENV{XESS_SDK})
  message(FATAL_ERROR "XESS_SDK environment variable is not set (intel/xess checkout)")
endif()

set(XESS_SDK_DIR "$ENV{XESS_SDK}")

if(NOT WIN32)
  # The shim loads Intel's prebuilt libxess.dll, which Intel only ships for Windows. A Linux build
  # would need a libxess.so Intel does not currently provide — deliberately left as follow-up work.
  message(FATAL_ERROR "xess_shim currently builds on Windows only (prebuilt libxess.dll)")
endif()

add_library(xessshim SHARED xess_shim.cpp)

target_include_directories(xessshim PRIVATE "${XESS_SDK_DIR}/inc")
# xess_vk.h pulls in <vulkan/vulkan.h>; on CI/dev setups that is the Vulkan SDK.
if(DEFINED ENV{VULKAN_SDK})
  target_include_directories(xessshim PRIVATE "$ENV{VULKAN_SDK}/include")
endif()
# No link-time dependency on the Intel runtime: the shim loads libxess.dll at run time
# (xess.h declares the entry points dllexport when XESS_SHARED_LIB, which fights linking the import
# library). Same dynamic-load pattern as fsr_shim / ngx_shim.
set_property(TARGET xessshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
set_target_properties(xessshim PROPERTIES OUTPUT_NAME "xessshim")

set_target_properties(xessshim PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${XESS_SHIM_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${XESS_SHIM_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY "${XESS_SHIM_OUTPUT_DIR}")

# Ship the Intel runtime next to the shim: the gradle bundling task picks both up from this
# directory (same convention as the FSR shim + amd_fidelityfx_vk.dll).
add_custom_command(TARGET xessshim POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${XESS_SDK_DIR}/bin/libxess.dll"
        "${XESS_SHIM_OUTPUT_DIR}/libxess.dll")
