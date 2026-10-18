# Contributing to BOTW Recomp

Thanks for wanting to help! Bug reports, testing on different hardware and code contributions are all welcome.

> [!NOTE]
> This project is developed mostly with AI (see the README). Contributions written by hand, with AI assistance or
> anything in between are equally welcome - what matters is that a change works, is tested, and follows the rules
> below.

## The one hard rule: no Nintendo files

Nothing owned by Nintendo may ever be committed or attached to an issue or pull request:

- no copies of the game in any form
- no dumps (`Dump/`), extracted game files (`extracted/`), generated code (`generated*/`) or fonts (`.bfttf` / Switch
  `.ttf`)
- no game assets of any kind: textures, models, music, text, map files - also not "just one file for testing"

Pull requests that include Nintendo files can't be merged. Screenshots and short clips of the game in bug reports are
fine.

## Reporting bugs

Open a [GitHub issue](https://github.com/mamcjr/BOTWRecomp/issues). Please include your platform (Windows / Linux /
macOS / Android), hardware (CPU, GPU, phone model and driver), what you did, what happened, and the log (run it from a
terminal; on Android: logcat, tag `BOTWRecomp`).

## Building

The plan and the state of each phase are in [docs/BOTW-PORT.md](docs/BOTW-PORT.md) (Portuguese). Windows:
[docs/BUILD-windows.md](docs/BUILD-windows.md); Linux: [docs/linux.md](docs/linux.md); Android: `android/`. Check
your dump with `cd tools && python botw_dump.py`; `make gen EXEFS=Dump/01007EF00011E000/exefs` recompiles the game
code from it.

## Project layout

- `tools/gen.py` - the recompiler: translates the game's executables into C (`generated/`, never committed).
  `tools/patches.py` holds code patches applied during generation; every patch site checks the original instruction
  word, so a wrong address fails loudly instead of silently.
- `runtime/` - the host side:
  - `kernel.c`, `loader.c`, `services/` - the Switch OS replacement
  - `hle/` - host implementations of SDK functions (input, movies, display, fonts) and the game hooks (`game.c`)
  - `nvn/` - the graphics layer: command recording (`cmdbuf.c`), Metal (`renderer.m`, `metal.m`) and Vulkan
    (`vulkan/`) backends, the shader translator (`maxwell/translate.c`)
  - `ui/` - the ImGui overlay menu and the start screen
- `android/` - the Android app (Gradle), its dependencies and the game code build for the phone.

## Testing your change

- Build cleanly on your platform. If you touch shared rendering code, say which backend you tested - a Metal change
  usually needs a Vulkan counterpart and the other way round.
- Test in game, and look at the result: a fix isn't verified until you've seen it work.
- Many debug hooks are documented where they're defined - grep for `getenv("LARECOMP_` (the engine's prefix, kept
  from LAS Recomp). Give test runs a scratch save folder (`LARECOMP_SAVE_DIR`) so your own saves stay untouched.
- State that belongs to one boot of the game must be reset when the game reboots in place: use an `RT_ON_RESET` hook
  (see `runtime/hle.h`).

## Code style

- Match the surrounding code: its naming, structure and comment density. Comments explain *why* (what the game does,
  what an address is, which bug showed the problem), not what the next line does.
- Engine changes that aren't about BOTW should stay easy to bring back to LAS Recomp (and the other way round).

## Pull requests

- Keep each pull request focused on one change; small is easier to review.
