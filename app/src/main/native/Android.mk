LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := easylsp-namespace
LOCAL_SRC_FILES := mount_namespace.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -O2
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := easylsp-diagnostic-collector
LOCAL_SRC_FILES := diagnostic_collector.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../native-runtime/src/main/cpp
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -O2
include $(BUILD_EXECUTABLE)
