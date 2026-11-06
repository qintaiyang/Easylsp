cmake_minimum_required(VERSION 3.22.1)
project(easylsp_shadowhook C ASM)
set(SRC_DIR "${CMAKE_CURRENT_BINARY_DIR}/source")
file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/shadowhook/" DESTINATION "${SRC_DIR}")
execute_process(COMMAND patch -p1 -i "${CMAKE_CURRENT_SOURCE_DIR}/../shadowhook-helper-fd.patch"
    WORKING_DIRECTORY "${SRC_DIR}" RESULT_VARIABLE PATCH_RESULT)
if(NOT PATCH_RESULT EQUAL 0)
    message(FATAL_ERROR "ShadowHook helper-FD patch failed")
endif()
file(READ "${SRC_DIR}/shadowhook.map.txt" EXPORTS)
string(REPLACE "        JNI_OnLoad;" "        easylsp_shadowhook_init_with_fd;" EXPORTS "${EXPORTS}")
string(REPLACE "        shadowhook_interceptor_glue_vfpv3d16;" "" EXPORTS "${EXPORTS}")
string(REPLACE "        shadowhook_interceptor_glue_vfpv3d32;" "" EXPORTS "${EXPORTS}")
file(WRITE "${SRC_DIR}/shadowhook.map.txt" "${EXPORTS}")
file(GLOB SOURCES "${SRC_DIR}/*.c" "${SRC_DIR}/arch/arm64/*.c"
    "${SRC_DIR}/arch/arm64/*.S" "${SRC_DIR}/common/*.c" "${SRC_DIR}/third_party/*/*.c")
# Easylsp uses the C API; omit the upstream Java adapter and its JNI_OnLoad class dependency.
list(FILTER SOURCES EXCLUDE REGEX "/sh_jni\\.c$")
add_library(shadowhook SHARED ${SOURCES})
target_include_directories(shadowhook PUBLIC "${SRC_DIR}/include")
target_compile_features(shadowhook PRIVATE c_std_11)
target_compile_options(shadowhook PRIVATE -Os -ffunction-sections -fdata-sections -Wall -Wextra -Werror)
target_include_directories(shadowhook PRIVATE "${SRC_DIR}" "${SRC_DIR}/include"
    "${SRC_DIR}/arch/arm64" "${SRC_DIR}/common" "${SRC_DIR}/third_party/xdl"
    "${SRC_DIR}/third_party/bsd" "${SRC_DIR}/third_party/lss")
target_link_libraries(shadowhook log dl)
target_link_options(shadowhook PRIVATE -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
    -Wl,--gc-sections "-Wl,--version-script=${SRC_DIR}/shadowhook.map.txt")
add_library(shadowhook_nothing SHARED "${SRC_DIR}/nothing/sh_nothing.c")
target_compile_options(shadowhook_nothing PRIVATE -Oz -fno-ident -fno-unwind-tables -fno-asynchronous-unwind-tables)
target_link_options(shadowhook_nothing PRIVATE -Wl,-z,max-page-size=16384 -Wl,--strip-all
    -Wl,--as-needed -Wl,--hash-style=sysv -Wl,--build-id=none -Wl,-nostdlib -nostartfiles -nodefaultlibs)
