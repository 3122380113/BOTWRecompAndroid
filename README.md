# BOTWRecompAndroid

**English** | [Português](README.pt-BR.md)

A static recompilation of *The Legend of Zelda: Breath of the Wild* (Nintendo Switch, **v1.0.0**) for Android. This is **not emulation**: the game's ARM64 code becomes C compiled for your machine, and
its graphics (NVN) are translated to Vulkan.

It is built on the engine of **[LAS Recomp](https://github.com/peiikai/LASRecomp)** by peiikai (a recompilation of
*Link's Awakening*) and its Android port, **[LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)**.

> [!WARNING]
> **Early work in progress: not playable.** The plan, what is known about the game files and the state of each phase
> are in [docs/BOTW-PORT.md](docs/BOTW-PORT.md) (in Portuguese).

## Supported platform

Android is the only application target. Desktop ports and packaging were discontinued on 2026-10-22.
Windows, Linux or macOS may still be used as development hosts with the Android SDK/NDK.

## Status

- **Recompilation:** the whole game code (main: 104,671 functions; sdk: 17,689) is lifted to C with no unsupported
  instruction and builds for Android (NDK).
- **Android (first tests on a POCO F7 Ultra, Qualcomm driver):** the game boots through the 2017 SDK's startup, sets
  up graphics (NVN 52 to Vulkan), audio, input and saves, loads its data and **presents its first frames**. It still
  crashes after a few seconds: the order between threads that share the Switch's core 0 is the current work.
- **Not done yet:** a picture on screen to look at, shaders using Maxwell instructions the translator doesn't know,
  movies.

> [!IMPORTANT]
> Like LAS Recomp, this project is made almost entirely with AI (Claude, by Anthropic, through Claude Code), directed,
> tested and reviewed by a human.

> [!NOTE]
> **No game files are included.** You need your own legally obtained dump of *Breath of the Wild* v1.0.0
> (`exefs` and `romfs`). Instructions on how to obtain it will not be provided.

## Testing on Android (developers)

With the Android SDK / NDK, a phone on adb and your dump in `Dump/`:

```bash
cd android/botwrecompandroid && ./gradlew assembleDebug    # the app
android/build_gamelib.sh Dump/01007EF00011E000/exefs  # the game code (about an hour)
android/push_game.sh Dump/01007EF00011E000           # game files and game code onto the phone
android/run_boot.sh 120                               # start the game, log to android/out/boot.log
```

## Checking your dump

Put the dump in `Dump/<title id>/` (`Dump/01007EF00011E000/exefs` and `.../romfs`; the folder is ignored by git),
then, with Python and the `lz4` package:

```bash
cd tools && python botw_dump.py
```

It checks every module, identifies the game version from the build id of `main` and counts the romfs.

## Credits

- [peiikai](https://github.com/peiikai) for LAS Recomp, the recompiler and runtime this project starts from
  (MIT, see [LICENSE](LICENSE)). The original README is kept in [docs/las/](docs/las/).
- The [zeldaret/botw](https://github.com/zeldaret/botw) decompilation, for what is known about the game's code.
- [libadrenotools](https://github.com/bylaws/libadrenotools), [SDL](https://libsdl.org),
  [Dear ImGui](https://github.com/ocornut/imgui), [FFmpeg](https://ffmpeg.org).

*The Legend of Zelda* and *Breath of the Wild* are trademarks of Nintendo. This project is not affiliated with or
endorsed by Nintendo.
