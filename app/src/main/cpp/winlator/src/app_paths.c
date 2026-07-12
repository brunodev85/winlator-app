#include <limits.h>
#include <string.h>

#include "winlator.h"

static char appCacheDir[PATH_MAX] = {0};

const char* getAppCacheDir() {
    return appCacheDir;
}

void setAppCacheDir(const char* path) {
    if (!path) {
        appCacheDir[0] = '\0';
        return;
    }

    strncpy(appCacheDir, path, sizeof(appCacheDir) - 1);
    appCacheDir[sizeof(appCacheDir) - 1] = '\0';
}
