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

set(ZZ_COMMON_SOURCES src/zz_common.cpp)

if(ZZ_ENGINE_MOCK)
  add_library(zz_engine SHARED ${ZZ_COMMON_SOURCES} mock/zz_engine_mock.cpp)
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
