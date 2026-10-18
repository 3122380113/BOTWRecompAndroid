# BOTW Recomp

**English** | [Português](README.pt-BR.md)

A static recompilation of *The Legend of Zelda: Breath of the Wild* (Nintendo Switch, **v1.0.0**) for PC (Windows,
Linux, macOS) and Android. This is **not emulation**: the game's ARM64 code becomes C compiled for your machine, and
its graphics (NVN) are translated to Vulkan.

It is built on the engine of **[LAS Recomp](https://github.com/peiikai/LASRecomp)** by peiikai (a recompilation of
*Link's Awakening*) and its Android port, **[LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)**.

> [!WARNING]
> **Early work in progress: the game does not run yet.** The plan, what is known about the game files and the state
> of each phase are in [docs/BOTW-PORT.md](docs/BOTW-PORT.md) (in Portuguese).

> [!IMPORTANT]
> Like LAS Recomp, this project is made almost entirely with AI (Claude, by Anthropic, through Claude Code), directed,
> tested and reviewed by a human.

> [!NOTE]
> **No game files are included.** You need your own legally obtained dump of *Breath of the Wild* v1.0.0
> (`exefs` and `romfs`). Instructions on how to obtain it will not be provided.

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
