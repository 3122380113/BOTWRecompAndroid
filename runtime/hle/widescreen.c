// Widescreen: what the renderers ask about the output's shape. BOTW draws a 16:9 view and nothing widens its camera
// yet, so a window of another shape gets the 16:9 image with bars (widescreen_allowed false). A widescreen for BOTW
// patches the camera's aspect (tools/patches.py) and turns this on; the renderers already set the wide factor (output
// aspect / 16:9) each frame and ask for the presented crop.
#include <stdbool.h>
#include <stdint.h>

#include "../hle.h"
#include "../settings.h"

static float g_wide = 1.0f;  // output aspect divided by 16:9 (1 = normal)

void widescreen_set_factor(float f) { g_wide = f; }
float widescreen_factor(void) { return g_wide; }

static bool g_allowed;  // (no camera hook for BOTW yet)
bool widescreen_allowed(void) { return g_allowed && g_settings.aspect == ASPECT_UNLOCKED && !ENV("LARECOMP_NO_WIDESCREEN"); }
void widescreen_set_allowed(bool on) { g_allowed = on; }

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
