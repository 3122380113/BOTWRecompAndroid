// Android host-side NVN regression tests. Include the implementation, discard unrelated HLE sections at link.
#include <assert.h>
#include "../runtime/nvn/objects.c"

static uint64_t test_frame;
static int destroyed, drains;
uint64_t renderer_present_count(void) { return test_frame; }
void backend_texture_destroyed(NvnTexture *t) { assert(t->refs == 0); destroyed++; }
void vk_async_drain(void) { drains++; }
void rt_fatal_trace(void) { abort(); }

static NvnTexture *new_texture(void) {
    NvnTexture *t = calloc(1, sizeof *t);
    live_add(t);
    return t;
}
int main(void) {
    NvnPool physical = {.storage = 0x300000000ull, .size = 0x7800000, .flags = 0x189};
    NvnPool virtual = {.size = physical.size, .flags = 0x2a1};
    uint64_t ph = (uintptr_t)&physical, vh = (uintptr_t)&virtual;
    uint64_t map[5] = {(uintptr_t)&ph, 0, 0, physical.size, 0};
    Cpu c = {0};
    c.x[0] = (uintptr_t)&vh; c.x[1] = 1; c.x[2] = (uintptr_t)map;
    hle_nvnMemoryPoolMapVirtual(&c);
    assert(c.x[0] == 1 && virtual.storage == physical.storage);
    // Invalid requests must not partially change the old alias.
    map[1] = 1;
    c.x[0] = (uintptr_t)&vh;
    hle_nvnMemoryPoolMapVirtual(&c);
    assert(c.x[0] == 0 && virtual.storage == physical.storage);
    map[1] = 0; map[3] /= 2;
    c.x[0] = (uintptr_t)&vh;
    hle_nvnMemoryPoolMapVirtual(&c);
    assert(c.x[0] == 0 && virtual.storage == physical.storage);

    NvnTexture *a = new_texture(), *b = new_texture();
    uint64_t ah = (uintptr_t)a, ah_copy = ah, bh = (uintptr_t)b;
    NvnTexEntry entries[2] = {0};
    NvnDescPool pool = {.count = 2, .entries = entries};
    uint64_t dh = (uintptr_t)&pool;
    c = (Cpu){0}; c.x[0] = (uintptr_t)&dh; c.x[2] = (uintptr_t)&ah;
    register_texture(&c);
    c.x[1] = 1;
    register_texture(&c);
    register_texture(&c);  // replacing a slot with the same texture must not add a reference
    assert(a->refs == 3);
    c.x[0] = (uintptr_t)&ah;
    hle_nvnTextureFinalize(&c);
    c.x[0] = (uintptr_t)&ah_copy;
    hle_nvnTextureFinalize(&c);  // a shallow guest copy must not release ownership twice
    assert(!ah && !ah_copy);
    test_frame = 10;
    c.x[0] = (uintptr_t)&dh; c.x[1] = 0; c.x[2] = (uintptr_t)&bh;
    register_texture(&c);
    assert(a->refs == 2 && destroyed == 0 && entries[1].tex == a);
    test_frame = 20;
    hle_nvnTexturePoolFinalize(&c);
    assert(!dh && !entries[0].tex && !entries[1].tex && destroyed == 0);
    test_frame = 30;
    c.x[0] = (uintptr_t)&bh;
    hle_nvnTextureFinalize(&c);
    assert(destroyed == 1 && b->refs == 1);
    NvnTexture *trigger = new_texture();
    test_frame = 40;
    texture_retire(trigger);
    assert(destroyed == 3 && drains == 3);
    textures_reset();
    assert(!g_all_tex && !g_retired && !g_nretired);

    // Unregistered CPU-only temporary textures (e.g., agl::TextureData::equal_ checks) must free immediately
    // on nvnTextureFinalize without queueing into g_retired or draining the GPU.
    int prev_destroyed = destroyed, prev_drains = drains;
    NvnTexture *tmp1 = new_texture(), *tmp2 = new_texture();
    tmp1->width = tmp2->width = 256;
    tmp1->height = tmp2->height = 256;
    tmp1->depth = tmp2->depth = 1;
    tmp1->levels = tmp2->levels = 1;
    tmp1->format = tmp2->format = 0x25;
    uint64_t t1h = (uintptr_t)tmp1, t2h = (uintptr_t)tmp2;
    c = (Cpu){0}; c.x[0] = (uintptr_t)&t1h; c.x[1] = (uintptr_t)&t2h;
    hle_nvnTextureCompare(&c);
    assert(c.x[0] == 1);
    tmp2->width = 128;
    c.x[0] = (uintptr_t)&t1h; c.x[1] = (uintptr_t)&t2h;
    hle_nvnTextureCompare(&c);
    assert(c.x[0] == 0);
    c.x[0] = (uintptr_t)&t1h;
    hle_nvnTextureFinalize(&c);
    c.x[0] = (uintptr_t)&t2h;
    hle_nvnTextureFinalize(&c);
    assert(destroyed == prev_destroyed + 2 && drains == prev_drains && g_nretired == 0);

    // Shallow guest copy registered with an NVNtextureView (agl::TextureSampler) must be tagged with
    // VIEW_WRAPPER_MAGIC and ignored by nvnTextureFinalize so the underlying agl::TextureData stays alive.
    NvnTexture *base = new_texture();
    uint64_t base_h = (uintptr_t)base;
    uint64_t wrapper_guest[24] = {base_h};
    uint64_t view_guest[5] = {1, 0, 0x25, 0, 0};
    NvnTexEntry ventries[2] = {0};
    NvnDescPool vpool = {.count = 2, .entries = ventries};
    uint64_t vdh = (uintptr_t)&vpool;
    c = (Cpu){0};
    c.x[0] = (uintptr_t)&vdh; c.x[1] = 1; c.x[2] = (uintptr_t)wrapper_guest; c.x[3] = (uintptr_t)view_guest;
    register_texture(&c);
    assert(wrapper_guest[1] == VIEW_WRAPPER_MAGIC && base->refs == 2);
    c = (Cpu){0}; c.x[0] = (uintptr_t)wrapper_guest;
    hle_nvnTextureFinalize(&c);  // must NOT drop base's guest ownership ref
    assert(base->refs == 2 && base_h == (uintptr_t)base);
    c = (Cpu){0}; c.x[0] = (uintptr_t)&vdh;
    hle_nvnTexturePoolFinalize(&c);
    c = (Cpu){0}; c.x[0] = (uintptr_t)&base_h;
    hle_nvnTextureFinalize(&c);
    test_frame += 10;
    NvnTexture *sweep = new_texture();
    texture_retire(sweep);
    assert(destroyed == prev_destroyed + 4 && g_nretired == 0 && !g_all_tex);
    textures_reset();

    // nvnSamplerCompare must ignore backend pointer differences at the end of NvnSamplerDesc.
    NvnSamplerDesc sb = {0}, s1 = {0}, s2 = {0};
    c = (Cpu){0}; c.x[0] = (uintptr_t)&sb;
    hle_nvnSamplerBuilderSetDefaults(&c);
    c.x[0] = (uintptr_t)&s1; c.x[1] = (uintptr_t)&sb;
    hle_nvnSamplerInitialize(&c);
    c.x[0] = (uintptr_t)&s2; c.x[1] = (uintptr_t)&sb;
    hle_nvnSamplerInitialize(&c);
    s1.backend = (void *)0x1234;
    s2.backend = (void *)0x5678;
    c.x[0] = (uintptr_t)&s1; c.x[1] = (uintptr_t)&s2;
    hle_nvnSamplerCompare(&c);
    assert(c.x[0] == 1);

    puts("PASS: virtual pool alias/bounds, descriptor retention, duplicate finalize, pool teardown, reset, compare & view wrapper");
}
