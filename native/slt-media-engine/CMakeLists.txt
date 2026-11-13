# SLT Native Media Engine: o núcleo C++20 comum ao Windows, ao Android e ao navegador.
#
#   Windows:  cmake -S native/slt-media-engine -B build/nativo/windows-x64 -A x64
#             cmake --build build/nativo/windows-x64 --config Release
#             ctest --test-dir build/nativo/windows-x64 -C Release --output-on-failure
#   Android:  entra pelo CMake do app (android/app/src/main/cpp), que inclui este diretório.
#   Navegador: node scripts/nativo-wasm.mjs (clang --target=wasm32, sem libc).
cmake_minimum_required(VERSION 3.22)
project(slt_native VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(SLT_BUILD_TESTS "Compila os testes do núcleo" ON)
option(SLT_SANITIZE "AddressSanitizer e UBSan (gcc/clang)" OFF)
set(SLT_VST3_SDK "" CACHE PATH "Caminho do SDK VST3 para o host Windows")
set(SLT_VST3_EXEMPLO "" CACHE FILEPATH "Bundle do plugin VST3 usado no teste de integração")

set(SLT_FONTES
    ${CMAKE_CURRENT_SOURCE_DIR}/src/api.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/consistencia.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/efeitos.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/espectral.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/json.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/mascaras.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/misturador.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/motor.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/restauracao.cpp)

# O dispositivo de áudio só existe onde há backend: WASAPI e ASIO no Windows, Oboe no Android.
if(WIN32)
  list(APPEND SLT_FONTES
      ${CMAKE_CURRENT_SOURCE_DIR}/src/dispositivo.cpp
      ${CMAKE_CURRENT_SOURCE_DIR}/src/platform/windows/asio.cpp
      ${CMAKE_CURRENT_SOURCE_DIR}/src/platform/windows/wasapi.cpp)
  set(SLT_BIBLIOTECAS ole32 avrt advapi32)
elseif(ANDROID)
  find_package(oboe REQUIRED CONFIG)
  list(APPEND SLT_FONTES
      ${CMAKE_CURRENT_SOURCE_DIR}/src/dispositivo.cpp
      ${CMAKE_CURRENT_SOURCE_DIR}/src/platform/android/oboe.cpp)
  set(SLT_BIBLIOTECAS oboe::oboe log)
endif()

if(WIN32 AND SLT_VST3_SDK)
  set(SMTG_ENABLE_VSTGUI_SUPPORT OFF CACHE BOOL "" FORCE)
  set(SMTG_ENABLE_VST3_PLUGIN_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(SMTG_ENABLE_VST3_HOSTING_EXAMPLES OFF CACHE BOOL "" FORCE)
  add_subdirectory("${SLT_VST3_SDK}" vst3sdk EXCLUDE_FROM_ALL)
  set(SLT_VST3_HOSTING "${SLT_VST3_SDK}/public.sdk/source/vst/hosting")
  add_library(slt_vst3 OBJECT src/platform/windows/vst3.cpp
      "${SLT_VST3_HOSTING}/module_win32.cpp"
      "${SLT_VST3_HOSTING}/plugprovider.cpp"
      "${SLT_VST3_SDK}/public.sdk/source/common/memorystream.cpp")
  target_include_directories(slt_vst3 SYSTEM PRIVATE "${SLT_VST3_SDK}")
  set_target_properties(slt_vst3 PROPERTIES CXX_STANDARD 17)
  target_compile_definitions(slt_vst3 PRIVATE SLT_BUILD_DLL)
  if(MSVC)
    # Captura exceções C++ e falhas estruturadas originadas em módulos de terceiros.
    target_compile_options(slt_vst3 PRIVATE /EHa /utf-8)
  endif()
  target_link_libraries(slt_vst3 PUBLIC sdk_hosting shell32 ole32)
  list(APPEND SLT_BIBLIOTECAS slt_vst3)
endif()

function(slt_opcoes alvo)
  target_include_directories(${alvo} PUBLIC ${CMAKE_CURRENT_FUNCTION_LIST_DIR}/include)
  if(MSVC)
    # /fp:precise mantém a conta igual à do JS; sem exceções nem RTTI atravessando a ABI.
    target_compile_options(${alvo} PRIVATE /W4 /WX /fp:precise /GR- /EHs-c- /D_HAS_EXCEPTIONS=0 /utf-8)
  else()
    target_compile_options(${alvo} PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti -ffp-contract=off -fvisibility=hidden)
  endif()
  if(SLT_SANITIZE AND NOT MSVC)
    target_compile_options(${alvo} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(${alvo} PRIVATE -fsanitize=address,undefined)
  endif()
endfunction()

add_library(slt_native SHARED ${SLT_FONTES})
target_compile_definitions(slt_native PRIVATE SLT_BUILD_DLL)
slt_opcoes(slt_native)
target_link_libraries(slt_native PRIVATE ${SLT_BIBLIOTECAS})
if(TARGET slt_vst3)
  target_compile_definitions(slt_native PRIVATE SLT_COM_VST3)
endif()
if(ANDROID)
  # Páginas de 16 KB (exigência da Google Play para o Android 15+).
  target_link_options(slt_native PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()

if(SLT_BUILD_TESTS AND NOT ANDROID)
  enable_testing()
  # Os testes compilam o núcleo junto (estático), para também enxergar as funções internas.
  add_executable(slt_testes ${SLT_FONTES} ${CMAKE_CURRENT_SOURCE_DIR}/tests/testes.cpp ${CMAKE_CURRENT_SOURCE_DIR}/tests/testes_mixer.cpp)
  # Os casos compartilhados da criação musical (o "esperado" vem da referência em JS).
  target_compile_definitions(slt_testes PRIVATE SLT_STATIC SLT_CASOS="${CMAKE_CURRENT_SOURCE_DIR}/../../packages/converter-core/musica-casos")
  slt_opcoes(slt_testes)
  target_link_libraries(slt_testes PRIVATE ${SLT_BIBLIOTECAS})
  if(TARGET slt_vst3)
    target_compile_definitions(slt_testes PRIVATE SLT_COM_VST3)
  endif()
  if(WIN32)
    target_sources(slt_testes PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tests/testes_windows.cpp)
  endif()
  add_test(NAME nucleo COMMAND slt_testes)
  # A DLL de verdade, pela ABI pública, como um cliente a carrega.
  add_executable(slt_abi ${CMAKE_CURRENT_SOURCE_DIR}/tests/abi.cpp)
  target_link_libraries(slt_abi PRIVATE slt_native)
  slt_opcoes(slt_abi)
  add_test(NAME abi COMMAND slt_abi)
  if(WIN32)
    add_custom_command(TARGET slt_abi POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:slt_native> $<TARGET_FILE_DIR:slt_abi>)
  endif()
  if(TARGET slt_vst3 AND SLT_VST3_EXEMPLO)
    add_executable(slt_teste_vst3 tests/vst3.cpp)
    target_link_libraries(slt_teste_vst3 PRIVATE slt_native)
    slt_opcoes(slt_teste_vst3)
    add_test(NAME vst3 COMMAND slt_teste_vst3 "${SLT_VST3_EXEMPLO}")
  endif()
endif()
