# Ponte JNI del modulo :engine (Spec §4.3). Agente B. Aggiunto il 22/10/2026 alle 11:20.
#
# Include il motore condiviso ../../core-native (CMakeLists dell'agente A/orchestratore)
# e aggiunge libzz_engine_jni.so, che dipende da libzz_engine.so.
# Le opzioni ZZ_ENGINE_MOCK e ZZ_BUILD_TESTS arrivano da Gradle come variabili di cache.
cmake_minimum_required(VERSION 3.22.1)
project(zz_engine_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# android/engine/src/main/cpp -> radice del repository
get_filename_component(ZZ_CORE_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core-native" ABSOLUTE)
add_subdirectory("${ZZ_CORE_NATIVE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/core-native")

add_library(zz_engine_jni SHARED zz_engine_jni.cpp)
target_link_libraries(zz_engine_jni PRIVATE zz_engine)

if(ANDROID)
  find_library(ZZ_LOG_LIB log)
  target_link_libraries(zz_engine_jni PRIVATE ${ZZ_LOG_LIB})
  # Pagine da 16 KB richieste da Google Play per i nuovi target (Android 15+).
  target_link_options(zz_engine_jni PRIVATE "-Wl,-z,max-page-size=16384")
  target_link_options(zz_engine PRIVATE "-Wl,-z,max-page-size=16384")
endif()
