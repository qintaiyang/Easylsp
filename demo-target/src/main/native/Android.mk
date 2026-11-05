LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := demo_early_hook
LOCAL_SRC_FILES := early_hook.c
LOCAL_CFLAGS := -g -O0 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := demo_fault
LOCAL_SRC_FILES := startup_fault.c
LOCAL_CFLAGS := -g -O0
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := demo_constructor_fault
LOCAL_SRC_FILES := startup_fault.c
LOCAL_CFLAGS := -g -O0 -DFAULT_CONSTRUCTOR
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := demo_jni_fault
LOCAL_SRC_FILES := startup_fault.c
LOCAL_CFLAGS := -g -O0 -DFAULT_JNI
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := demo_plugin_driver
LOCAL_SRC_FILES := plugin_driver.c
LOCAL_CFLAGS := -g -O0 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
