# core-native — motore Zzitto condiviso Android/Windows (Spec §4.3)
# Aggiunto il 22/10/2026 alle 11:05.
#
# Opzioni:
#   ZZ_ENGINE_MOCK=ON   compila il motore finto (Spec §16.1), senza whisper.cpp/llama.cpp
#   ZZ_BUILD_TESTS=ON   compila i test; con ZZ_RUN_TESTS_ON_BUILD=ON li esegue a ogni build (Spec §12)
cmake_minimum_required(VERSION 3.22)
project(zz_engine VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(ZZ_ENGINE_MOCK "Compila il motore finto" OFF)
option(ZZ_BUILD_TESTS "Compila i test" ON)
option(ZZ_RUN_TESTS_ON_BUILD "Esegue i test dopo ogni build" ON)

# Windows: runtime C/C++ statico (/MT) per zz_engine.dll e per tutte le dipendenze, così la DLL
# non dipende dal runtime Visual C++ (richiesta dell'agente E, Spec §7.1). CMP0091 NEW anche
# nei sottoprogetti che dichiarano una versione minima di CMake più vecchia (whisper.cpp).
# Aggiunto il 22/10/2026 alle 11:52 (agente A).
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# Logica condivisa tra mock e motore reale (comandi, scorciatoie, VAD, SHA-256).
# Libreria OBJECT: i suoi oggetti entrano interi in zz_engine (le funzioni ZZ_API
# restano esportate) e nei test. Aggiornato il 22/10/2026 alle 11:20 (agente A).
add_library(zz_common_obj OBJECT src/zz_common.cpp src/zz_sha256.cpp)
target_include_directories(zz_common_obj PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(zz_common_obj PRIVATE ZZ_ENGINE_BUILD)
target_compile_options(zz_common_obj PRIVATE
  $<$<CXX_COMPILER_ID:MSVC>:/W4 /utf-8>
  $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra>)
set_target_properties(zz_common_obj PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)

if(ZZ_ENGINE_MOCK)
  add_library(zz_engine SHARED mock/zz_engine_mock.cpp)
  target_link_libraries(zz_engine PRIVATE zz_common_obj)
  if(WIN32)
    set_target_properties(zz_engine PROPERTIES PREFIX "" IMPORT_PREFIX "")
  endif()
else()
  # Motore reale: implementato dall'agente A (Spec §16.1) in src/ con whisper.cpp e llama.cpp.
  include(cmake/real_engine.cmake OPTIONAL RESULT_VARIABLE ZZ_REAL_ENGINE)
  if(NOT ZZ_REAL_ENGINE)
    message(FATAL_ERROR "Motore reale non ancora disponibile: usare -DZZ_ENGINE_MOCK=ON")
  endif()
endif()

target_include_directories(zz_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_definitions(zz_engine PRIVATE ZZ_ENGINE_BUILD)
set_target_properties(zz_engine PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)

if(ZZ_BUILD_TESTS AND NOT ANDROID)
  enable_testing()
  add_subdirectory(tests)
endif()
