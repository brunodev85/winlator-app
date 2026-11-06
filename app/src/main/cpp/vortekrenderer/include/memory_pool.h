#ifndef VORTEK_MEMORY_POOL_H
#define VORTEK_MEMORY_POOL_H

#include "winlator.h"

typedef struct MemoryPool {
    void* data;
    int size;
    int maxSize;
    ArrayList allocationList;
} MemoryPool;

static inline void mp_init(MemoryPool* mp, int maxSize) {
    MEMFREE(mp->data);
    mp->data = malloc(maxSize);
    mp->maxSize = maxSize;
    mp->size = 0;
    memset(mp->data, 0, maxSize);
}

static inline void* mp_alloc(MemoryPool* mp, int size) {
    bool isFull = (mp->size + size) >= mp->maxSize || !mp->data;
    void* chunk;
    if (isFull) {
        chunk = malloc(size);
        ArrayList_add(&mp->allocationList, chunk);
    }
    else {
        chunk = mp->data + mp->size;
        mp->size += size;
    }

    memset(chunk, 0, size);
    return chunk;
}

static inline void mp_free(MemoryPool* mp) {
    if (!mp) return;
    mp->size = 0;

    for (int i = mp->allocationList.size-1; i >= 0; i--) {
        MEMFREE(mp->allocationList.elements[i]);
        ArrayList_removeAt(&mp->allocationList, i);
    }
}

#define MEMORY_POOL_MAX_SIZE 65536

#endif