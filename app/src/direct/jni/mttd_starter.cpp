// Ported from RikkaApps/Shizuku (manager/src/main/jni/starter.cpp, misc.cpp),
// licensed under Apache License 2.0. See THIRD_PARTY_NOTICES.md.
//
// Adapted: root-only code paths removed (SELinux binder check, cgroup switch, mount namespace
// switch in starter.cpp's main()) — mTTD's `direct` flavor never runs as root, only as the adb
// shell user (uid 2000), and Shizuku itself only exercises those paths when uid==0. Process name
// and target class are passed via argv instead of Shizuku's own #define constants. The
// "kill old process by name" step (foreach_proc/get_proc_name, from misc.cpp) is kept as-is.
//
// Launched by DirectAdbManager.launchDaemon() over the wireless-debugging adb connection to start
// a persistent shell-UID daemon (DirectDaemonStarter.kt, passed via --class=) — that daemon then
// keeps working via pure Binder IPC even after the adb connection itself drops (Wi-Fi → LTE, etc.),
// since it's only used once here to bootstrap the process, not for every file read afterward.
// (Confirmed on-device: sending the equivalent `app_process` invocation as a shell command string
// via `adb shell "..."` instead of this real fork()+setsid()+execvp() binary was NOT the cause of
// an earlier SIGKILL seen during development — that turned out to be an unrelated bug, launching
// a class with no main(). Noted here since it was a real, if ultimately wrong, hypothesis worth
// remembering if something like it resurfaces.)
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <libgen.h>
#include <sys/stat.h>
#include <signal.h>
#include <cerrno>
#include <string>
#include <vector>
#include "logging.h"

#define perrorf(...) fprintf(stderr, __VA_ARGS__)

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_APK_PATH 7
#define EXIT_FATAL_ARGS 8

#if defined(__arm__)
#define ABI "arm"
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__aarch64__)
#define ABI "arm64"
#endif

// --- ported from misc.cpp (only the pieces the shell/adb path needs) ---

static int get_proc_name(int pid, char *name, size_t size) {
    char buf[PATH_MAX];
    snprintf(buf, sizeof(buf), "/proc/%d/cmdline", pid);
    int fd = open(buf, O_RDONLY);
    if (fd == -1) return 1;
    name[0] = '\0';
    ssize_t ret;
    do {
        ret = read(fd, name, size - 1);
    } while (ret < 0 && errno == EINTR);
    if (ret >= 0) name[ret] = '\0';
    close(fd);
    return 0;
}

static int is_num(const char *s) {
    size_t len = strlen(s);
    for (size_t i = 0; i < len; ++i)
        if (s[i] < '0' || s[i] > '9') return 0;
    return 1;
}

static void foreach_proc(void (*func)(pid_t)) {
    DIR *dir = opendir("/proc");
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (entry->d_type != DT_DIR) continue;
        if (!is_num(entry->d_name)) continue;
        func((pid_t) atoi(entry->d_name));
    }
    closedir(dir);
}

// foreach_proc's callback is a plain function pointer (no capture), so the name to match against
// is stashed here instead of being captured — set right before the single call site below.
static char g_kill_target_name[256];
static pid_t g_self_pid;

static void kill_if_matching_name(pid_t pid) {
    if (pid == g_self_pid) return;
    char name[256];
    if (get_proc_name(pid, name, sizeof(name)) != 0) return;
    if (strcmp(g_kill_target_name, name) != 0) return;
    if (kill(pid, SIGKILL) == 0) {
        printf("info: killed old process %d (%s)\n", pid, name);
    }
}

// --- adapted from starter.cpp ---

// [passthrough_args] are appended after main_class in the app_process invocation — they become
// main_class's own `String[] args` (e.g. DirectDaemonStarter reads --authority=/--pkg= from
// these). Forgetting to forward these was a real bug caught on-device: the java side threw
// "--authority= required" because nothing past --apk=/--class=/--name= ever reached it.
static void run_server(
    const char *dex_path, const char *main_class, const char *process_name,
    const std::vector<std::string> &passthrough_args
) {
    if (setenv("CLASSPATH", dex_path, true)) {
        LOGE("can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

    char dex_path_copy[PATH_MAX];
    strncpy(dex_path_copy, dex_path, PATH_MAX - 1);
    dex_path_copy[PATH_MAX - 1] = '\0';
    char lib_path[PATH_MAX]{0};
    snprintf(lib_path, PATH_MAX, "%s/lib/%s", dirname(dex_path_copy), ABI);

    char class_path_arg[PATH_MAX + 32];
    snprintf(class_path_arg, sizeof(class_path_arg), "-Djava.class.path=%s", dex_path);
    char lib_path_arg[PATH_MAX + 32];
    snprintf(lib_path_arg, sizeof(lib_path_arg), "-Dmttd.library.path=%s", lib_path);
    char nice_name_arg[256];
    snprintf(nice_name_arg, sizeof(nice_name_arg), "--nice-name=%s", process_name);

    std::vector<const char *> argv;
    argv.push_back("/system/bin/app_process");
    argv.push_back(class_path_arg);
    argv.push_back(lib_path_arg);
    argv.push_back("/system/bin");
    argv.push_back(nice_name_arg);
    argv.push_back(main_class);
    for (const auto &a : passthrough_args) argv.push_back(a.c_str());
    argv.push_back(nullptr);

    LOGD("exec app_process: class=%s process_name=%s passthrough_count=%zu",
         main_class, process_name, passthrough_args.size());

    execvp(argv[0], (char *const *) argv.data());
    // execvp only returns on failure.
    LOGE("execvp app_process failed: %s", strerror(errno));
    exit(EXIT_FATAL_APP_PROCESS);
}

static void start_server(
    const char *path, const char *main_class, const char *process_name,
    const std::vector<std::string> &passthrough_args
) {
    pid_t pid = fork();
    switch (pid) {
        case -1: {
            perrorf("fatal: can't fork\n");
            exit(EXIT_FATAL_FORK);
        }
        case 0: {
            LOGD("child: detaching");
            setsid();
            chdir("/");
            int fd = open("/dev/null", O_RDWR);
            if (fd != -1) {
                dup2(fd, STDIN_FILENO);
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > 2) close(fd);
            }
            run_server(path, main_class, process_name, passthrough_args);
        }
        default: {
            printf("info: mttd_starter child pid is %d\n", pid);
            exit(EXIT_SUCCESS);
        }
    }
}

int main(int argc, char *argv[]) {
    std::string apk_path;
    std::string main_class;
    std::string process_name = "mttd_daemon";
    std::vector<std::string> passthrough_args;

    // argv[0] is this binary's own path, not a real arg — skip it, matching how the invoker
    // (DirectAdbManager.launchDaemon) builds the shell command.
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
        } else if (strncmp(argv[i], "--class=", 8) == 0) {
            main_class = argv[i] + 8;
        } else if (strncmp(argv[i], "--name=", 7) == 0) {
            process_name = argv[i] + 7;
        } else {
            // 이 starter 가 모르는 인자는 전부 main_class(우리 Kotlin 진입점, DirectDaemonStarter)
            // 의 String[] args 로 그대로 넘긴다 — --authority=/--pkg= 는 여기서 해석 안 하고
            // 순수하게 통과시킨다. (실기기에서 이걸 빼먹어서 "--authority= required" 로 죽는 걸
            // 실제로 겪었다 — 데몬 실행 커맨드를 바꿀 때 이 통과 경로도 같이 챙길 것.)
            passthrough_args.emplace_back(argv[i]);
        }
    }

    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        perrorf("fatal: run mttd_starter from non root nor adb user (uid=%d).\n", uid);
        exit(EXIT_FATAL_UID);
    }

    if (apk_path.empty() || main_class.empty()) {
        perrorf("fatal: --apk= and --class= are required.\n");
        exit(EXIT_FATAL_ARGS);
    }

    if (access(apk_path.c_str(), R_OK) != 0) {
        perrorf("fatal: can't access apk %s\n", apk_path.c_str());
        exit(EXIT_FATAL_APK_PATH);
    }

    // kill any previous instance so retries don't accumulate duplicate daemons.
    printf("info: killing old process (name=%s)...\n", process_name.c_str());
    strncpy(g_kill_target_name, process_name.c_str(), sizeof(g_kill_target_name) - 1);
    g_self_pid = getpid();
    foreach_proc(kill_if_matching_name);

    printf("info: apk path is %s\n", apk_path.c_str());
    printf("info: starting server (passthrough_args=%zu)...\n", passthrough_args.size());
    fflush(stdout);
    start_server(apk_path.c_str(), main_class.c_str(), process_name.c_str(), passthrough_args);
}
