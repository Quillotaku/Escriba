cmake_minimum_required(VERSION 3.22)
project(escriba_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Igual que whisper.cpp: llama.cpp y su ggml estáticos dentro de una sola biblioteca.
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
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE) # sin red: no hace falta HTTPS
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/llama.cpp ${CMAKE_BINARY_DIR}/llama)

add_library(escriba_llm SHARED llama_jni.cpp)
target_compile_options(escriba_llm PRIVATE -O3)
target_link_libraries(escriba_llm PRIVATE llama android log)
# whisper.cpp y llama.cpp llevan cada uno su copia de ggml. Ocultando los símbolos de las
# bibliotecas estáticas, cada .so usa la suya aunque las dos estén cargadas en el mismo proceso.
target_link_options(escriba_llm PRIVATE -Wl,--exclude-libs,ALL)
