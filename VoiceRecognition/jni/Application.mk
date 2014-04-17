# Build both ARMv5TE and ARMv7-A machine code.
APP_ABI := armeabi armeabi-v7a

APP_MODULES = audioboo-ogg audioboo-flac audioboo-native
APP_OPTIM = release

AUDIOBOO_NATIVE_FLAGS = \
	-Ijni/config \
	-Ijni/ogg/include \
	-DVERSION=\"1.2\" \
	-Ijni/flac/include \
	-Ijni/flac/src/libFLAC/include

APP_CFLAGS += $(AUDIOBOO_NATIVE_FLAGS)
APP_CXXFLAGS += $(AUDIOBOO_NATIVE_FLAGS)
