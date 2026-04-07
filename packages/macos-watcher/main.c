#include <CoreServices/CoreServices.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

typedef struct {
    char **resolved;  /* absolute paths */
    size_t *lengths;  /* strlen of each resolved path */
    int count;
} WatchContext;

static void callback(
    ConstFSEventStreamRef stream __attribute__((unused)),
    void *info,
    size_t count,
    void *paths,
    const FSEventStreamEventFlags flags[],
    const FSEventStreamEventId ids[] __attribute__((unused)))
{
    WatchContext *ctx = (WatchContext *)info;
    char **event_paths = (char **)paths;

    for (size_t i = 0; i < count; i++) {
        FSEventStreamEventFlags f = flags[i];

        /* skip directory-level events */
        if (f & kFSEventStreamEventFlagItemIsDir)
            continue;

        /* skip hidden files: check dot-prefix and UF_HIDDEN flag */
        const char *p = event_paths[i];
        int hidden = 0;
        for (const char *c = p; *c; c++) {
            if (*c == '/' && *(c + 1) == '.') {
                hidden = 1;
                break;
            }
        }

        if (hidden)
            continue;

        struct stat st;
        if (lstat(p, &st) == 0 && (st.st_flags & UF_HIDDEN))
            continue;

        /* skip backup files ending with ~, IntelliJ does this */
        size_t len = strlen(p);
        if (len > 0 && p[len - 1] == '~')
            continue;

        const char *kind;
        if (f & kFSEventStreamEventFlagItemRemoved) {
            kind = "del";
        } else if (f & kFSEventStreamEventFlagItemCreated) {
            kind = "new";
        } else if (f & (kFSEventStreamEventFlagItemModified |
                        kFSEventStreamEventFlagItemInodeMetaMod |
                        kFSEventStreamEventFlagItemXattrMod)) {
            kind = "mod";
        } else {
            continue;
        }

        /* find which watched dir this path belongs to */
        const char *root = NULL;
        const char *rel = NULL;
        for (int j = 0; j < ctx->count; j++) {
            if (strncmp(p, ctx->resolved[j], ctx->lengths[j]) == 0
                && p[ctx->lengths[j]] == '/') {
                root = ctx->resolved[j];
                rel = p + ctx->lengths[j] + 1;
                break;
            }
        }
        if (!root)
            continue;

        printf("%s,%s,%s\n", kind, root, rel);
    }

    fflush(stdout);
}

static char **read_dirs_from_stdin(int *out_count)
{
    size_t cap = 16;
    int count = 0;
    char **dirs = malloc(sizeof(char *) * cap);
    char *line = NULL;
    size_t line_cap = 0;
    ssize_t n;

    while ((n = getline(&line, &line_cap, stdin)) != -1) {
        /* strip trailing newline */
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r'))
            line[--n] = '\0';
        if (n == 0)
            continue;

        if ((size_t)count == cap) {
            cap *= 2;
            dirs = realloc(dirs, sizeof(char *) * cap);
        }
        dirs[count++] = strdup(line);
    }
    free(line);

    *out_count = count;
    return dirs;
}

int main(int argc, char *argv[])
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s [--latency <ms>] [--stdin | <dir> ...]\n", argv[0]);
        return 1;
    }

    long latency_ms = 500;
    int dir_start = 1;
    int use_stdin = 0;

    /* parse flags */
    while (dir_start < argc) {
        if (strcmp(argv[dir_start], "--latency") == 0) {
            if (dir_start + 1 >= argc) {
                fprintf(stderr, "error: --latency requires a value\n");
                return 1;
            }
            char *endptr;
            latency_ms = strtol(argv[dir_start + 1], &endptr, 10);
            if (*endptr != '\0' || latency_ms < 0) {
                fprintf(stderr, "error: invalid latency '%s'\n", argv[dir_start + 1]);
                return 1;
            }
            dir_start += 2;
        } else if (strcmp(argv[dir_start], "--stdin") == 0) {
            use_stdin = 1;
            dir_start++;
        } else {
            break;
        }
    }
    double latency_sec = latency_ms / 1000.0;

    int dir_count;
    char **dir_args;

    if (use_stdin) {
        dir_args = read_dirs_from_stdin(&dir_count);
    } else {
        dir_count = argc - dir_start;
        dir_args = argv + dir_start;
    }

    if (dir_count < 1) {
        fprintf(stderr, "error: no directories specified\n");
        return 1;
    }

    WatchContext ctx;
    ctx.count = dir_count;
    ctx.resolved = malloc(sizeof(char *) * dir_count);
    ctx.lengths = malloc(sizeof(size_t) * dir_count);

    CFStringRef *cf_paths = malloc(sizeof(CFStringRef) * dir_count);

    for (int i = 0; i < dir_count; i++) {
        const char *dir = dir_args[i];

        struct stat sb;
        if (stat(dir, &sb) != 0 || !S_ISDIR(sb.st_mode)) {
            fprintf(stderr, "error: '%s' is not a directory\n", dir);
            return 1;
        }

        char *resolved = realpath(dir, NULL);
        if (!resolved) {
            fprintf(stderr, "error: cannot resolve path '%s'\n", dir);
            return 1;
        }

        ctx.resolved[i] = resolved;
        ctx.lengths[i] = strlen(resolved);

        cf_paths[i] = CFStringCreateWithCString(
            kCFAllocatorDefault, resolved, kCFStringEncodingUTF8);
    }

    /* sort by path length descending so longest prefix matches first */
    for (int i = 0; i < dir_count - 1; i++) {
        for (int j = i + 1; j < dir_count; j++) {
            if (ctx.lengths[j] > ctx.lengths[i]) {
                char *tmp_r = ctx.resolved[i];
                ctx.resolved[i] = ctx.resolved[j];
                ctx.resolved[j] = tmp_r;

                size_t tmp_l = ctx.lengths[i];
                ctx.lengths[i] = ctx.lengths[j];
                ctx.lengths[j] = tmp_l;
            }
        }
    }

    CFArrayRef paths = CFArrayCreate(
        kCFAllocatorDefault, (const void **)cf_paths, dir_count,
        &kCFTypeArrayCallBacks);

    for (int i = 0; i < dir_count; i++)
        CFRelease(cf_paths[i]);
    free(cf_paths);

    FSEventStreamContext stream_ctx = {0, &ctx, NULL, NULL, NULL};

    FSEventStreamRef stream = FSEventStreamCreate(
        kCFAllocatorDefault,
        callback,
        &stream_ctx,
        paths,
        kFSEventStreamEventIdSinceNow,
        latency_sec,
        kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer);

    CFRelease(paths);

    dispatch_queue_t queue = dispatch_queue_create(
        "com.thheller.shadow-cljs.fswatch", DISPATCH_QUEUE_SERIAL);
    FSEventStreamSetDispatchQueue(stream, queue);
    FSEventStreamStart(stream);

    dispatch_main();

    FSEventStreamStop(stream);
    FSEventStreamInvalidate(stream);
    FSEventStreamRelease(stream);

    return 0;
}
