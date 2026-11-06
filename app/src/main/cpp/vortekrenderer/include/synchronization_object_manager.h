#ifndef VORTEK_SYNCHRONIZATION_OBJECT_MANAGER_H
#define VORTEK_SYNCHRONIZATION_OBJECT_MANAGER_H

#include "vortek.h"

typedef struct SynchronizationObjectManager {
    MemoryPool memoryPools[MAX_NOTIFY_FDS];
    NotifyFdPool notifyFdPool;
} SynchronizationObjectManager;

extern void SynchronizationObjectManager_init(SynchronizationObjectManager* manager);
extern void SynchronizationObjectManager_destroy(SynchronizationObjectManager* manager);
extern void SynchronizationObjectManager_waitForFences(SynchronizationObjectManager* manager, ThreadPool* threadPool, char* inputBuffer);
extern void SynchronizationObjectManager_waitSemaphores(SynchronizationObjectManager* manager, ThreadPool* threadPool, char* inputBuffer);

#endif