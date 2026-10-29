# droidHLE

**Play old 32-bit Android games on 64-bit-only phones.**

droidHLE is a fork of [Afterlife Manager](https://github.com/etonedemid/afterlife-manager), which
is itself a fork of [ZettaBridge](https://github.com/ZailoxTT/ZettaBridge) by ZailoxTT. It runs a
32-bit app inside a 64-bit launcher process: Java code runs on the phone's normal ART, while its
ARM32 native code is translated to ARM64 with Dynarmic.

Thanks to both upstream projects and their contributors for the code and work this project builds
on. See [README.upstream.md](README.upstream.md) for the ZettaBridge project background and
[LICENSE](LICENSE) for the applicable license terms.

Tested on a Samsung Galaxy S25+ (SM-S936B, Snapdragon 8 Elite):

| Game | Engine | Status |
|---|---|---|
| Granny Smith (Mediocre) | NativeActivity | Playable, sound, input |
| Vector Full (Nekki) | Marmalade | Playable, sound, input, MSAA |

## What this fork adds

**Compatibility**
- Guest OpenSL ES (`guest/opensles/zbopensles.c`): all players mixed on one thread into a
  single 44.1 kHz stream, played by `GuestAudio` through `AudioTrack`.
- NDK input glue fixes: `AInputQueue_attachLooper` data argument, ident-only looper
  registrations.
- Implicit service intents fail like on pre-Lollipop Android, instead of crashing (billing).
- Libraries named after the package (Marmalade's `lib<app>.so`) and Apache HTTP legacy on the
  classpath for old targetSdk.
- The guest CPU reports as ARMv7, which stops Marmalade's "armv4t" warning dialog.

**Per-game settings**
- Graphics: 4x MSAA (native and Java EGL), 16x anisotropic filtering, full resolution,
  highest refresh rate.
- Compatibility: hide bundled ad SDK views.
- Tools: **memory editor**, a floating MEM button in game. Scan for a 4-byte integer or float,
  narrow the hits with next scan / changed / same / up / down, then set or freeze values.
- Debugging: precise faults, GL diagnostics.

**Launcher**
- Grid library with day/night theme, import button, home-screen shortcuts.

## Build

Needs Android NDK r29 (`29.0.14206865`), the Android SDK, CMake, Ninja, clang and Boost headers.

```
git submodule update --init; tools/extract_sysroot.sh; tools/build_guest.sh
```
```
export NDK=$HOME/Android/Sdk/ndk/29.0.14206865 NDK_HOST=linux-x86_64 ANDROID_HOME=$HOME/Android/Sdk; mkdir -p build/boost-headers; ln -sfn /usr/include/boost build/boost-headers/boost; cmake -S . -B build/android-arm64 -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release -DZB_BUILD_TESTS=OFF -DBoost_INCLUDE_DIR=$PWD/build/boost-headers; ninja -C build/android-arm64 zbridge zbproxy
```
```
tools/make_launcher_bundle.sh; cd android/launcher; ./gradlew assembleDebug; adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Translator internals, design docs and the upstream roadmap are in
[README.upstream.md](README.upstream.md) and [`docs/`](docs/).

## License

[PolyForm Noncommercial 1.0.0 + PolyForm Perimeter 1.0.1](LICENSE)
