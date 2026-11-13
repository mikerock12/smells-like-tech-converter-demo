# Inferência ONNX da criação musical (separação Spleeter) pela API C do ONNX Runtime.
# Liga no libonnxruntime.so que o sherpa-onnx já embarca: um runtime só no APK, sem conflito
# de versão de símbolos. O .so é extraído do próprio AAR do sherpa na configuração.
cmake_minimum_required(VERSION 3.22.1)
project(musica_onnx C CXX)

set(AAR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/sherpa-onnx-1.13.8.aar)
set(EXTRAIDO ${CMAKE_BINARY_DIR}/sherpa-onnx)
if(NOT EXISTS ${EXTRAIDO}/jni/${ANDROID_ABI}/libonnxruntime.so)
  file(ARCHIVE_EXTRACT INPUT ${AAR} DESTINATION ${EXTRAIDO} PATTERNS jni/${ANDROID_ABI}/libonnxruntime.so)
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${EXTRAIDO}/jni/${ANDROID_ABI}/libonnxruntime.so)

add_library(musica-onnx SHARED musica_onnx.c)
target_include_directories(musica-onnx PRIVATE onnxruntime)
target_link_libraries(musica-onnx onnxruntime log)
# Páginas de 16 KB (exigência da Google Play para o Android 15+).
target_link_options(musica-onnx PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

set(SLT_BUILD_TESTS OFF)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/slt-media-engine" slt-media-engine)

add_library(slt-jni SHARED nucleo_jni.cpp)
target_compile_features(slt-jni PRIVATE cxx_std_20)
target_compile_options(slt-jni PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti -fvisibility=hidden)
target_link_libraries(slt-jni PRIVATE slt_native)
target_link_options(slt-jni PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
