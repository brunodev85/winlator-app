#ifndef VORTEK_NOTIFY_FD_POOL_H
#define VORTEK_NOTIFY_FD_POOL_H

#include <stdbool.h>

#include "winlator.h"
#include "events.h"

#define MAX_NOTIFY_FDS 8

typedef unsigned char notifyid_t;

typedef struct NotifyFdPool {
    int fds[MAX_NOTIFY_FDS];
    bool usedFds[MAX_NOTIFY_FDS];
    bool enabled;
} NotifyFdPool;

static inline void NotifyFdPool_create(NotifyFdPool* notifyFdPool, int* initialFds) {
    for (int i = 0; i < ARRAY_SIZE(notifyFdPool->fds); i++) {
        int fd = initialFds ? initialFds[i] : eventfd(0, 0);
        notifyFdPool->fds[i] = fd;
        notifyFdPool->usedFds[i] = false;
    }
    notifyFdPool->enabled = true;
}

static inline void NotifyFdPool_destroy(NotifyFdPool* notifyFdPool) {
    for (int i = 0; i < ARRAY_SIZE(notifyFdPool->fds); i++) {
        CLOSEFD(notifyFdPool->fds[i]);
    }
    notifyFdPool->enabled = false;
}

static inline notifyid_t NotifyFdPool_acquire(NotifyFdPool* notifyFdPool) {
    for (notifyid_t i = 0; i < ARRAY_SIZE(notifyFdPool->fds); i++) {
        if (!notifyFdPool->usedFds[i]) {
            notifyFdPool->usedFds[i] = true;
            return i;
        }
    }

    uint32_t iter = 0;
    while (notifyFdPool->enabled) {
        busyWait(&iter);

        for (notifyid_t i = 0; i < ARRAY_SIZE(notifyFdPool->fds); i++) {
            if (!notifyFdPool->usedFds[i]) {
                notifyFdPool->usedFds[i] = true;
                return i;
            }
        }
    }

    return -1;
}

static inline void NotifyFdPool_free(NotifyFdPool* notifyFdPool, notifyid_t id) {
    if (id >= 0 && id < MAX_NOTIFY_FDS) {
        notifyFdPool->usedFds[id] = false;
    }
}

static inline eventfd_t NotifyFdPool_wait(NotifyFdPool* notifyFdPool, notifyid_t id) {
    if (id >= 0 && id < MAX_NOTIFY_FDS) {
        int fd = notifyFdPool->fds[id];
        eventfd_t result = 0;
        return eventfd_read(fd, &result) == 0 ? result : 0;
    }
    else return 0;
}

static inline void NotifyFdPool_notify(NotifyFdPool* notifyFdPool, notifyid_t id, eventfd_t value) {
    if (id >= 0 && id < MAX_NOTIFY_FDS) {
        int fd = notifyFdPool->fds[id];
        eventfd_write(fd, value);
    }
}

#endif