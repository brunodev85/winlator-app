#ifndef WINLATOR_EVENTS_H
#define WINLATOR_EVENTS_H

#include <stdbool.h>
#include <stdatomic.h>
#include <poll.h>
#include <errno.h>
#include <threads.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <limits.h>

#define EVENT_RESULT_SUCCESS 0
#define EVENT_RESULT_TIMEOUT 1
#define EVENT_RESULT_ERROR 2

#define BUSY_WAIT_MAX_CPU_PAUSE_ITER 50
#define BUSY_WAIT_MAX_THRD_YIELD_ITER 200
#define BUSY_WAIT_MAX_DELAY_US 1000

static inline int createEvent(bool initialState) {
    return eventfd(initialState ? 1 : 0, EFD_CLOEXEC | EFD_NONBLOCK);
}

static inline bool setEvent(int fd) {
    uint64_t value = 1;
    ssize_t res = write(fd, &value, sizeof(uint64_t));
    return res >= 0 ? true : false;
}

static inline bool resetEvent(int fd) {
    uint64_t value;
    ssize_t res = read(fd, &value, sizeof(uint64_t));
    return res >= 0 ? true : false;
}

static inline int waitForEvents(int* fds, int numFds, bool waitAll, int timeoutMs) {
    if (timeoutMs <= 0) timeoutMs = -1;
    struct pollfd pfds[numFds];
    int i, j, res;

    bool signaled[numFds];
    for (i = 0, j = 0; i < numFds; i++) {
        if (fds[i] < 0) continue;
        pfds[j].fd = fds[i];
        pfds[j].events = POLLIN;
        pfds[j].revents = 0;
        signaled[j] = false;
        j++;
    }

    numFds = j;
    if (numFds == 0) return EVENT_RESULT_SUCCESS;

    int remaining = numFds;
    do {
        res = poll(pfds, remaining, timeoutMs);
        if (res < 0) return EVENT_RESULT_ERROR;

        bool anySignaled = false;
        for (i = 0; i < remaining; i++) {
            if ((pfds[i].revents & POLLIN)) {
                if (!waitAll || numFds == 1) return EVENT_RESULT_SUCCESS;
                remaining--;

                for (j = 0; j < numFds; j++) {
                    if (pfds[i].fd == fds[j]) {
                        signaled[j] = true;
                        anySignaled = true;
                        break;
                    }
                }
            }
        }

        if (!anySignaled && timeoutMs > 0) return EVENT_RESULT_TIMEOUT;

        for (i = 0, j = 0; i < numFds; i++) {
            if (!signaled[i]) {
                pfds[j].fd = fds[i];
                pfds[j].events = POLLIN;
                pfds[j].revents = 0;
                j++;
            }
        }
    }
    while (remaining > 0);

    return EVENT_RESULT_SUCCESS;
}

static inline uint32_t busyWait(uint32_t iter) {
#define CPU_PAUSE() __asm__ __volatile__("yield" : : : "memory")
    iter++;
    if (iter < BUSY_WAIT_MAX_CPU_PAUSE_ITER) {
        CPU_PAUSE();
    }
    else if (iter < BUSY_WAIT_MAX_THRD_YIELD_ITER) {
        thrd_yield();
    }
    else {
        uint32_t delayUs = (iter - BUSY_WAIT_MAX_THRD_YIELD_ITER) * 10;
        if (delayUs > BUSY_WAIT_MAX_DELAY_US) delayUs = BUSY_WAIT_MAX_DELAY_US;

        struct timespec ts = {0};
        ts.tv_nsec = delayUs * 1000;
        nanosleep(&ts, NULL);
    }
#undef CPU_PAUSE
    return iter;
}

static inline int futexWait(atomic_uint* futexWord, uint32_t expectedVal) {
    return syscall(SYS_futex, futexWord, FUTEX_WAIT, expectedVal, NULL, NULL, 0);
}

static inline int futexTimedWait(atomic_uint* futexWord, uint32_t expectedVal, uint64_t timeoutNs) {
    struct timespec ts = {.tv_sec = 0, .tv_nsec = timeoutNs};
    return syscall(SYS_futex, futexWord, FUTEX_WAIT, expectedVal, &ts, NULL, 0);
}

static inline int futexWake(atomic_uint* futexWord, int numWaiters) {
    return syscall(SYS_futex, futexWord, FUTEX_WAKE, numWaiters, NULL, NULL, 0);
}

static inline bool waitSemaphore(atomic_uint* semaphore, uint32_t expectedVal, uint64_t timeoutNs) {
    uint32_t currentVal = atomic_load(semaphore);

    if (currentVal >= expectedVal) {
        if (atomic_compare_exchange_weak(semaphore, &currentVal, currentVal - expectedVal)) return true;
    }
    else futexTimedWait(semaphore, currentVal, timeoutNs);
    return false;
}

static inline void writeSemaphore(atomic_uint* semaphore, uint32_t currentVal) {
    uint32_t previousVal = atomic_fetch_add(semaphore, currentVal);
    if (previousVal == 0) futexWake(semaphore, INT_MAX);
}

#endif