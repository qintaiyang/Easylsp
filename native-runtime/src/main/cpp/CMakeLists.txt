cmake_minimum_required(VERSION 3.22.1)
project(easylsp_native C)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../shadowhook" "${CMAKE_CURRENT_BINARY_DIR}/shadowhook")
add_library(easylsp_native SHARED native_runtime.c native_plugin.c native_jni.c)
target_compile_options(easylsp_native PRIVATE -std=c11 -Wall -Wextra -Werror -O2)
target_link_libraries(easylsp_native shadowhook dl log)

target_link_options(easylsp_native PRIVATE -Wl,-z,max-page-size=16384)
