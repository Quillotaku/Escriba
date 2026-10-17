cmake_minimum_required(VERSION 3.22)
project(escriba_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same as whisper.cpp: llama.cpp and its ggml statically inside a single library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE) # no network: HTTPS isn't needed
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/llama.cpp ${CMAKE_BINARY_DIR}/llama)

add_library(escriba_llm SHARED llama_jni.cpp)
target_compile_options(escriba_llm PRIVATE -O3)
target_link_libraries(escriba_llm PRIVATE llama android log)
# whisper.cpp and llama.cpp each carry their own copy of ggml. By hiding the symbols of the static
# libraries, each .so uses its own even when both are loaded in the same process.
target_link_options(escriba_llm PRIVATE -Wl,--exclude-libs,ALL)
