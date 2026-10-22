// BOTWRecompAndroid on Android: SDL's activity runs the runtime (libmain.so, runtime/main.c). Before the native code starts
// this sets up what the desktop finds next to its executable (runtime/platform/android.c, runtime/paths.c):
// - the shipped resources (assets/res: the start screen's font, the icon) copied to <internal files>/res;
// - the Vulkan driver: the first driver in <external files>/drivers (a .so, or an adrenotools .zip: Turnip), copied to
//   internal storage (dlopen refuses external storage) and named in LARECOMP_VK_DRIVER; none there = the system's;
// - LARECOMP_NATIVE_LIB_DIR: where adrenotools finds its hook libraries;
// - the game code (botwgame.so + botwgame.so.id, built from the player's own game files: android/build_gamelib.sh) from
//   <external files>/extracted to <internal files>/res, where runtime/gamelib.c looks for it next to "the executable".
// - the game code's builder (docs/las/ANDROID-PORT.md, Fase 6): assets/gen (the generator, its Python's standard library,
//   zig's headers) unpacked to <internal files>/gen once per app version, named in LARECOMP_GEN_DIR; its programs
//   (libpymain.so, libzig.so) are in nativeLibraryDir;
// - the system's pickers for the start screen (runtime/ui/launcher.mm): the game files folder (exefs/ and romfs/ are
//   found in it and copied to <external files>/extracted) and the Vulkan driver (a .zip copied to the drivers folder).
// The data root (game files, saves, settings) is <external files>: /sdcard/Android/data/com.botwrecomp.android/files.
// While in front it reports the battery and thermal state once a second for the performance overlay
// (runtime/ui/perf_overlay.mm): apps can't read those in /sys.
package com.botwrecompandroid;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BOTWRecompAndroidActivity extends SDLActivity {
    private static final String TAG = "BOTWRecompAndroid";

    private final Handler stats = new Handler(Looper.getMainLooper());
    private final Runnable report = new Runnable() {
        @Override
        public void run() {
            reportDeviceStats();
            stats.postDelayed(this, 1000);
        }
    };

    private static native void nativeDeviceStats(float battTempC, float battPct, float watts, int thermal, float headroom);
    // the start screen (runtime/ui/launcher.mm)
    private static native boolean nativeBuildIdOk(byte[] head);
    private static native void nativeImportBegin(String name);
    private static native void nativeImportProgress(long done, long total);
    private static native void nativeImportDone(int result);
    private static native void nativeDriverPicked(String name);

    private static final int REQ_GAME = 0x4c41, REQ_DRIVER = 0x4c42;
    private static BOTWRecompAndroidActivity self;
    private WatchMapServer watchMap;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "shaderc_shared", "main"};
    }

    @Override
    protected void onCreate(Bundle state) {
        try {
            File res = new File(getFilesDir(), "res");
            copyAssets("res", res);
            File games = new File(getExternalFilesDir(null), "extracted");
            copyIfChanged(new File(games, "botwgame.so"), new File(res, "botwgame.so"));
            copyIfChanged(new File(games, "botwgame.so.id"), new File(res, "botwgame.so.id"));
            setenv("LARECOMP_NATIVE_LIB_DIR", getApplicationInfo().nativeLibraryDir);
            File gen = new File(getFilesDir(), "gen");
            unpackGen(gen);
            setenv("LARECOMP_GEN_DIR", gen.getAbsolutePath());
            if (getIntent() != null && SYSTEM_DRIVER.equals(getIntent().getAction())) dropCustomDriver();
            File driver = customDriver();
            setenv("LARECOMP_VK_DRIVER", driver != null ? driver.getAbsolutePath() : "");
            // testing: the runtime's LARECOMP_* switches from the launch intent, separated by commas (adb shell drops
            // the quotes around spaces), e.g. LARECOMP_VK_DRIVER= is the system driver:
            // adb shell am start -n com.botwrecomp.android/com.botwrecompandroid.BOTWRecompAndroidActivity --es env LARECOMP_NO_LAUNCHER=1,LARECOMP_VK_DRIVER=
            String env = getIntent() != null ? getIntent().getStringExtra("env") : null;
            // Opened again from the recent apps, Android hands back that test launch's intent: keep only which game
            // version it ran (a scripted input left in it ignored the touch screen - the menu looked frozen)
            boolean recents = getIntent() != null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
            if (env != null) {  // (a piece without '=' continues the previous value: LARECOMP_GPUPROF_AT=1500,1750)
                String name = null, value = null;
                for (String kv : env.trim().split("[,\\s]+")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        if (name != null && (!recents || KEEP_FROM_RECENTS.contains(name))) setenv(name, value);
                        name = kv.substring(0, eq);
                        value = kv.substring(eq + 1);
                    } else if (name != null) value += "," + kv;
                }
                if (name != null && (!recents || KEEP_FROM_RECENTS.contains(name))) setenv(name, value);
                // (debugging) the game code built on the phone, out where adb can read it: a crash's host addresses
                if (env.contains("LARECOMP_EXPORT_GAMELIB")) {
                    File lib = new File(res, "botwgame.so");
                    if (lib.exists()) try (InputStream in = new FileInputStream(lib)) {
                        copy(in, new File(getExternalFilesDir(null), "botwgame-export.so"));
                    }
                }
            }
        } catch (IOException | ErrnoException e) {
            Log.e(TAG, "setup: " + e);
        }
        self = this;
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        super.onCreate(state);
        // SDL's own immersive full screen (no status / navigation bar, the camera cutout used): SDL's onCreate asks
        // for the windowed style, and keeps the bars hidden once its full screen mode is on
        setWindowStyle(true);
        watchMap = new WatchMapServer(this);
        if (getIntent() != null && WatchMapServer.ACTION.equals(getIntent().getAction()))
            stats.post(() -> watchMap.showSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        stats.post(report);
        if (watchMap != null) watchMap.resume();
    }

    @Override
    protected void onPause() {
        stats.removeCallbacks(report);
        if (watchMap != null) watchMap.pause();
        super.onPause();
    }

    // Battery temperature, charge and power draw, and Android's thermal state (runtime/platform/android.c).
    private void reportDeviceStats() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            float temp = -1, pct = -1, watts = 0;
            if (b != null) {
                temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) pct = level * 100f / scale;
                int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                BatteryManager bm = getSystemService(BatteryManager.class);
                long now = bm != null ? Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) : 0;
                if (now > 0 && now < 20000) now *= 1000;  // (some devices report mA instead of uA)
                watts = now * (float) mv / 1e9f;
            }
            PowerManager pm = getSystemService(PowerManager.class);
            int thermal = pm != null ? pm.getCurrentThermalStatus() : -1;
            float headroom = pm != null ? pm.getThermalHeadroom(10) : -1;
            nativeDeviceStats(temp, pct, watts, thermal, Float.isNaN(headroom) ? -1 : headroom);
        } catch (Throwable t) {  // (also before libmain is loaded: UnsatisfiedLinkError)
            Log.w(TAG, "device stats: " + t);
        }
    }

    // ---- the game code's builder: assets/gen, unpacked once per installed version (runtime/gamebuild.c)
    private void unpackGen(File dir) throws IOException {
        String[] top = getAssets().list("gen");
        if (top == null || top.length == 0) return;  // (an APK without it: android/deps/build_gen_deps.sh not run)
        String stamp;
        try {
            stamp = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            stamp = "?";
        }
        File st = new File(dir, ".stamp");
        if (st.exists() && stamp.equals(readSmall(st))) return;
        deleteTree(dir);
        copyAssetTree("gen", dir);
        try (OutputStream o = new FileOutputStream(st)) {
            o.write(stamp.getBytes());
        }
        Log.i(TAG, "unpacked the game code builder to " + dir);
    }

    private void copyAssetTree(String from, File to) throws IOException {
        AssetManager am = getAssets();
        String[] names = am.list(from);
        if (names == null || names.length == 0) {  // a file (or an empty folder)
            try (InputStream in = am.open(from)) {
                to.getParentFile().mkdirs();
                copy(in, to);
            } catch (IOException e) {
                to.mkdirs();
            }
            return;
        }
        to.mkdirs();
        for (String n : names) copyAssetTree(from + "/" + n, new File(to, n));
    }

    // ---- pickers (called from the start screen, on SDL's thread)
    public static void pickGameFolder() {
        self.runOnUiThread(() -> {
            try {
                self.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_GAME);
            } catch (Exception e) {
                Log.e(TAG, "folder picker: " + e);
                nativeImportDone(1);
            }
        });
    }

    public static void pickDriver() {
        self.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                self.startActivityForResult(i, REQ_DRIVER);
            } catch (Exception e) {
                Log.e(TAG, "file picker: " + e);
            }
        });
    }

    // Back to the system's Vulkan driver: the chosen package and its unpacked copy go (from the next start on)
    public static void useSystemDriver() {
        new Thread(() -> {
            self.dropCustomDriver();
            nativeDriverPicked("-");
        }, "driver").start();
    }

    private void dropCustomDriver() {
        deleteTree(new File(getExternalFilesDir(null), "drivers"));
        deleteTree(new File(getFilesDir(), "drivers"));
    }

    // The app icon's long-press shortcut (res/xml/shortcuts.xml): the system driver, settings out of reach or not - a
    // custom driver that draws garbage (Turnip_v26.3.0-R6 on an Adreno 830: stripes, the menus unreadable) or crashes.
    // Before the driver loads when it starts the app; a running one (a single instance) drops it and starts again.
    static final String SYSTEM_DRIVER = "com.botwrecompandroid.action.SYSTEM_DRIVER";
    // (android/run_ver.sh: the game version a test launch ran, and its own start without the launcher)
    static final List<String> KEEP_FROM_RECENTS = Arrays.asList("LARECOMP_DATA_ROOT", "LARECOMP_GAME_LIB",
            "LARECOMP_SAVE_SEED", "LARECOMP_NO_LAUNCHER");

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && WatchMapServer.ACTION.equals(intent.getAction()) && watchMap != null)
            watchMap.showSettings();
        if (intent != null && SYSTEM_DRIVER.equals(intent.getAction())) {
            Log.i(TAG, "shortcut: back to the system driver");
            dropCustomDriver();
            restartApp();
        }
    }

    // a new process: the driver is loaded once, at start
    public static void restartApp() {
        self.runOnUiThread(() -> {
            Intent i = self.getPackageManager().getLaunchIntentForPackage(self.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                self.startActivity(i);
            }
            System.exit(0);
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_GAME) {
            Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (tree == null) nativeImportDone(1);
            else new Thread(() -> importGame(tree), "import").start();
            return;
        }
        if (req == REQ_DRIVER) {
            Uri f = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (f != null) new Thread(() -> importDriver(f), "driver").start();
            return;
        }
        super.onActivityResult(req, result, data);
    }

    // A document in the picked tree: id, name, folder or not, size
    private static final class Doc {
        String id, name;
        boolean dir;
        long size;
    }

    private List<Doc> children(Uri tree, String id) {
        List<Doc> out = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                         DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor c = getContentResolver().query(u, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                d.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                d.size = c.isNull(3) ? 0 : c.getLong(3);
                out.add(d);
            }
        }
        return out;
    }

    private static Doc named(List<Doc> l, String name) {
        for (Doc d : l) if (d.name != null && d.name.equals(name)) return d;
        return null;
    }

    // exefs: the folder with main and main.npdm; romfs: the folder with Pack - in the picked folder or a few
    // levels below it (as runtime/ui/launcher.mm find_game)
    private Doc exefs, romfs;
    private boolean packages;

    private void find(Uri tree, Doc dir, int depth) {
        if (depth > 4 || (exefs != null && romfs != null)) return;
        List<Doc> kids = children(tree, dir.id);
        if (exefs == null && named(kids, "main") != null && named(kids, "main.npdm") != null) exefs = dir;
        Doc rc = named(kids, "Pack");
        if (romfs == null && rc != null && rc.dir) romfs = dir;
        for (Doc k : kids) {
            String n = k.name == null ? "" : k.name.toLowerCase();
            if (n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nca") || n.endsWith(".nsz") || n.endsWith(".xcz")) packages = true;
            if (k.dir && !"Pack".equals(k.name)) find(tree, k, depth + 1);
        }
    }

    private long treeBytes(Uri tree, Doc dir) {
        long n = 0;
        for (Doc k : children(tree, dir.id)) n += k.dir ? treeBytes(tree, k) : k.size;
        return n;
    }

    private long copied, total, reported;

    private void copyTree(Uri tree, Doc dir, File to) throws IOException {
        to.mkdirs();
        ContentResolver cr = getContentResolver();
        for (Doc k : children(tree, dir.id)) {
            File dst = new File(to, k.name);
            if (k.dir) {
                copyTree(tree, k, dst);
                continue;
            }
            try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, k.id));
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 20];
                for (int r; (r = in.read(buf)) > 0; ) {
                    o.write(buf, 0, r);
                    copied += r;
                    if (copied - reported > (16 << 20)) {
                        reported = copied;
                        nativeImportProgress(copied, total);
                    }
                }
            }
        }
    }

    private void importGame(Uri tree) {
        int result;
        try {
            exefs = romfs = null;
            packages = false;
            Doc root = new Doc();
            root.id = DocumentsContract.getTreeDocumentId(tree);
            root.dir = true;
            find(tree, root, 0);
            if (exefs == null || romfs == null) {
                nativeImportDone(packages ? 3 : 2);
                return;
            }
            Doc main = named(children(tree, exefs.id), "main");
            byte[] head = new byte[0x48];
            try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, main.id))) {
                int got = 0;
                for (int r; got < head.length && (r = in.read(head, got, head.length - got)) > 0; ) got += r;
            }
            if (!nativeBuildIdOk(head)) {
                nativeImportDone(4);
                return;
            }
            String name = DocumentsContract.getTreeDocumentId(tree);
            nativeImportBegin(name.substring(name.lastIndexOf(':') + 1));
            File games = new File(getExternalFilesDir(null), "extracted");
            copied = reported = 0;
            total = treeBytes(tree, romfs) + treeBytes(tree, exefs);
            copyTree(tree, exefs, new File(games, "exefs"));
            copyTree(tree, romfs, new File(games, "romfs"));
            nativeImportProgress(copied, total);
            result = 0;
        } catch (Exception e) {
            Log.e(TAG, "game files: " + e);
            result = 5;
        }
        nativeImportDone(result);
    }

    // A driver package (.zip, or a bare .so) into <external files>/drivers, in place of any there (customDriver loads
    // it at the next start)
    private void importDriver(Uri f) {
        String name = "driver.zip";
        try (Cursor c = getContentResolver().query(f, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) {
        }
        String low = name.toLowerCase();
        if (!low.endsWith(".zip") && !low.endsWith(".so")) {
            nativeDriverPicked("");
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "drivers");
            deleteTree(dir);
            dir.mkdirs();
            try (InputStream in = getContentResolver().openInputStream(f)) {
                copy(in, new File(dir, name));
            }
            nativeDriverPicked(name);
        } catch (Exception e) {
            Log.e(TAG, "driver: " + e);
            nativeDriverPicked("");
        }
    }

    // (by size and date: the game code is ~100 MB)
    private static void copyIfChanged(File src, File dst) throws IOException {
        if (!src.exists()) return;
        if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) return;
        try (InputStream in = new FileInputStream(src)) {
            copy(in, dst);
        }
        Log.i(TAG, "copied " + src + " -> " + dst);
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
