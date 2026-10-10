NDK=/root/Android/ndk/26.1.10909125
SYS=$NDK/toolchains/llvm/prebuilt/linux-x86/sysroot
LIBS=$SYS/usr/lib/aarch64-linux-android
RT=$NDK/toolchains/llvm/prebuilt/linux-x86/lib/clang/17/lib/linux
SRC=/root/projects/llama.cpp
B=/root/projects/llama-android/build
OUT=/root/projects/llama-android/out
mkdir -p $OUT
clang++ --target=aarch64-linux-android26 --sysroot=$SYS -O2 -fvisibility=hidden -I$SRC/include -I$SRC/ggml/include -c /root/projects/llama-android/jni/llama_jni.cpp -o $OUT/llama_jni.o || exit 1
clang++ --target=aarch64-linux-android26 --sysroot=$SYS -shared -o $OUT/libllama_jni.so $OUT/llama_jni.o -L$B/src -L$B/ggml/src -Wl,--start-group -l:libllama.a -l:libggml.a -l:libggml-base.a -l:libggml-cpu.a -Wl,--end-group -L$LIBS/26 -L$LIBS/24 -L$RT -llog -l:libc++_static.a -l:libc++abi.a -l:libunwind.a -lclang_rt.builtins-aarch64-android -lc -lm -static-libstdc++ -fuse-ld=/usr/bin/ld.lld-18 -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack || exit 1
echo BUILD_OK
