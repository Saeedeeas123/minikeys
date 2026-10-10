# `core:llama` — native build (`libllama_jni.so`)

Ships as `src/main/jniLibs/arm64-v8a/libllama_jni.so`: self-contained (NEEDED = libc, libm, libdl,
liblog; libc++_static linked in), 7,583,000 bytes,
sha256 `9d98f7bb34fe135512602d8f3d72942cdd96cc44536af5d8ae6bf5d6b6c1e75f`.
Built from `llama_jni.cpp` in this folder against llama.cpp 0.6.0-dev (ggml `de7fa0a`).

**Why the source is versioned here:** a binary is not a module. Anyone must be able to rebuild the
shipped artifact from this file — otherwise the module cannot be reused, audited or patched.

## Toolchain (proven end-to-end on this device, 2026-10-09)

| Piece | Value |
|---|---|
| NDK | 26.1.10909125 (x86_64-hosted, used only as a sysroot) |
| Compiler | on-device clang 18 (`/usr/bin/clang++-18`, native aarch64) |
| Linker | `/usr/bin/ld.lld-18` |
| Target | `--target=aarch64-linux-android26` |
| llama.cpp | built **static** with `android.cmake`: `libllama.a`, `libggml.a`, `libggml-base.a`, `libggml-cpu.a`; `GGML_NATIVE=OFF`, no OpenMP, no backend-DL |

`build_jni.sh` holds the exact command lines (edit the five path variables at the top).
`android.cmake` is the CMake toolchain file used to produce the static archives.

## The failure that mattered (found 2026-10-10): a prompt without the assistant turn

`format_prompt()` called `llama_chat_apply_template(..., add_ass = false, ...)`. With `false` the
rendered prompt stops at the user's `<end_of_turn>` and never opens the model's own turn, so greedy
decoding answers with `<end_of_turn>` itself — an end-of-generation token — and `chat()` produced
**zero tokens**. On the device this surfaced as
`RuntimeException: generation produced no tokens (decode failed or context exhausted)` on the *first*
call, for every call.

Reproduced and fixed with `chat_probe` (Linux, same GGUF, mirroring `chat()` exactly):

| variant | rendered prompt ends with | first sampled token | result |
|---|---|---|---|
| `add_ass = false` (was shipped) | `...OK<end_of_turn>\n` | `106` = `<end_of_turn>` (EOG) | 0 tokens → the app's error |
| `add_ass = true` (now shipped) | `...OK<end_of_turn>\n<start_of_turn>model\n` | `7676` = `OK` | `OK\n` |
| hand-rolled gemma fallback | same as above | `7676` = `OK` | `OK\n` |

Lesson for every template-based engine: a chat call must end the prompt *inside the assistant turn*.
If generation ever returns empty again, the exception now reports the first sampled token id and
whether it was an EOG, which identifies this class of bug immediately.

## Native semantics — do not "simplify" without re-testing

1. **`chat()` clears the KV cache before every request**
   (`llama_memory_clear(llama_get_memory(ctx), true)`).
   The Kotlin `LlmEngine` contract passes the *whole* message list on every call, so one native
   session must behave as a stateless per-call service. Without the clear, positions accumulate in a
   single context: after enough calls `llama_decode` can no longer find a KV slot (rc = 1) and
   generation silently dies — the *"it works a few times, then suddenly stops"* failure mode.
2. **Handles are opaque ids into a registry of `std::shared_ptr<Session>`**, never raw pointers.
   `free()` unregisters first, then waits for an in-flight `chat()` and only then releases the model
   and context; a second `free()` of the same handle is a no-op. The previous raw-pointer scheme
   deleted the `Session` while its own mutex was still held (undefined behaviour → native crashes).
3. **An empty generation throws** (`generation produced no tokens (decode failed or context
   exhausted)`) instead of returning `""`. A silent empty reply used to be indistinguishable from a
   model that stopped answering.
4. **`to_java()` converts UTF-8 → UTF-16 by hand.** `NewStringUTF` is not byte-safe for multibyte
   model output.
5. The per-session mutex is held for the whole `chat()`, and the sampler chain is created and freed
   per call.

## Verifying a rebuild

The unit tests in this module (`LlamaModelFormatsTest`, `LlamaCppOptionsTest`) cover the JVM-only
logic; the native layer is covered by running the app's *Test GGUF model* / *Run 20x* buttons on a
device (they call `chat()` repeatedly on one loaded session) and by the
`MiniKeys-crash-*.txt` / `MiniKeys-<engine>-*.log` traces the app writes to shared Downloads.
