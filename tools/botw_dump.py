"""Check a BOTW dump: the exefs modules, their build ids (which game version), what each one is, and the romfs.

Usage: botw_dump.py [dump dir]   (default: the only folder under ../Dump, e.g. Dump/01007EF00011E000)

The dump is <dir>/exefs (main, rtld, sdk, subsdk0-3, main.npdm) and <dir>/romfs. Nothing is written.
"""
import argparse
import os
import sys

from nso import Nso

TITLE_ID = '01007EF00011E000'

# build id of main -> game version (the generator and the hooks are tied to one exact build)
VERSIONS = {
    '2094f152836683a3632c6153fd84ab80': '1.0.0',
}
TARGET = '1.0.0'

# what each module is (from its own build paths and the NEEDED list of main), and how the recompilation treats it
MODULES = [
    ('main', 'the game: KingSystem (U-King), sead, Havok, Effect/vfx, the game\'s own code', 'recompiled'),
    ('sdk', 'nnSdk: the Nintendo SDK (os, fs, hid, audio, ...)', 'recompiled, services HLE'),
    ('subsdk0', 'nvn.nss: the NVN driver (NVIDIA)', 'HLE: runtime/nvn translates NVN to Vulkan'),
    ('subsdk1', 'nnDisplay.nss: vi/display', 'HLE'),
    ('subsdk2', 'libcurl.nss: network', 'stub'),
    ('subsdk3', 'multimedia.nss: movie playback (nn::mm)', 'HLE: FFmpeg (runtime/hle/movie.c)'),
    ('rtld', 'the dynamic loader', 'not used: runtime/loader.c'),
]

ROMFS_DIRS = ['Actor', 'Effect', 'Event', 'Font', 'Game', 'Layout', 'Map', 'Model', 'Movie', 'NavMesh', 'Pack',
              'Physics', 'Sound', 'System', 'Terrain', 'UI', 'Voice']


def find_dump():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'Dump')
    subs = [d for d in sorted(os.listdir(root)) if os.path.isdir(os.path.join(root, d, 'exefs'))] \
        if os.path.isdir(root) else []
    if not subs:
        sys.exit(f'no dump under {os.path.normpath(root)} (expected Dump/<title id>/exefs and romfs)')
    return os.path.join(root, TITLE_ID if TITLE_ID in subs else subs[0])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('dump', nargs='?')
    args = ap.parse_args()
    dump = os.path.normpath(args.dump or find_dump())
    exefs, romfs = os.path.join(dump, 'exefs'), os.path.join(dump, 'romfs')
    print(f'dump: {dump}')
    ok = True

    for name, what, how in MODULES:
        path = os.path.join(exefs, name)
        if not os.path.isfile(path):
            print(f'  {name:8} MISSING')
            ok = False
            continue
        n = Nso(path)
        print(f'  {name:8} build {n.build_id[:32]}  image {n.size / 2**20:6.1f} MiB  '
              f'syms {len(n.syms):5}  relocs {len(n.relocs):6}')
        print(f'           {what} -> {how}')
        if name == 'main':
            version = VERSIONS.get(n.build_id[:32])
            needed = ', '.join(os.path.basename(x) for x in n.needed)
            print(f'           NEEDED: {needed}')
            if version is None:
                print(f'  main: unknown build: this project targets v{TARGET}')
                ok = False
            elif version != TARGET:
                print(f'  main: v{version}, this project targets v{TARGET}')
                ok = False
            else:
                print(f'  game version: v{version}')
    if not os.path.isfile(os.path.join(exefs, 'main.npdm')):
        print('  main.npdm MISSING')
        ok = False

    missing = [d for d in ROMFS_DIRS if not os.path.isdir(os.path.join(romfs, d))]
    if missing:
        print(f'  romfs: missing {", ".join(missing)}')
        ok = False
    else:
        files, size = 0, 0
        for dirpath, _, names in os.walk(romfs):
            for f in names:
                files += 1
                size += os.path.getsize(os.path.join(dirpath, f))
        print(f'  romfs: {files} files, {size / 2**30:.1f} GiB')

    print('OK' if ok else 'problems found')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
