# whisper.cpp, vendored

From https://github.com/ggml-org/whisper.cpp, release v1.9.4 (11 Sep 2026), MIT licence (`LICENSE`).

Only what an Android CPU build needs is kept: `include/whisper.h`, `src/whisper.cpp` and `src/whisper-arch.h`,
and of ggml its CMake files, headers, core sources and the CPU backend (`ggml/src/ggml-cpu`). The GPU and
accelerator backends (CUDA, Metal, Vulkan, OpenCL, SYCL and the rest), the examples, tests, bindings and
models are left out. Nothing kept is modified.

To update: replace these files from a newer release the same way and note the version here.
