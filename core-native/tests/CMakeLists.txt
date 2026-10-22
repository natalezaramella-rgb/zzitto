# Test di core-native (Spec §12). Aggiunto il 22/10/2026 alle 11:05.
add_executable(zz_contract_tests test_contract.cpp)
target_link_libraries(zz_contract_tests PRIVATE zz_engine)
target_include_directories(zz_contract_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
add_test(NAME contract COMMAND zz_contract_tests)
set_tests_properties(contract PROPERTIES ENVIRONMENT "ZZ_MOCK_FAST=1")

add_executable(zz_common_tests test_common.cpp ${CMAKE_SOURCE_DIR}/src/zz_common.cpp)
target_include_directories(zz_common_tests PRIVATE ${CMAKE_SOURCE_DIR}/include ${CMAKE_SOURCE_DIR}/src)
target_compile_definitions(zz_common_tests PRIVATE ZZ_ENGINE_BUILD)
add_test(NAME common COMMAND zz_common_tests)

if(ZZ_RUN_TESTS_ON_BUILD)
  add_custom_target(zz_run_tests ALL
    COMMAND ${CMAKE_CTEST_COMMAND} --output-on-failure -C $<CONFIG>
    WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
    DEPENDS zz_contract_tests zz_common_tests
    COMMENT "Esecuzione test core-native (Spec §12)")
endif()
