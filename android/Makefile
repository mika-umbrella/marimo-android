# android/Makefile — build the C core + JNI shim into libmarimo_core.so
# for both ABIs, dropped straight into the app's jniLibs so gradle just
# packages them (no AGP/NDK plugin integration to fight with).
NDK ?= $(HOME)/android-sdk/ndk/29.0.14206865
TC  := $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin
API := 24
CFLAGS := -std=c11 -Wall -Wextra -pedantic -O2 -fPIC
CORE  := ../core/tags.c ../core/queue.c ../core/md5.c ../core/cJSON.c ../core/fs.c
SHIM  := ../app/src/main/jni/marimo_core.c
INC   := -I../core -I../app/src/main/jni
OUT   := ../app/src/main/jniLibs

all: $(OUT)/x86_64/libmarimo_core.so $(OUT)/arm64-v8a/libmarimo_core.so

$(OUT)/x86_64/libmarimo_core.so: $(CORE) $(SHIM)
	@mkdir -p $(dir $@)
	$(TC)/x86_64-linux-android$(API)-clang $(CFLAGS) $(INC) -shared -o $@ $(CORE) $(SHIM)

$(OUT)/arm64-v8a/libmarimo_core.so: $(CORE) $(SHIM)
	@mkdir -p $(dir $@)
	$(TC)/aarch64-linux-android$(API)-clang $(CFLAGS) $(INC) -shared -o $@ $(CORE) $(SHIM)

clean:
	rm -rf $(OUT)
