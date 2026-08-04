# android/Makefile — NDK cross-build of the core selftest (M2 checkpoint).
# Produces android-native binaries that run on waydroid / any device:
#   adb push core_selftest_x86_64 /data/local/tmp/
#   adb shell /data/local/tmp/core_selftest_x86_64
NDK ?= $(HOME)/android-sdk/ndk/29.0.14206865
TC  := $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin
API := 24
CFLAGS := -std=c11 -Wall -Wextra -pedantic -O2
SRC := ../core/tags.c ../core/queue.c ../core/md5.c ../core/cJSON.c ../core/fs.c ../core/core_selftest.c

all: core_selftest_x86_64 core_selftest_arm64

core_selftest_x86_64: $(SRC)
	$(TC)/x86_64-linux-android$(API)-clang $(CFLAGS) -o $@ $(SRC)

core_selftest_arm64: $(SRC)
	$(TC)/aarch64-linux-android$(API)-clang $(CFLAGS) -o $@ $(SRC)

clean:
	rm -f core_selftest_x86_64 core_selftest_arm64
