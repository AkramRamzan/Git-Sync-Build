/**
 * pty_bridge.c — JNI PTY wrapper for Aro Code terminal.
 *
 * Provides:
 *   forkPty(cmd[], envp[], cwd, rows, cols) → int[]{masterFd, childPid}
 *   resizePty(masterFd, rows, cols)
 *   killProcessGroup(pid, signal)
 *   waitForExit(pid)  → exit code (non-blocking; -1 still running)
 *
 * Uses POSIX openpty() / forkpty() via posix_openpt + manual implementation
 * so it works on Android API 26+ (forkpty(3) is only in Bionic since API 28).
 *
 * All reads/writes of the master FD are done on the Kotlin/Java side using
 * FileDescriptor + FileInputStream/FileOutputStream to avoid blocking the JNI
 * thread and to enable coroutine-friendly I/O.
 */

#define _GNU_SOURCE
#define _XOPEN_SOURCE 600

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>

#ifdef __ANDROID__
#include <android/log.h>
#define TAG     "PtyBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define TAG     "PtyBridge"
#define LOGI(...) printf("[INFO] " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, "[ERROR] " __VA_ARGS__)
#endif

/* ── Helper: convert Java String[] to char*[] ── */
static char** toStringArray(JNIEnv *env, jobjectArray arr, int *outLen) {
    if (!arr) { *outLen = 0; return NULL; }
    jsize len = (*env)->GetArrayLength(env, arr);
    char **result = (char**)malloc((len + 1) * sizeof(char*));
    for (jsize i = 0; i < len; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *cstr = (*env)->GetStringUTFChars(env, s, NULL);
        result[i] = strdup(cstr);
        (*env)->ReleaseStringUTFChars(env, s, cstr);
        (*env)->DeleteLocalRef(env, s);
    }
    result[len] = NULL;
    *outLen = (int)len;
    return result;
}

static void freeStringArray(char **arr) {
    if (!arr) return;
    for (int i = 0; arr[i] != NULL; i++) {
        free(arr[i]);
    }
    free(arr);
}

/* ── Manual forkpty for Android API 26/27 compatibility ── */
static pid_t my_forkpty(int *masterFd, int rows, int cols) {
    /* Open PTY master */
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        return -1;
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    char slaveName[128];
    char *p = ptsname(master);
    if (!p) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    strncpy(slaveName, p, sizeof(slaveName) - 1);
    slaveName[sizeof(slaveName) - 1] = '\0';

    /* Enable UTF-8 mode and disable flow control (Termux standard) */
    struct termios tios;
    if (tcgetattr(master, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(master, TCSANOW, &tios);
    }

    /* Set initial window size on master before fork */
    struct winsize ws = { .ws_row = (unsigned short)rows,
                          .ws_col = (unsigned short)cols,
                          .ws_xpixel = 0, .ws_ypixel = 0 };
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    if (pid == 0) {
        /* ── Child process (ONLY async-signal-safe calls; NO JNI!) ── */
        close(master);

        /* New session so we become process group leader */
        if (setsid() < 0) {
            _exit(1);
        }

        /* Open slave end */
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) {
            _exit(1);
        }

        /* Make slave the controlling terminal */
        ioctl(slave, TIOCSCTTY, 0);

        /* Set window size on slave too */
        ioctl(slave, TIOCSWINSZ, &ws);

        /* Wire slave to stdin/stdout/stderr */
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        return 0;
    }

    /* ── Parent ── */
    *masterFd = master;
    LOGI("forkpty: masterFd=%d  childPid=%d", master, pid);
    return pid;
}

/* ════════════════════════════════════════════════════════════════════════════
 * JNI exports
 * ════════════════════════════════════════════════════════════════════════════ */

/**
 * forkAndExec(String[] cmd, String[] envp, String cwd, int rows, int cols)
 * Returns int[]{masterFd, childPid}, or null on error.
 */
JNIEXPORT jintArray JNICALL
Java_com_aro_code_terminal_PtyBridge_forkAndExec(
        JNIEnv *env, jclass clazz,
        jobjectArray jcmd, jobjectArray jenvp,
        jstring jcwd, jint rows, jint cols)
{
    (void)clazz;

    /* 1. Extract all Java strings in the parent BEFORE forking.
     * CRITICAL: Calling JNI methods in the child after fork() causes
     * an instant SIGSEGV (exit code 139) because the ART VM runtime state is unsafe! */
    int cmdLen = 0, envLen = 0;
    char **cmd = toStringArray(env, jcmd, &cmdLen);
    if (!cmd || cmdLen == 0 || !cmd[0]) {
        LOGE("forkAndExec: cmd array is empty or null");
        freeStringArray(cmd);
        return NULL;
    }

    char **envp = toStringArray(env, jenvp, &envLen);

    char *cwd = NULL;
    if (jcwd) {
        const char *rawCwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
        if (rawCwd) {
            cwd = strdup(rawCwd);
            (*env)->ReleaseStringUTFChars(env, jcwd, rawCwd);
        }
    }

    /* 2. Allocate PTY and fork process */
    int masterFd = -1;
    pid_t pid = my_forkpty(&masterFd, (int)rows, (int)cols);
    if (pid < 0) {
        freeStringArray(cmd);
        freeStringArray(envp);
        if (cwd) free(cwd);
        return NULL;
    }

    if (pid == 0) {
        /* ── Child: ONLY async-signal-safe calls here! ZERO JNI! ── */
        if (cwd && chdir(cwd) < 0) {
            /* non-fatal */
        }

        /* Clear signal masks inherited from Android zygote */
        sigset_t set;
        sigemptyset(&set);
        sigprocmask(SIG_SETMASK, &set, NULL);

        /* Reset all signals to default */
        for (int sig = 1; sig < 32; sig++) {
            if (sig != SIGKILL && sig != SIGSTOP) {
                signal(sig, SIG_DFL);
            }
        }

        /* Close leaked file descriptors above STDERR */
        int maxFd = (int)sysconf(_SC_OPEN_MAX);
        if (maxFd < 32) maxFd = 256;
        for (int fd = 3; fd < maxFd; fd++) {
            close(fd);
        }

        if (envp) {
            execve(cmd[0], cmd, envp);
        } else {
            execv(cmd[0], cmd);
        }

        /* If exec failed, write error to stderr */
        char buf[256];
        snprintf(buf, sizeof(buf), "\r\nexecve failed: %s: %s\r\n", cmd[0], strerror(errno));
        write(STDERR_FILENO, buf, strlen(buf));
        _exit(127);
    }

    /* ── Parent: clean up C strings and return [masterFd, pid] ── */
    freeStringArray(cmd);
    freeStringArray(envp);
    if (cwd) free(cwd);

    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { close(masterFd); return NULL; }
    jint buf[2] = { masterFd, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

/**
 * resizePty(int masterFd, int rows, int cols)
 * Sends TIOCSWINSZ to the master FD → triggers SIGWINCH in child.
 */
JNIEXPORT jint JNICALL
Java_com_aro_code_terminal_PtyBridge_resizePty(
        JNIEnv *env, jclass clazz, jint masterFd, jint rows, jint cols)
{
    (void)env;
    (void)clazz;
    struct winsize ws = { .ws_row = (unsigned short)rows,
                          .ws_col = (unsigned short)cols,
                          .ws_xpixel = 0, .ws_ypixel = 0 };
    int rc = ioctl((int)masterFd, TIOCSWINSZ, &ws);
    if (rc < 0) LOGE("TIOCSWINSZ failed fd=%d: %s", (int)masterFd, strerror(errno));
    return rc;
}

/**
 * killProcessGroup(int pid, int signal)
 */
JNIEXPORT jint JNICALL
Java_com_aro_code_terminal_PtyBridge_killProcessGroup(
        JNIEnv *env, jclass clazz, jint pid, jint sig)
{
    (void)env;
    (void)clazz;
    return (jint)killpg((pid_t)pid, (int)sig);
}

/**
 * waitForExit(int pid) → exit code, or -1 if still running.
 * Non-blocking (WNOHANG).
 */
JNIEXPORT jint JNICALL
Java_com_aro_code_terminal_PtyBridge_waitForExit(
        JNIEnv *env, jclass clazz, jint pid)
{
    (void)env;
    (void)clazz;
    int status = 0;
    pid_t ret = waitpid((pid_t)pid, &status, WNOHANG);
    if (ret == 0)  return -1;   /* still running */
    if (ret < 0)   return -2;   /* error */
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
