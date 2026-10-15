// On-screen controls on Android (docs/ANDROID-PORT.md, Fase 4): a left stick, A / B / X / Y, L / R / ZL / ZR,
// Minus / Plus and a button for the in-game menu, drawn over the game with ImGui (ui/menu.mm menu_render) while no
// controller is connected. Fingers come from SDL's touch events (ui_run, runtime/nvn/vulkan/device.c); what they hold
// joins the controller's buttons and stick in runtime/hle/input.c (g_touch_buttons, g_touch_lx / ly). The stick is
// floating: it starts where a finger lands in the left part of the screen. With the menu or the name entry up,
// touches stay mouse clicks for the menu. The Android back button opens the menu too.
#ifdef __ANDROID__
#include <SDL3/SDL.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>

#include "imgui.h"

extern "C" {
#include "settings.h"
bool menu_input_captured(void);  // ui/menu.mm
void menu_test_open(void);       // ui/menu.mm: opens the in-game menu
_Atomic uint64_t g_touch_buttons;  // (runtime/hle/input.c) Switch button bits held on the screen
_Atomic int32_t g_touch_lx, g_touch_ly;  // left stick, -32767..32767, y up
}

// the Switch button bits (runtime/hle/input.c)
enum { TB_A = 0, TB_B, TB_X, TB_Y, TB_LSTICK, TB_RSTICK, TB_L, TB_R, TB_ZL, TB_ZR, TB_PLUS, TB_MINUS };
enum { C_NONE = -1, C_STICK = 100, C_MENU = 101 };  // (0..15: a button bit)

typedef struct { float x, y, r; int bit; const char *label; bool rect; float w, h; } Ctl;

static pthread_mutex_t t_lock = PTHREAD_MUTEX_INITIALIZER;
static struct { SDL_FingerID id; int ctl; float x, y; } fingers[10];
static int nfingers;
static float stick_cx, stick_cy, stick_kx, stick_ky;  // where the stick started and where its knob is (pixels)
static bool stick_on;
static float disp_w = 1, disp_h = 1;  // the window in pixels (as last drawn)
static atomic_bool used;  // a touch since the last controller: show the controls
static atomic_bool hide_auto;  // a controller is connected

static int layout(Ctl *c) {  // in pixels of the window (landscape)
    float w = disp_w, h = disp_h, u = h / 100.0f;  // u: 1% of the height
    int n = 0;
    float br = 7.5f * u, gap = 13.0f * u;
    float fx = w - 20 * u, fy = h - 27 * u;  // face buttons: a diamond, Switch layout (A east)
    c[n++] = (Ctl){fx + gap, fy, br, TB_A, "A"};
    c[n++] = (Ctl){fx, fy + gap, br, TB_B, "B"};
    c[n++] = (Ctl){fx, fy - gap, br, TB_X, "X"};
    c[n++] = (Ctl){fx - gap, fy, br, TB_Y, "Y"};
    float sw = 15 * u, sh = 7 * u;  // shoulders: L / ZL at the top left, R / ZR at the top right
    c[n++] = (Ctl){4 * u + sw / 2, 20 * u, 0, TB_L, "L", true, sw, sh};
    c[n++] = (Ctl){4 * u + sw / 2, 10 * u, 0, TB_ZL, "ZL", true, sw, sh};
    c[n++] = (Ctl){w - 4 * u - sw / 2, 20 * u, 0, TB_R, "R", true, sw, sh};
    c[n++] = (Ctl){w - 4 * u - sw / 2, 10 * u, 0, TB_ZR, "ZR", true, sw, sh};
    c[n++] = (Ctl){w / 2 - 9 * u, h - 7 * u, 4.5f * u, TB_MINUS, "-"};
    c[n++] = (Ctl){w / 2 + 9 * u, h - 7 * u, 4.5f * u, TB_PLUS, "+"};
    c[n++] = (Ctl){w / 2, 6 * u, 4.5f * u, C_MENU, "="};
    return n;
}

static int hit(float x, float y) {
    Ctl c[16];
    int n = layout(c);
    float u = disp_h / 100.0f;
    for (int i = 0; i < n; i++) {
        if (c[i].rect) {
            if (fabsf(x - c[i].x) <= c[i].w / 2 + u && fabsf(y - c[i].y) <= c[i].h / 2 + u) return c[i].bit;
        } else {
            float dx = x - c[i].x, dy = y - c[i].y, r = c[i].r * 1.25f;  // (a bit larger than drawn: thumbs)
            if (dx * dx + dy * dy <= r * r) return c[i].bit;
        }
    }
    if (x < disp_w * 0.45f && y > disp_h * 0.30f) return C_STICK;
    return C_NONE;
}

static void stick_update(void) {  // (t_lock held)
    float R = disp_h * 0.13f;
    float dx = stick_kx - stick_cx, dy = stick_ky - stick_cy, d = sqrtf(dx * dx + dy * dy);
    if (d > R) { dx *= R / d, dy *= R / d; stick_kx = stick_cx + dx, stick_ky = stick_cy + dy; }
    g_touch_lx = (int32_t)(dx / R * 32767);
    g_touch_ly = (int32_t)(-dy / R * 32767);
}

static void recompute(void) {  // (t_lock held)
    uint64_t b = 0;
    for (int i = 0; i < nfingers; i++)
        if (fingers[i].ctl >= 0 && fingers[i].ctl < 16) b |= 1ull << fingers[i].ctl;
    g_touch_buttons = b;
}

static void release_all(void) {
    pthread_mutex_lock(&t_lock);
    nfingers = 0;
    stick_on = false;
    g_touch_buttons = 0, g_touch_lx = 0, g_touch_ly = 0;
    pthread_mutex_unlock(&t_lock);
}

extern "C" bool touch_controls_visible(void) {
    return !hide_auto && !menu_input_captured() && g_settings.touch_controls;
}

// SDL events (the UI thread): true when the event was for the on-screen controls.
extern "C" bool touch_controls_event(const SDL_Event *e) {
    switch (e->type) {
    case SDL_EVENT_GAMEPAD_ADDED: hide_auto = true; release_all(); return false;
    case SDL_EVENT_GAMEPAD_REMOVED: hide_auto = SDL_HasGamepad(); return false;
    case SDL_EVENT_KEY_DOWN:
        if (e->key.scancode == SDL_SCANCODE_AC_BACK && !e->key.repeat && !menu_input_captured()) { menu_test_open(); return true; }
        return false;
    case SDL_EVENT_FINGER_DOWN: case SDL_EVENT_FINGER_MOTION: case SDL_EVENT_FINGER_UP: break;
    default: return false;
    }
    if (menu_input_captured() || !g_settings.touch_controls) { release_all(); return false; }  // (the menu's clicks)
    used = true;
    if (hide_auto && !SDL_HasGamepad()) hide_auto = false;
    float x = e->tfinger.x * disp_w, y = e->tfinger.y * disp_h;
    pthread_mutex_lock(&t_lock);
    int k = -1;
    for (int i = 0; i < nfingers; i++) if (fingers[i].id == e->tfinger.fingerID) k = i;
    bool open_menu = false;
    if (e->type == SDL_EVENT_FINGER_DOWN && k < 0 && nfingers < 10) {
        int ctl = hit(x, y);
        if (ctl == C_STICK && stick_on) ctl = C_NONE;  // (one stick)
        fingers[nfingers].id = e->tfinger.fingerID, fingers[nfingers].ctl = ctl, fingers[nfingers].x = x, fingers[nfingers].y = y;
        nfingers++;
        if (ctl == C_STICK) { stick_on = true; stick_cx = stick_kx = x, stick_cy = stick_ky = y; stick_update(); }
        if (ctl == C_MENU) open_menu = true;
    } else if (e->type == SDL_EVENT_FINGER_MOTION && k >= 0) {
        fingers[k].x = x, fingers[k].y = y;
        if (fingers[k].ctl == C_STICK) { stick_kx = x, stick_ky = y; stick_update(); }
    } else if (e->type == SDL_EVENT_FINGER_UP && k >= 0) {
        if (fingers[k].ctl == C_STICK) { stick_on = false; g_touch_lx = 0, g_touch_ly = 0; }
        fingers[k] = fingers[--nfingers];
    }
    recompute();
    pthread_mutex_unlock(&t_lock);
    if (open_menu) { release_all(); menu_test_open(); }
    return true;
}

// Drawn with the in-game overlay (ui/menu.mm menu_render), between ImGui's NewFrame and Render.
extern "C" void touch_controls_draw(ImVec2 display, ImFont *font) {
    disp_w = display.x, disp_h = display.y;
    if (!touch_controls_visible()) return;
    ImDrawList *dl = ImGui::GetForegroundDrawList();
    Ctl c[16];
    int n = layout(c);
    uint64_t held = g_touch_buttons;
    float u = disp_h / 100.0f;
    float alpha = used ? 1.0f : 0.6f;
    ImU32 fill = IM_COL32(255, 255, 255, (int)(40 * alpha)), fill_on = IM_COL32(255, 255, 255, 120);
    ImU32 line = IM_COL32(255, 255, 255, (int)(110 * alpha)), text = IM_COL32(255, 255, 255, (int)(200 * alpha));
    for (int i = 0; i < n; i++) {
        bool on = c[i].bit >= 0 && c[i].bit < 16 && (held >> c[i].bit & 1);
        float fs = (c[i].rect ? c[i].h * 0.6f : c[i].r * 0.9f);
        ImVec2 ts = font->CalcTextSizeA(fs, FLT_MAX, 0, c[i].label);
        if (c[i].rect) {
            ImVec2 a(c[i].x - c[i].w / 2, c[i].y - c[i].h / 2), b(c[i].x + c[i].w / 2, c[i].y + c[i].h / 2);
            dl->AddRectFilled(a, b, on ? fill_on : fill, 1.5f * u);
            dl->AddRect(a, b, line, 1.5f * u, 0, 0.3f * u);
        } else {
            dl->AddCircleFilled(ImVec2(c[i].x, c[i].y), c[i].r, on ? fill_on : fill, 32);
            dl->AddCircle(ImVec2(c[i].x, c[i].y), c[i].r, line, 32, 0.3f * u);
        }
        if (c[i].bit == C_MENU) {  // three bars
            for (int k = -1; k <= 1; k++)
                dl->AddLine(ImVec2(c[i].x - 2 * u, c[i].y + k * 1.3f * u), ImVec2(c[i].x + 2 * u, c[i].y + k * 1.3f * u), text, 0.4f * u);
        } else {
            dl->AddText(font, fs, ImVec2(c[i].x - ts.x / 2, c[i].y - ts.y / 2), text, c[i].label);
        }
    }
    // the stick: where it started (or its resting place) and the knob
    pthread_mutex_lock(&t_lock);
    bool on = stick_on;
    float cx = on ? stick_cx : 20 * u, cy = on ? stick_cy : disp_h - 27 * u, kx = on ? stick_kx : cx, ky = on ? stick_ky : cy;
    pthread_mutex_unlock(&t_lock);
    float R = disp_h * 0.13f;
    dl->AddCircleFilled(ImVec2(cx, cy), R, fill, 48);
    dl->AddCircle(ImVec2(cx, cy), R, line, 48, 0.3f * u);
    dl->AddCircleFilled(ImVec2(kx, ky), R * 0.45f, on ? fill_on : IM_COL32(255, 255, 255, (int)(70 * alpha)), 32);
}
#endif
