// Android host layer (runtime/platform.h), arm64. Based on linux.c; what differs:
// - the guest window goes wherever the app process has room (android/common/guest_window.h) instead of a fixed
//   address: an app process is forked from the zygote, with its libraries already mapped, and can't re-exec itself;
// - stdout / stderr go to logcat (tag LASRecomp);
// - no execinfo: backtraces through the unwinder; no re-exec.
#define _GNU_SOURCE
#include <SDL3/SDL.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/ucontext.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#include "../platform.h"
#include "../rt.h"
#include "../../android/common/guest_window.h"

uint64_t g_guest_lo;  // the window's start (rt.h GUEST_LO), set by plat_reserve_guest before main()

// ---- logcat: the runtime logs with fprintf(stderr), as on the desktop; a thread forwards the lines
static void *log_pump(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[4096];
    size_t n = 0;
    for (;;) {
        ssize_t r = read(fd, buf + n, sizeof buf - 1 - n);
        if (r <= 0) break;
        n += (size_t)r;
        char *start = buf, *nl;
        while ((nl = memchr(start, '\n', (size_t)(buf + n - start)))) {
            *nl = 0;
            __android_log_write(ANDROID_LOG_INFO, "LASRecomp", start);
            start = nl + 1;
        }
        n = (size_t)(buf + n - start);
        memmove(buf, start, n);
        if (n == sizeof buf - 1) {  // (a line longer than the buffer: in pieces)
            buf[n] = 0;
            __android_log_write(ANDROID_LOG_INFO, "LASRecomp", buf);
            n = 0;
        }
    }
    return NULL;
}

static void log_to_logcat(void) {
    static bool done;
    if (done) return;
    done = true;
    int p[2];
    if (pipe(p) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    dup2(p[1], STDOUT_FILENO);
    dup2(p[1], STDERR_FILENO);
    close(p[1]);
    pthread_t t;
    if (pthread_create(&t, NULL, log_pump, (void *)(intptr_t)p[0]) == 0) pthread_detach(t);
}

// ---- guest address space
// Called by runtime/main.c before main() with GUEST_LO / GUEST_HI, while the window has no place yet (GUEST_LO = 0):
// picks one (the lowest 4 GB-aligned free hole above 4 GB and below 2^39) and sets g_guest_lo. Called with a set
// window (it never moves while the process lives), it claims exactly that range, as on Linux.
bool plat_reserve_guest(uint64_t lo, uint64_t hi) {
    log_to_logcat();
    if (!g_guest_lo) {
        uint64_t base = gw_reserve(hi - lo, GW_ALIGN, GW_FLOOR, GW_VA_LIMIT);
        if (!base) {
            fprintf(stderr, "FATAL: no room for the guest window (%#llx bytes below 2^39)\n", (unsigned long long)(hi - lo));
            return false;
        }
        g_guest_lo = base;
        fprintf(stderr, "[android] guest window %#llx-%#llx\n", (unsigned long long)base, (unsigned long long)(base + hi - lo));
        return true;
    }
    void *p = mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    if (p == MAP_FAILED) return false;
    if (p != (void *)(uintptr_t)lo) { munmap(p, hi - lo); return false; }
    return true;
}

// Fresh zero pages over the whole window (the old ones are released at once).
bool plat_reset_guest(uint64_t lo, uint64_t hi) {
    return mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED,
                -1, 0) == (void *)(uintptr_t)lo;
}

void plat_commit(uint64_t addr, uint64_t size) { (void)addr; (void)size; }

int plat_written_pages(uint64_t addr, uint64_t size, uint64_t *pages, int max, uint64_t *page) {  // (no write tracking)
    (void)addr; (void)size; (void)pages; (void)max; (void)page;
    return -1;
}

int plat_protect(uint64_t addr, uint64_t size, int prot) {
    int p = prot == PLAT_PROT_NONE ? PROT_NONE : prot == PLAT_PROT_READ ? PROT_READ : PROT_READ | PROT_WRITE;
    return mprotect((void *)(uintptr_t)addr, size, p);
}

// ---- time
uint64_t plat_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}

void plat_sleep_until_ns(uint64_t t) {
    struct timespec ts = {.tv_sec = (time_t)(t / 1000000000u), .tv_nsec = (long)(t % 1000000000u)};
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR) {}
}

// Apps can't have real-time scheduling: the display priority Android gives its own frame threads instead.
void plat_thread_realtime(uint64_t period_ns) {
    (void)period_ns;
    setpriority(PRIO_PROCESS, (id_t)gettid(), -8);  // (THREAD_PRIORITY_DISPLAY; fails quietly if not allowed)
}

// ---- process
void plat_init(void) { log_to_logcat(); }

// "The executable": a file in the folder the app copies its shipped resources to (internal storage, files/res: the
// Baloo font, the icon, ...), so runtime/paths.c finds them next to it as on the desktop. Native libraries stay in
// nativeLibraryDir (LARECOMP_NATIVE_LIB_DIR, set by the activity).
bool plat_exe_path(char *buf, size_t size) {
    const char *files = SDL_GetAndroidInternalStoragePath();
    if (!files) return false;
    return (size_t)snprintf(buf, size, "%s/res/lasrecomp", files) < size;
}

void plat_exec(char *const argv[]) { (void)argv; }  // (an app process can't replace itself: the caller reports it)

// ---- diagnostics
const char *plat_symbol(uint64_t pc, uint64_t *offset) {
    Dl_info di = {0};
    if (!dladdr((void *)(uintptr_t)pc, &di) || !di.dli_sname) return NULL;
    if (offset) *offset = pc - (uint64_t)(uintptr_t)di.dli_saddr;
    return di.dli_sname;
}

void plat_fatal_notice(void) {
    fflush(stderr);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "LAS Recomp", "LAS Recomp stopped because of an error.\n\n"
                             "Details: logcat, tag LASRecomp.", NULL);
}

const char *rt_host_fn_name(uint64_t pc, uint64_t *offset);  // runtime/dispatch.c

typedef struct { void **pcs; int n, max; } Trace;
static _Unwind_Reason_Code trace_step(struct _Unwind_Context *ctx, void *arg) {
    Trace *t = arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc && t->n < t->max) t->pcs[t->n++] = (void *)pc;
    return t->n < t->max ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void plat_print_backtrace(void) {
    void *bt[64];
    Trace t = {bt, 0, 64};
    _Unwind_Backtrace(trace_step, &t);
    Dl_info self = {0};
    dladdr((void *)plat_print_backtrace, &self);
    for (int i = 0; i < t.n; i++) {
        uint64_t pc = (uint64_t)(uintptr_t)bt[i], off = 0;
        Dl_info di = {0};
        if (!dladdr(bt[i], &di)) di.dli_fname = NULL;
        bool ours = di.dli_fbase && di.dli_fbase == self.dli_fbase;
        const char *s = plat_symbol(pc, &off);
        if (!s && ours) s = rt_host_fn_name(pc, &off);
        const char *mod = di.dli_fname ? (strrchr(di.dli_fname, '/') ? strrchr(di.dli_fname, '/') + 1 : di.dli_fname) : "?";
        uint64_t moff = pc - (uint64_t)(uintptr_t)di.dli_fbase;
        if (s) fprintf(stderr, "  #%-2d %016llx %s+%llx (%s+%llx)\n", i, (unsigned long long)pc, s, (unsigned long long)off,
                       mod, (unsigned long long)moff);
        else fprintf(stderr, "  #%-2d %016llx %s+%llx\n", i, (unsigned long long)pc, mod, (unsigned long long)moff);
    }
}

// (installed through ART's signal chain: ART still handles its own faults first)
static void crash_handler(int sig, siginfo_t *si, void *ctx) {
    ucontext_t *uc = ctx;
    static volatile int depth;
    if (sig == SIGABRT && depth++ > 0) { signal(SIGABRT, SIG_DFL); abort(); }  // (nested: just die)
    uint64_t fa = (uint64_t)(uintptr_t)si->si_addr;
    uint64_t pc = uc->uc_mcontext.pc, lr = uc->uc_mcontext.regs[30];
    if (rt_fault_watch(fa, pc)) return;
    char what[32];
    snprintf(what, sizeof what, "signal %d", sig);
    rt_fault_report(what, fa, pc, lr);
}

void plat_crash_handler_install(void) {
    stack_t ss = {.ss_sp = malloc(1 << 20), .ss_size = 1 << 20};
    sigaltstack(&ss, NULL);
    struct sigaction sa = {0};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
}

// ---- child processes (none of the desktop's helpers exist on Android yet: docs/ANDROID-PORT.md, Fases 6-7)
#include <ftw.h>
#include <spawn.h>
#include <sys/wait.h>
extern char **environ;

PlatProc plat_spawn(const char *const argv[], const char *log_path, bool append_log) {
    posix_spawn_file_actions_t fa;
    posix_spawn_file_actions_init(&fa);
    posix_spawn_file_actions_addopen(&fa, 1, log_path ? log_path : "/dev/null",
                                     O_WRONLY | O_CREAT | (append_log ? O_APPEND : O_TRUNC), 0644);
    posix_spawn_file_actions_adddup2(&fa, 1, 2);
    posix_spawnattr_t at;
    posix_spawnattr_init(&at);
    posix_spawnattr_setflags(&at, POSIX_SPAWN_SETPGROUP);
    posix_spawnattr_setpgroup(&at, 0);
    pid_t pid;
    int rc = posix_spawn(&pid, argv[0], &fa, &at, (char *const *)argv, environ);
    posix_spawnattr_destroy(&at);
    posix_spawn_file_actions_destroy(&fa);
    return rc ? 0 : (PlatProc)pid;
}

void plat_proc_kill(PlatProc p) {
    kill(-(pid_t)p, SIGKILL);
    waitpid((pid_t)p, NULL, 0);
}

int plat_proc_wait(PlatProc p, bool block) {
    int st = 0;
    pid_t r = waitpid((pid_t)p, &st, block ? 0 : WNOHANG);
    if (r == 0) return -1;
    if (r < 0) return 255;
    return WIFEXITED(st) ? WEXITSTATUS(st) : 255;
}

static int rm_entry(const char *path, const struct stat *sb, int flag, struct FTW *ftw) {
    (void)sb, (void)flag, (void)ftw;
    return remove(path);
}
bool plat_remove_tree(const char *dir) { return nftw(dir, rm_entry, 16, FTW_DEPTH | FTW_PHYS) == 0; }

void plat_terminate(void) { _exit(0); }
