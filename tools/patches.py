"""Per-instruction replacements applied by gen.py (guest address -> function(gen, addr) returning C code).

Each entry replaces the one instruction at that address of `main` (canonical address, 0x7000000000 + offset) with the C
the function returns; gen.py rebases the addresses in it for --dyn-base. Check the instruction word first (an assert),
so a wrong build fails here instead of patching the wrong code. Example, from LAS Recomp's widescreen: a camera's
`ldr sN, [xM, #imm]` of the 16:9 aspect literal, replaced by a call into the runtime:

    def _aspect_load(gen, a):
        w = struct.unpack_from('<I', gen.nso.image, a - gen.base)[0]
        assert (w & 0xffc00000) == 0xbd400000, hex(w)  # ldr s, [x, #imm]
        rt = w & 31
        return (f'{{ extern float rt_aspect_site(int); float f = rt_aspect_site(0); '
                f'c->v[{rt}] = 0; memcpy(&c->v[{rt}], &f, 4); }}  /* {a:#x}: ldr s{rt}, =16/9 */')

    PATCHES[0x7000xxxxxx] = _aspect_load

BOTW v1.0.0 (the code addresses are that build's; docs/BOTW-PORT.md):
- 0x70010c1aa0: act::ClusteredRenderer's teardown (f_70010c1a04) calls the virtual destroy of its display-list heap
  at this+0xc60 without a null check. Its setup (f_70010c084c) sets the "created" flag even when that 7 MB heap
  could not be made - here OpenWorldStage runs ~13 MB short and it never is - so a reload (dying, loading a save)
  crashed. A null heap now skips the destroy (the field and the flag are cleared as before).
"""
import struct

PATCHES = {}


def _clustered_dl_heap_null(gen, a):
    w = struct.unpack_from('<I', gen.nso.image, a - gen.base)[0]
    assert w == 0xf9400008, hex(w)  # ldr x8, [x0]
    return ('if (c->x[0] == 0) { c->x[21] = c->x[20] + 0xc60ull; goto L_70010c1ad8; }  /* (tools/patches.py) */ '
            '{ uint64_t ea = c->x[0] + 0x0ull; uint64_t v = LD64(ea); c->x[8] = (uint64_t)(v); }')


PATCHES[0x70010c1aa0] = _clustered_dl_heap_null
