# droidHLE agent notes

- Read `CLAUDE.md` for detailed architecture, build setup, design constraints, and active platform gotchas; consult `docs/` for the relevant subsystem spec/plan. Treat dated handoffs there as historical unless their `NEXT` work is still open.
- This project translates ARM32 Android native code with Dynarmic inside a 64-bit Android app process; guest Java runs on host ART. Do not drift toward 32-bit ART/system-wide NativeBridge or special-case one game's imports when a generic bridge belongs in the shared layer.
- `core/` is portable C++20; Android NDK/JNI implementations live in `core/android/`. Guest runtime/stubs are in `guest/`; launcher and device harnesses are under `android/`; `tools/` owns generators and build scripts.
- Generated files are checked in. Edit their generator/input, not generated output: run the matching `tools/gen_*.py --check` (CTest includes JNI, GLES, EGL and NDK checks). Host-call indices are ABI: append new stub names; never reorder or reuse existing indices.
- Host build and full local regression:
  ```sh
  cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
  ninja -C build/host
  tools/build_guest.sh
  ctest --test-dir build/host --output-on-failure
  tools/run_guest_tests.sh
  ```
  For one host test, use `ctest --test-dir build/host -R <test_name> --output-on-failure`; `elf_loader_test` and several translated probes need `tools/build_guest.sh` first.
- Android core build uses NDK r29 and API 29; see the verified configure command in `CLAUDE.md`. Launcher APK build also requires the runtime bundle first: `tools/make_launcher_bundle.sh`, then `cd android/launcher && ANDROID_HOME=$HOME/android-sdk ./gradlew assembleDebug`.
- Initial setup may require `git submodule update --init` and `tools/extract_sysroot.sh`. Dynarmic has required local patches recorded in `third_party/patches/`; do not alter or stage the submodule pointer as part of unrelated work.
- Android/device behavior cannot be validated by host tests alone. Use the runtime report and on-screen/clipboard/file diagnostics; OxygenOS may hide third-party logcat output. Device tests require the user's phone.
- Keep repo files English and ASCII. Commit messages use imperative English. Do not push without the user's agreement.
- When `graphify-out/graph.json` exists, run `graphify query "<question>"` before source search/read for codebase questions; use `graphify path`/`explain` for relationships/concepts. After code changes, run `graphify update .`.
- When merging upstream, resolve conflicts per hunk: choose the better implementation, preserve independent contributions from both sides, adapt duplicate upstream behavior to existing droidHLE helpers, and add regression tests for fixes. Never apply a blanket ours/theirs rule.
