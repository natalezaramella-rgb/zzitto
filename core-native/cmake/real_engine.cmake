# Motore reale Zzitto: whisper.cpp + llama.cpp con un solo ggml condiviso.
# Spec §3, §4.3, §7, §11.1, §16.1. Aggiunto il 22/10/2026 alle 11:18.
#
# Incluso da core-native/CMakeLists.txt quando ZZ_ENGINE_MOCK=OFF.
# Produce:
#   zz_engine          libreria condivisa (libzz_engine.so su Android/Linux, zz_engine.dll su Windows)
#   zz_engine_static   stessa implementazione in libreria statica, per i test interni (Spec §12)
#   zz_bench           strumento di benchmark da riga di comando (Spec §11, §16 fase 1)
#
# Scelta su ggml (vedi README.md, "Un solo ggml"): whisper.cpp e llama.cpp contengono
# ciascuno una copia di ggml. Si compila UNA sola copia, quella di llama.cpp, prima dei due
# progetti: entrambi la riusano perché aggiungono la propria solo se il target `ggml` non esiste.
# Le due revisioni fissate nei sottomoduli hanno lo stesso ggml 0.27.0; quello di llama.cpp
# è un sovrainsieme di quello di whisper.cpp (un'operazione in più), quindi l'API è compatibile.

set(ZZ_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
foreach(_zz_dep whisper.cpp llama.cpp)
  if(NOT EXISTS ${ZZ_THIRD_PARTY}/${_zz_dep}/CMakeLists.txt)
    message(FATAL_ERROR
      "Sottomodulo ${_zz_dep} mancante. Eseguire dalla radice del repository:\n"
      "  git submodule update --init --depth 1 core-native/third_party/${_zz_dep}")
  endif()
endforeach()

# ---- Opzioni del motore --------------------------------------------------------------
if(ANDROID)
  set(_zz_bench_default OFF)
else()
  set(_zz_bench_default ON)
endif()
option(ZZ_BUILD_BENCH "Compila lo strumento di benchmark zz_bench (Spec §11)" ${_zz_bench_default})
option(ZZ_GPU_VULKAN "Backend GPU Vulkan di ggml (Spec §2.4, §11.1); richiede Vulkan SDK/glslc" OFF)
option(ZZ_GPU_OPENCL "Backend GPU OpenCL di ggml (GPU Adreno, Spec §11.1); richiede OpenCL ICD" OFF)
option(ZZ_NPU_HEXAGON "Backend NPU Hexagon di ggml (Snapdragon, Spec §11.1); richiede Hexagon SDK" OFF)
option(ZZ_ENGINE_DEBUG_LOGS "Inoltra i log di whisper/llama su stderr (solo sviluppo, mai in rilascio: Spec §10)" OFF)
# ARM64 (Android arm64-v8a, Windows on ARM): NEON è sempre attivo in ARMv8; dotprod e fp16
# sono presenti su Galaxy S24 (Cortex-X4/A720/A520) e Snapdragon X (Oryon). Spec §11.1.
set(ZZ_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "Architettura ARM64 per ggml (-march)")

# ---- Rilevamento architettura ---------------------------------------------------------
set(_zz_arch "${CMAKE_SYSTEM_PROCESSOR}")
if(CMAKE_GENERATOR_PLATFORM)
  set(_zz_arch "${CMAKE_GENERATOR_PLATFORM}")
elseif(ANDROID_ABI)
  set(_zz_arch "${ANDROID_ABI}")
endif()
string(TOLOWER "${_zz_arch}" _zz_arch)
if(_zz_arch MATCHES "^(aarch64|arm64|arm64-v8a|armv8.*)$")
  set(ZZ_ARCH_ARM64 ON)
elseif(_zz_arch MATCHES "^(x86_64|amd64|x64)$")
  set(ZZ_ARCH_X64 ON)
endif()
message(STATUS "Zzitto: architettura '${_zz_arch}' (arm64=${ZZ_ARCH_ARM64}, x64=${ZZ_ARCH_X64})")

# Imposta una variabile di cache con FORCE: whisper.cpp dichiara cmake_minimum_required(3.5),
# quindi option() ignorerebbe le normali variabili (CMP0077 OLD).
macro(zz_force var type value)
  set(${var} ${value} CACHE ${type} "Impostato da core-native/cmake/real_engine.cmake" FORCE)
endmacro()

# ---- ggml (una sola copia, da llama.cpp) ----------------------------------------------
zz_force(BUILD_SHARED_LIBS BOOL OFF)          # tutto statico dentro zz_engine
zz_force(GGML_BUILD_TESTS BOOL OFF)
zz_force(GGML_BUILD_EXAMPLES BOOL OFF)
zz_force(GGML_CCACHE BOOL OFF)
zz_force(GGML_NATIVE BOOL OFF)                # binari distribuibili, mai -march=native
zz_force(GGML_OPENMP BOOL OFF)                # threadpool di ggml: nessuna DLL/so OpenMP da distribuire
zz_force(GGML_LLAMAFILE BOOL ON)              # sgemm ottimizzato (x86 e ARM)
zz_force(GGML_CPU_REPACK BOOL ON)             # riordino pesi Q4_0/Q4_K per NEON/i8mm
zz_force(GGML_BACKEND_DL BOOL OFF)
zz_force(GGML_VULKAN BOOL ${ZZ_GPU_VULKAN})
zz_force(GGML_OPENCL BOOL ${ZZ_GPU_OPENCL})
zz_force(GGML_HEXAGON BOOL ${ZZ_NPU_HEXAGON})
zz_force(GGML_METAL BOOL OFF)
zz_force(GGML_BLAS BOOL OFF)

if(ZZ_ARCH_ARM64)
  if(MSVC AND NOT CMAKE_C_COMPILER_ID STREQUAL "Clang")
    # ggml con cl.exe su ARM64 accetta solo GGML_NATIVE (sonda la macchina di build).
    message(FATAL_ERROR
      "Windows ARM64: usare clang-cl (Visual Studio: -T ClangCL), non cl.exe. "
      "Con cl.exe ggml non può abilitare NEON dotprod/fp16 in modo portabile (Spec §11.1).")
  endif()
  zz_force(GGML_CPU_ARM_ARCH STRING "${ZZ_ARM_ARCH}")   # Spec §11.1: NEON + dotprod + fp16
elseif(ZZ_ARCH_X64)
  # Riferimento Windows: portatile con 16 GB senza GPU dedicata (Spec §11.1).
  # AVX2/FMA/F16C presenti su tutte le CPU x64 dal 2013 (Haswell, Zen).
  zz_force(GGML_SSE42 BOOL ON)
  zz_force(GGML_AVX BOOL ON)
  zz_force(GGML_AVX2 BOOL ON)
  zz_force(GGML_FMA BOOL ON)
  zz_force(GGML_F16C BOOL ON)
  zz_force(GGML_BMI2 BOOL ON)
  zz_force(GGML_AVX512 BOOL OFF)
endif()

# Simboli di ggml/llama/whisper nascosti: zz_engine esporta solo l'API del contratto.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

add_subdirectory(${ZZ_THIRD_PARTY}/llama.cpp/ggml ${CMAKE_CURRENT_BINARY_DIR}/third_party/ggml EXCLUDE_FROM_ALL)

# ---- llama.cpp ------------------------------------------------------------------------
zz_force(LLAMA_USE_SYSTEM_GGML BOOL OFF)
zz_force(LLAMA_BUILD_COMMON BOOL OFF)
zz_force(LLAMA_BUILD_TESTS BOOL OFF)
zz_force(LLAMA_BUILD_TOOLS BOOL OFF)
zz_force(LLAMA_BUILD_EXAMPLES BOOL OFF)
zz_force(LLAMA_BUILD_SERVER BOOL OFF)
zz_force(LLAMA_BUILD_APP BOOL OFF)
zz_force(LLAMA_BUILD_MTMD BOOL OFF)
zz_force(LLAMA_OPENSSL BOOL OFF)
zz_force(LLAMA_CURL BOOL OFF)
zz_force(LLAMA_ALL_WARNINGS BOOL OFF)
add_subdirectory(${ZZ_THIRD_PARTY}/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/third_party/llama EXCLUDE_FROM_ALL)

# ---- whisper.cpp ----------------------------------------------------------------------
zz_force(WHISPER_USE_SYSTEM_GGML BOOL OFF)
zz_force(WHISPER_BUILD_TESTS BOOL OFF)
zz_force(WHISPER_BUILD_EXAMPLES BOOL OFF)
zz_force(WHISPER_BUILD_SERVER BOOL OFF)
zz_force(WHISPER_CURL BOOL OFF)
zz_force(WHISPER_SDL2 BOOL OFF)
zz_force(WHISPER_ALL_WARNINGS BOOL OFF)
zz_force(WHISPER_COREML BOOL OFF)
zz_force(WHISPER_OPENVINO BOOL OFF)
add_subdirectory(${ZZ_THIRD_PARTY}/whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/third_party/whisper EXCLUDE_FROM_ALL)

# I target di terze parti vanno compilati senza i nostri -Wall/-Wextra.
foreach(_zz_t ggml ggml-base ggml-cpu llama whisper)
  if(TARGET ${_zz_t})
    set_target_properties(${_zz_t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
  endif()
endforeach()

# ---- Versioni dei sottomoduli (per zz_version_string) ---------------------------------
find_package(Git QUIET)
function(zz_git_rev dir out)
  set(_rev "sconosciuta")
  if(GIT_FOUND)
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${dir} rev-parse --short=12 HEAD
                    OUTPUT_VARIABLE _o RESULT_VARIABLE _r OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if(_r EQUAL 0 AND _o)
      set(_rev "${_o}")
    endif()
  endif()
  set(${out} "${_rev}" PARENT_SCOPE)
endfunction()
zz_git_rev(${ZZ_THIRD_PARTY}/whisper.cpp ZZ_WHISPER_REV)
zz_git_rev(${ZZ_THIRD_PARTY}/llama.cpp ZZ_LLAMA_REV)

# ---- Sorgenti del motore --------------------------------------------------------------
set(ZZ_ENGINE_SOURCES
  ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_engine.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_engine_core.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_prompts.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_backend_whisper.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_backend_llama.cpp)

function(zz_engine_configure target)
  target_include_directories(${target} PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
                                       PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_compile_definitions(${target} PRIVATE
    ZZ_ENGINE_BUILD
    ZZ_WHISPER_REV="${ZZ_WHISPER_REV}"
    ZZ_LLAMA_REV="${ZZ_LLAMA_REV}"
    ZZ_ENGINE_VERSION="${PROJECT_VERSION}"
    $<$<BOOL:${ZZ_ENGINE_DEBUG_LOGS}>:ZZ_ENGINE_DEBUG_LOGS=1>)
  target_link_libraries(${target} PRIVATE whisper llama ggml)
  target_compile_options(${target} PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /utf-8 /permissive->
    $<$<AND:$<NOT:$<CXX_COMPILER_ID:MSVC>>,$<COMPILE_LANGUAGE:CXX>>:-Wall -Wextra>)
  set_target_properties(${target} PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
endfunction()

add_library(zz_engine SHARED ${ZZ_ENGINE_SOURCES})
zz_engine_configure(zz_engine)
target_link_libraries(zz_engine PRIVATE zz_common_obj)
if(WIN32)
  # Nome fisso zz_engine.dll anche con MinGW/clang (Spec §4.3: P/Invoke dell'agente C).
  set_target_properties(zz_engine PROPERTIES PREFIX "" IMPORT_PREFIX "")
endif()
if(CMAKE_SYSTEM_NAME MATCHES "Linux|Android")
  # Nasconde i simboli delle librerie statiche e rifiuta simboli non risolti.
  target_link_options(zz_engine PRIVATE "LINKER:--exclude-libs,ALL" "LINKER:--no-undefined"
                      "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/cmake/zz_engine.map")
  set_target_properties(zz_engine PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/cmake/zz_engine.map)
endif()
if(ANDROID)
  # Pagine da 16 KB richieste da Google Play per le librerie native (Android 15+).
  target_link_options(zz_engine PRIVATE "LINKER:-z,max-page-size=16384")
  target_link_libraries(zz_engine PRIVATE log)
endif()

# Libreria statica con la stessa implementazione: i test interni (Spec §12) iniettano
# motori finti di trascrizione/correzione tramite zz_engine_core.h.
if(ZZ_BUILD_TESTS AND NOT ANDROID)
  add_library(zz_engine_static STATIC ${ZZ_ENGINE_SOURCES})
  zz_engine_configure(zz_engine_static)
  target_link_libraries(zz_engine_static PUBLIC zz_common_obj whisper llama ggml)
  # Su Windows chi usa la libreria statica non deve vedere __declspec(dllimport).
  target_compile_definitions(zz_engine_static PUBLIC ZZ_ENGINE_BUILD)
endif()

if(ZZ_BUILD_BENCH)
  add_executable(zz_bench ${CMAKE_CURRENT_SOURCE_DIR}/tools/zz_bench.cpp ${CMAKE_CURRENT_SOURCE_DIR}/src/zz_wav.cpp)
  target_include_directories(zz_bench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_link_libraries(zz_bench PRIVATE zz_engine)
  target_compile_options(zz_bench PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /utf-8>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra>)
  if(WIN32)
    target_link_libraries(zz_bench PRIVATE psapi)
  endif()
endif()
