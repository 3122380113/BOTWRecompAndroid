// Android: which Vulkan driver the backend uses. LARECOMP_VK_DRIVER=<path of a driver library in the app's internal
// storage> (the activity sets it from the user's choice, e.g. Turnip): loaded through adrenotools, in a linker
// namespace of its own (its hooks are in nativeLibraryDir, LARECOMP_NATIVE_LIB_DIR). Otherwise, or if that fails, the
// system driver. volk then takes every Vulkan function from it (device.c: volkLoadInstance / volkLoadDevice).
#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "vk.h"

void vk_android_load(void) {
    void *lib = NULL;
    const char *drv = getenv("LARECOMP_VK_DRIVER"), *hooks = getenv("LARECOMP_NATIVE_LIB_DIR");
    if (drv && *drv && hooks && *hooks) {
        const char *slash = strrchr(drv, '/');
        char dir[1024];
        if (slash && (size_t)snprintf(dir, sizeof dir, "%.*s/", (int)(slash - drv), drv) < sizeof dir)
            lib = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, NULL, hooks, dir, slash + 1,
                                             NULL, NULL);
        LOG("[vk] driver %s: %s\n", drv, lib ? "loaded through adrenotools" : "FAILED, using the system driver");
    }
    if (!lib) lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) FATAL("no Vulkan driver: %s\n", dlerror());
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) FATAL("the Vulkan driver has no vkGetInstanceProcAddr\n");
    volkInitializeCustom(gipa);
}
