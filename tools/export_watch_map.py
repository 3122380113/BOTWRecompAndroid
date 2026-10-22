"""Build a local Zepp tile pyramid from the user's BOTW Switch ROMFS.

Requires Pillow and numpy. Generated Nintendo assets stay in ignored directories.
Only the base MainField textures are used; conditional world changes are not applied.
"""
import argparse
import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image


def yaz0(data):
    if data[:4] != b'Yaz0':
        return data
    size = int.from_bytes(data[4:8], 'big')
    if not 0 < size <= 32 * 1024 * 1024:
        raise ValueError('Invalid Yaz0 size')
    result = bytearray()
    offset = 16
    while len(result) < size:
        code = data[offset]
        offset += 1
        for bit in range(7, -1, -1):
            if len(result) == size:
                break
            if code & (1 << bit):
                result.append(data[offset])
                offset += 1
            else:
                a, b = data[offset:offset + 2]
                offset += 2
                distance = ((a & 15) << 8) | b
                count = a >> 4
                if not count:
                    count = data[offset] + 18
                    offset += 1
                else:
                    count += 2
                if distance >= len(result) or len(result) + count > size:
                    raise ValueError('Invalid Yaz0 back-reference')
                for _ in range(count):
                    result.append(result[-distance - 1])
    return bytes(result)


def map_texture(path):
    data = yaz0(path.read_bytes())
    start = data.index(b'BNTX\0\0\0\0')
    texture = memoryview(data)[start:]
    count = struct.unpack_from('<I', texture, 0x24)[0]
    if count != 1:
        raise ValueError(f'{path}: expected one texture')
    table = struct.unpack_from('<Q', texture, 0x28)[0]
    info = struct.unpack_from('<Q', texture, table)[0]
    if texture[info:info + 4] != b'BRTI':
        raise ValueError('Missing texture info')
    format_id, = struct.unpack_from('<I', texture, info + 0x1c)
    width, height = struct.unpack_from('<II', texture, info + 0x24)
    block_height, = struct.unpack_from('<I', texture, info + 0x34)
    if format_id >> 8 != 0x1a or not (0 < width <= 2048 and 0 < height <= 2048) or block_height > 4:
        raise ValueError(f'{path}: unsupported map format {format_id:x}, {width}x{height}')
    levels, = struct.unpack_from('<Q', texture, info + 0x70)
    payload, = struct.unpack_from('<Q', texture, levels)
    wb, hb = (width + 3) // 4, (height + 3) // 4
    row_bytes = wb * 8
    # Tegra GOB addressing, the same block-linear layout used by runtime/nvn/tegra.c.
    y, x = np.indices((hb, row_bytes), dtype=np.uint32)
    width_gobs = (row_bytes + 63) // 64
    gob_y = y >> 3
    address = ((gob_y >> block_height) * width_gobs + (x >> 6)) * (512 << block_height)
    address += (gob_y & ((1 << block_height) - 1)) * 512
    address += ((x & 63) >> 5) * 256 + ((y & 7) >> 1) * 64
    address += (((x & 63) >> 4) & 1) * 32 + (y & 1) * 16 + (x & 15)
    pixels = np.frombuffer(texture, dtype=np.uint8)[payload + address].tobytes()
    header = bytearray(128)
    header[:4] = b'DDS '
    struct.pack_into('<7I', header, 4, 124, 0x81007, height, width, len(pixels), 0, 1)
    struct.pack_into('<II4s', header, 76, 32, 4, b'DXT1')
    struct.pack_into('<I', header, 108, 0x1000)
    return Image.open(io.BytesIO(header + pixels)).convert('RGB')


def export(romfs, output, maximum=2048, tile_size=128):
    if maximum != 2048 or tile_size != 128:
        raise ValueError('The watch viewer requires maximum size 2048 and tile size 128')
    root = romfs / 'UI/MapTex/MainField'
    atlas = Image.new('RGB', (maximum, maximum))
    for column in range(10):
        for row in range(10):
            path = root / f'MapTex1_{chr(65 + column)}-{row}.sbmaptex'
            tile = map_texture(path)
            x0, y0 = round(column * maximum / 10), round(row * maximum / 10)
            x1, y1 = round((column + 1) * maximum / 10), round((row + 1) * maximum / 10)
            atlas.paste(tile.resize((x1 - x0, y1 - y0), Image.Resampling.LANCZOS), (x0, y0))
        print(f'Converted map column {chr(65 + column)}', flush=True)
    output.mkdir(parents=True, exist_ok=True)
    levels = []
    size = 256
    while size <= maximum:
        image = atlas.resize((size, size), Image.Resampling.LANCZOS)
        level = len(levels)
        directory = output / str(level)
        directory.mkdir(exist_ok=True)
        for y in range(size // tile_size):
            for x in range(size // tile_size):
                image.crop((x * tile_size, y * tile_size, (x + 1) * tile_size, (y + 1) * tile_size)).save(directory / f'{x}-{y}.png')
        levels.append({'size': size, 'tiles': size // tile_size})
        size *= 2
    metadata = {'version': 1, 'map': 'MainField', 'tileSize': tile_size,
                'bounds': {'minX': -5000, 'maxX': 5000, 'minZ': -5000, 'maxZ': 5000},
                'levels': levels}
    (output / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--romfs', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--max-size', type=int, default=2048)
    args = parser.parse_args()
    export(args.romfs, args.output, args.max_size)
