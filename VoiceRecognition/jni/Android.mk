LOCAL_PATH := $(call my-dir)

# First build libogg statically
#
include $(CLEAR_VARS)

LOCAL_MODULE    := audioboo-ogg
LOCAL_SRC_FILES := \
	ogg/src/bitwise.c \
	ogg/src/framing.c

include $(BUILD_STATIC_LIBRARY)

# Then build flac statically
#
include $(CLEAR_VARS)

LOCAL_MODULE    := audioboo-flac
LOCAL_SRC_FILES := \
	flac/src/libFLAC/bitmath.c \
	flac/src/libFLAC/bitreader.c \
	flac/src/libFLAC/cpu.c \
	flac/src/libFLAC/crc.c \
	flac/src/libFLAC/fixed.c \
	flac/src/libFLAC/float.c \
	flac/src/libFLAC/format.c \
	flac/src/libFLAC/lpc.c \
	flac/src/libFLAC/md5.c \
	flac/src/libFLAC/memory.c \
	flac/src/libFLAC/metadata_iterators.c \
	flac/src/libFLAC/metadata_object.c \
	flac/src/libFLAC/ogg_decoder_aspect.c \
	flac/src/libFLAC/ogg_encoder_aspect.c \
	flac/src/libFLAC/ogg_helper.c \
	flac/src/libFLAC/ogg_mapping.c \
	flac/src/libFLAC/stream_decoder.c \
	flac/src/libFLAC/stream_encoder.c \
	flac/src/libFLAC/stream_encoder_framing.c \
	flac/src/libFLAC/window.c \
	flac/src/libFLAC/bitwriter.c

include $(BUILD_STATIC_LIBRARY)

# Lastly build the JNI wrapper and link both other libs against it
#
include $(CLEAR_VARS)

LOCAL_MODULE    := audioboo-native
LOCAL_SRC_FILES := \
	jni/FLACStreamEncoder.cpp \
	jni/FLACStreamDecoder.cpp \
	jni/util.cpp
LOCAL_LDLIBS := -llog

LOCAL_STATIC_LIBRARIES := audioboo-ogg audioboo-flac

include $(BUILD_SHARED_LIBRARY)
