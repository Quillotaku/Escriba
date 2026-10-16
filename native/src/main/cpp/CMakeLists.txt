cmake_minimum_required(VERSION 3.22)
project(escriba_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp y ggml se enlazan estáticos dentro de una sola biblioteca: así los backends quedan
# registrados sin tener que cargarlos por dlopen desde la carpeta de bibliotecas de la app.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
# Los núcleos Oryon del Snapdragon 8 Elite Gen 5 tienen dotprod, fp16 e i8mm: son los que
# aceleran las multiplicaciones cuantizadas (q5_0, q8_0).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
# llamafile (tinyBLAS): multiplicaciones de matrices mucho más rápidas en ARM. llama.cpp la
# activa por defecto; whisper.cpp no.
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)

# whisper.cpp declara una versión mínima de CMake (3.5) que CMake 4 ya no acepta por defecto.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp ${CMAKE_BINARY_DIR}/whisper)

add_library(escriba_native SHARED whisper_jni.cpp)
target_compile_options(escriba_native PRIVATE -O3)
target_link_libraries(escriba_native PRIVATE whisper android log)
