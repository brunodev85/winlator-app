#ifndef VORTEK_RING_BUFFER_POOL_H
#define VORTEK_RING_BUFFER_POOL_H

#include <stdbool.h>

#include "winlator.h"
#include "ring_buffer.h"
#include "time_utils.h"

#define RING_POOL_BUFFER_SIZE 128
#define RING_POOL_ARRAY_LEN 8
#define RING_POOL_ACQUIRE_TIMEOUT_NS 2000000000
#define RING_ID_INVALID 255

typedef unsigned char ringid_t;

typedef struct RingBufferPool {
    bool acquired;
    RingBuffer* ring;
} RingBufferPool;

static inline ringid_t RingBufferPool_acquire(RingBufferPool* ringPool) {
    for (ringid_t i = 0; i < RING_POOL_ARRAY_LEN; i++) {
        if (!ringPool[i].acquired) {
            ringPool[i].acquired = true;
            return i;
        }
    }

    uint32_t iter = 0;
    uint64_t endTime = nanoTime() + RING_POOL_ACQUIRE_TIMEOUT_NS;

    while (1) {
        iter = busyWait(iter);

        for (ringid_t i = 0; i < RING_POOL_ARRAY_LEN; i++) {
            if (!ringPool[i].acquired) {
                ringPool[i].acquired = true;
                return i;
            }
        }

        if (nanoTime() > endTime) {
            println("vortek: can't acquire ring buffer, the timeout has been reached %u", iter);
            break;
        }
    }

    return RING_ID_INVALID;
}

#endif