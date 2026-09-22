cmake_minimum_required(VERSION 3.22)
project(nrdshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

get_filename_component(NRD_SHIM_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(NRD_SHIM_OUTPUT_ROOT "${NRD_SHIM_REPO_ROOT}/build/native/nrd_shim"
    CACHE PATH "Directory for built NRD shim artifacts")
set(NRD_SHIM_OUTPUT_DIR "${NRD_SHIM_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")

if(NOT DEFINED ENV{NRD_SDK})
  message(FATAL_ERROR "NRD_SDK environment variable is not set (NVIDIA-RTX/NRD checkout)")
endif()
set(NRD_SDK_DIR "$ENV{NRD_SDK}")

if(NOT DEFINED ENV{VULKAN_SDK})
  message(FATAL_ERROR "VULKAN_SDK environment variable is not set")
endif()

# --- NRD (static, SPIR-V only) + its NRI dependency -------------------------------------------
# NRD v4.17.3 fetches NRI v179 via FetchContent when NRD_NRI=ON. Fetch NRI up front instead so the
# shim can include NRI headers directly: NRD sees the NRI target already exists and reuses it.
#
# Both are built STATIC so the shim is a single self-contained DLL: the shared-NRI build produces a
# separate NRI.dll that the bundle step would have to ship (and silently lost — LoadLibrary failed
# at runtime and the whole NRD path died without a visible error).
include(FetchContent)
set(NRI_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_VK_SUPPORT ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D11_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D12_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NVAPI OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_AMDAGS OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NVTX_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NONE_SUPPORT OFF CACHE BOOL "" FORCE)
FetchContent_Declare(nri
    DOWNLOAD_EXTRACT_TIMESTAMP 1
    DOWNLOAD_NO_PROGRESS 1
    URL https://github.com/NVIDIA-RTX/NRI/archive/refs/tags/v179.zip)
FetchContent_MakeAvailable(nri)

# Build-time config that must match the renderer's front-end packing (see nrd_shim.cpp + the
# world_core.slang NRD helpers): RGBA16/SNORM normal encoding unpacks as xyz + w and LINEAR
# roughness unpacks as-is, which is exactly the guide format the tracer already writes.
set(NRD_NRI ON CACHE BOOL "" FORCE)
set(NRD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRD_NORMAL_ENCODING "4" CACHE STRING "" FORCE)
set(NRD_ROUGHNESS_ENCODING "1" CACHE STRING "" FORCE)
set(NRD_EMBEDS_SPIRV_SHADERS ON CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXIL_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXBC_SHADERS OFF CACHE BOOL "" FORCE)
add_subdirectory("${NRD_SDK_DIR}" nrd_build)

# --- The shim -------------------------------------------------------------------------------------
add_library(nrdshim SHARED nrd_shim.cpp)

target_include_directories(nrdshim PRIVATE
    "${NRD_SDK_DIR}/Include"
    "${NRD_SDK_DIR}/Integration"
    "${nri_SOURCE_DIR}/Include"
    "$ENV{VULKAN_SDK}/include")

target_link_libraries(nrdshim PRIVATE NRD NRI)

if(WIN32)
  set_property(TARGET nrdshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
  # Import library only: NRI resolves the entry points at runtime, but the static NRI objects may
  # still carry loader imports; linking the import lib costs nothing when unused.
  if(EXISTS "$ENV{VULKAN_SDK}/Lib/vulkan-1.lib")
    target_link_libraries(nrdshim PRIVATE "$ENV{VULKAN_SDK}/Lib/vulkan-1.lib")
  endif()
endif()

set_target_properties(nrdshim PROPERTIES
    OUTPUT_NAME "nrdshim"
    RUNTIME_OUTPUT_DIRECTORY "${NRD_SHIM_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${NRD_SHIM_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY "${NRD_SHIM_OUTPUT_DIR}")
