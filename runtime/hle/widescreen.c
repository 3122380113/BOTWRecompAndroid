// Widescreen: what the renderers ask about the output's shape. BOTW draws a 16:9 view and nothing widens its main
// camera yet, so a window of another shape gets the 16:9 image with bars (widescreen_allowed false; LARECOMP_WIDESCREEN=1
// turns the wide render targets on for tests: the image then stretches). The renderers already set the wide factor
// (output aspect / 16:9) each frame. Work in progress: 1.8.2's one 16:9 constant (main 0x158afc8, loaded at
// 0x7001381c34) builds some cameras' projections, but not the main camera's (patched to rt_aspect_bits by hand in the
// generated code for tests: the image still stretched); the main camera's aspect comes from elsewhere.
// Found 2026-10-23 (LARECOMP_FIND_PROJ, vulkan/exec.c): the G-buffer's vertex shaders read the main camera's projection
// from their first constant buffer (0x920 bytes, P at +0x70: 1.2063 2.1445 -1.0001 -2.0001 - fovy 50 degrees, 16:9,
// near 1, far 25000). Its source is a sead::PerspectiveProjection (vtable 0x101ba4438; at 3df0c4598 on the Great Plateau
// save): +8 mDirty, +9 mDeviceDirty, +0xC mMatrix, +0x4C mDeviceMatrix, +0x98 near, far, fovy, sin, cos, tan, +0xB0
// mAspect. Setting mAspect (and the dirty flags) each frame from the render thread doesn't hold (LARECOMP_CAM_PROJ:
// the game sets 16:9 again before it builds the matrix), and a write watch on it caught nothing (as before: the placed
// heap). Next: hook where sead stores the aspect or rebuilds the matrix (the vtable's updateMatrixImpl_), so culling
// widens with it; then the HUD's orthographic projection, centred.
#include <stdbool.h>
#include <stdint.h>

#include <string.h>

#include "../hle.h"
#include "../rt.h"
#include "../settings.h"

static float g_wide = 1.0f;  // output aspect divided by 16:9 (1 = normal)

void widescreen_set_factor(float f) { g_wide = f; }
float widescreen_factor(void) { return g_wide; }

static bool g_allowed;  // (widescreen_set_allowed: tests)
bool widescreen_allowed(void) {
    return (g_allowed || ENV("LARECOMP_WIDESCREEN")) && g_settings.aspect == ASPECT_UNLOCKED && !ENV("LARECOMP_NO_WIDESCREEN");
}
void widescreen_set_allowed(bool on) { g_allowed = on; }

// A camera aspect at a patched site: 16:9 times the output's shape (g_wide: 1 unless widened)
uint32_t rt_aspect_bits(int site) {
    float a = 16.0f / 9.0f * (widescreen_allowed() ? g_wide : 1.0f);
    if (ENV("LARECOMP_LOG_ASPECT")) LOG("[widescreen] camera aspect %.4f (site %d)\n", a, site);
    uint32_t u;
    memcpy(&u, &a, 4);
    return u;
}

void widescreen_frame_end(uint64_t frame) { (void)frame; }

// Draws to stretch with the wide view (identified by fragment shader hash): none.
float widescreen_effect_stretch(uint64_t fs_hash) { (void)fs_hash; return 1.0f; }

void widescreen_backdrop_seen(uint64_t hash) { (void)hash; }
void widescreen_scene_ubo(const float *u, uint64_t size) { (void)u, (void)size; }

// Presented part of the image (crop: uv origin and size) and its place in the window: the whole image.
void widescreen_present_crop(uint64_t frame, double shape, float crop[4], double *vx, double *vy, double *vw, double *vh) {
    (void)frame, (void)shape, (void)vx, (void)vy, (void)vw, (void)vh;
    crop[0] = crop[1] = 0, crop[2] = crop[3] = 1;
}
