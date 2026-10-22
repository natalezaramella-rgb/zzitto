# Test di core-native (Spec §12). Aggiunto il 22/10/2026 alle 11:05.
# Aggiornato il 22/10/2026 alle 11:35 (agente A): test del motore reale, SHA-256 accelerato,
# test con modelli reali attivati da ZZ_TEST_WHISPER_MODEL e ZZ_TEST_LLM_MODEL.

function(zz_test_warnings target)
  target_compile_options(${target} PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /utf-8>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra>)
endfunction()

# Contratto: vale per il mock e per il motore reale.
add_executable(zz_contract_tests test_contract.cpp)
target_link_libraries(zz_contract_tests PRIVATE zz_engine)
target_include_directories(zz_contract_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
if(NOT ZZ_ENGINE_MOCK)
  target_compile_definitions(zz_contract_tests PRIVATE ZZ_TEST_REAL_ENGINE=1)
  target_sources(zz_contract_tests PRIVATE ${CMAKE_SOURCE_DIR}/src/zz_wav.cpp)
endif()
zz_test_warnings(zz_contract_tests)
add_test(NAME contract COMMAND zz_contract_tests)
set_tests_properties(contract PROPERTIES ENVIRONMENT "ZZ_MOCK_FAST=1")

add_executable(zz_common_tests test_common.cpp)
target_link_libraries(zz_common_tests PRIVATE zz_common_obj)
target_compile_definitions(zz_common_tests PRIVATE ZZ_ENGINE_BUILD)
zz_test_warnings(zz_common_tests)
add_test(NAME common COMMAND zz_common_tests)

# SHA-256: percorsi accelerati (ARMv8 Crypto, SHA-NI) confrontati con quello portabile (Spec §7).
add_executable(zz_sha256_tests test_sha256.cpp)
target_link_libraries(zz_sha256_tests PRIVATE zz_common_obj)
target_compile_definitions(zz_sha256_tests PRIVATE ZZ_ENGINE_BUILD)
zz_test_warnings(zz_sha256_tests)
add_test(NAME sha256 COMMAND zz_sha256_tests)

set(ZZ_TEST_TARGETS zz_contract_tests zz_common_tests zz_sha256_tests)

if(NOT ZZ_ENGINE_MOCK)
  # Prompt, pulizia dell'uscita, suddivisione del testo (senza modelli).
  add_executable(zz_prompts_tests test_prompts.cpp)
  target_link_libraries(zz_prompts_tests PRIVATE zz_engine_static)
  target_include_directories(zz_prompts_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
  zz_test_warnings(zz_prompts_tests)
  add_test(NAME prompts COMMAND zz_prompts_tests)

  # Motore reale con backend finti: errori, hash, busy, cancel, timeout, caricamento sequenziale.
  add_executable(zz_engine_tests test_engine.cpp ${CMAKE_SOURCE_DIR}/src/zz_wav.cpp)
  target_link_libraries(zz_engine_tests PRIVATE zz_engine_static)
  target_include_directories(zz_engine_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
  zz_test_warnings(zz_engine_tests)
  add_test(NAME engine COMMAND zz_engine_tests)

  # Modelli reali: saltato (codice 77) se ZZ_TEST_WHISPER_MODEL / ZZ_TEST_LLM_MODEL mancano.
  add_executable(zz_models_tests test_models.cpp ${CMAKE_SOURCE_DIR}/src/zz_wav.cpp)
  target_link_libraries(zz_models_tests PRIVATE zz_engine)
  target_include_directories(zz_models_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
  zz_test_warnings(zz_models_tests)
  add_test(NAME models COMMAND zz_models_tests)
  set_tests_properties(models PROPERTIES SKIP_RETURN_CODE 77)

  # Integrazione con whisper.cpp/llama.cpp veri e modelli minuscoli (Spec §12): il modello
  # phi3 casuale si genera con Python 3 + numpy; senza, il test viene saltato (77).
  add_executable(zz_integration_tests test_integration.cpp)
  target_link_libraries(zz_integration_tests PRIVATE zz_engine_static)
  target_include_directories(zz_integration_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
  zz_test_warnings(zz_integration_tests)
  set(ZZ_TINY_WHISPER ${ZZ_THIRD_PARTY}/whisper.cpp/models/for-tests-ggml-tiny.bin)
  set(ZZ_TINY_LLM ${CMAKE_CURRENT_BINARY_DIR}/tiny-phi3.gguf)
  find_package(Python3 COMPONENTS Interpreter QUIET)
  set(ZZ_HAVE_NUMPY OFF)
  if(Python3_Interpreter_FOUND)
    execute_process(COMMAND ${Python3_EXECUTABLE} -I -c "import numpy"
                    RESULT_VARIABLE _zz_np OUTPUT_QUIET ERROR_QUIET)
    if(_zz_np EQUAL 0)
      set(ZZ_HAVE_NUMPY ON)
    endif()
  endif()
  if(ZZ_HAVE_NUMPY)
    add_test(NAME tiny_llm_model
             COMMAND ${Python3_EXECUTABLE} -I ${CMAKE_CURRENT_SOURCE_DIR}/tools/make_tiny_phi3.py
                     ${ZZ_THIRD_PARTY}/llama.cpp/gguf-py
                     ${ZZ_THIRD_PARTY}/llama.cpp/models/ggml-vocab-phi-3.gguf ${ZZ_TINY_LLM})
    set_tests_properties(tiny_llm_model PROPERTIES FIXTURES_SETUP zz_tiny_models)
  endif()
  add_test(NAME integration COMMAND zz_integration_tests)
  set_tests_properties(integration PROPERTIES SKIP_RETURN_CODE 77 FIXTURES_REQUIRED zz_tiny_models
                       ENVIRONMENT "ZZ_TEST_TINY_WHISPER=${ZZ_TINY_WHISPER};ZZ_TEST_TINY_LLM=${ZZ_TINY_LLM}")

  list(APPEND ZZ_TEST_TARGETS zz_prompts_tests zz_engine_tests zz_models_tests zz_integration_tests)
endif()

if(ZZ_RUN_TESTS_ON_BUILD)
  add_custom_target(zz_run_tests ALL
    COMMAND ${CMAKE_CTEST_COMMAND} --output-on-failure -C $<CONFIG>
    WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
    DEPENDS ${ZZ_TEST_TARGETS}
    COMMENT "Esecuzione test core-native (Spec §12)")
endif()
