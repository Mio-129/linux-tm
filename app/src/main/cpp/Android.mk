LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := mio-elfpatch
LOCAL_MODULE_FILENAME := libmio-elfpatch.so
LOCAL_SRC_FILES := mio-elfpatch.c
include $(BUILD_EXECUTABLE)
