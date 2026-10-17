cmake_minimum_required(VERSION 3.22)
project(escriba_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp and ggml are linked statically into a single library: the backends get registered
# without loading them with dlopen from the app's library folder.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
# The Oryon cores of the Snapdragon 8 Elite Gen 5 have dotprod, fp16 and i8mm: they speed up the
# quantized multiplications (q5_0, q8_0).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
# llamafile (tinyBLAS): much faster matrix multiplications on ARM. llama.cpp enables it by
# default; whisper.cpp doesn't.
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)

# whisper.cpp declares a minimum CMake version (3.5) that CMake 4 no longer accepts by default.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp ${CMAKE_BINARY_DIR}/whisper)

add_library(escriba_native SHARED whisper_jni.cpp)
target_compile_options(escriba_native PRIVATE -O3)
target_link_libraries(escriba_native PRIVATE whisper android log)
# See llm/src/main/cpp/CMakeLists.txt: each library hides its own copy of ggml.
target_link_options(escriba_native PRIVATE -Wl,--exclude-libs,ALL)
