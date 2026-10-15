// LAS Recomp on Android: SDL's activity runs the runtime (libmain.so, runtime/main.c). Before the native code starts
// this sets up what the desktop finds next to its executable (runtime/platform/android.c, runtime/paths.c):
// - the shipped resources (assets/res: the start screen's font, the icon) copied to <internal files>/res;
// - the Vulkan driver: the first driver in <external files>/drivers (a .so, or an adrenotools .zip: Turnip), copied to
//   internal storage (dlopen refuses external storage) and named in LARECOMP_VK_DRIVER; none there = the system's;
// - LARECOMP_NATIVE_LIB_DIR: where adrenotools finds its hook libraries.
// The data root (game files, saves, settings) is <external files>: /sdcard/Android/data/com.lasrecomp.android/files.
package com.lasrecomp;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LASRecompActivity extends SDLActivity {
    private static final String TAG = "LASRecomp";

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "shaderc_shared", "main"};
    }

    @Override
    protected void onCreate(Bundle state) {
        try {
            copyAssets("res", new File(getFilesDir(), "res"));
            setenv("LARECOMP_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir);
            File driver = customDriver();
            setenv("LARECOMP_VK_DRIVER", driver != null ? driver.getAbsolutePath() : "");
        } catch (IOException | ErrnoException e) {
            Log.e(TAG, "setup: " + e);
        }
        super.onCreate(state);
        // SDL's own immersive full screen (no status / navigation bar, the camera cutout used): SDL's onCreate asks
        // for the windowed style, and keeps the bars hidden once its full screen mode is on
        setWindowStyle(true);
    }

    private static void setenv(String name, String value) throws ErrnoException {
        Os.setenv(name, value, true);
    }

    private void copyAssets(String from, File to) throws IOException {
        AssetManager am = getAssets();
        String[] names = am.list(from);
        if (names == null) return;
        to.mkdirs();
        for (String n : names) {
            try (InputStream in = am.open(from + "/" + n)) {
                copy(in, new File(to, n));
            }
        }
    }

    // The first driver in <external files>/drivers, in internal storage (copied again only when it changed).
    private File customDriver() throws IOException {
        File src = new File(getExternalFilesDir(null), "drivers");
        src.mkdirs();
        File[] files = src.listFiles((d, n) -> n.endsWith(".so") || n.endsWith(".zip"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        File f = files[0];
        File dir = new File(getFilesDir(), "drivers/" + f.getName().replaceAll("\\.(so|zip)$", ""));
        File stamp = new File(dir, ".source");
        String id = f.length() + ":" + f.lastModified();
        File lib = findLib(dir);
        if (lib != null && stamp.exists() && id.equals(readSmall(stamp))) return lib;
        deleteTree(dir);
        dir.mkdirs();
        if (f.getName().endsWith(".so")) {
            try (InputStream in = new FileInputStream(f)) {
                lib = copy(in, new File(dir, f.getName()));
            }
        } else {
            lib = null;
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(f))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String n = new File(e.getName()).getName();
                    if (!e.isDirectory() && n.endsWith(".so")) lib = copyNoClose(z, new File(dir, n));
                }
            }
        }
        if (lib != null) {
            try (OutputStream o = new FileOutputStream(stamp)) {
                o.write(id.getBytes());
            }
        }
        Log.i(TAG, "Vulkan driver: " + f.getName() + " -> " + lib);
        return lib;
    }

    private static File findLib(File dir) {
        File[] libs = dir.listFiles((d, n) -> n.endsWith(".so"));
        return libs != null && libs.length > 0 ? libs[0] : null;
    }

    private static String readSmall(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes());
        }
    }

    private static File copy(InputStream in, File dst) throws IOException {
        return copyNoClose(in, dst);
    }

    private static File copyNoClose(InputStream in, File dst) throws IOException {
        try (OutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        return dst;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
