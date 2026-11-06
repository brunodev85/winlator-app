#include "synchronization_object_manager.h"
#include "vortek_serializer.h"
#include "string_utils.h"
#include "vulkan_helper.h"

typedef struct WaitForFencesRequest {
    notifyid_t notifyId;
    NotifyFdPool* notifyFdPool;
    MemoryPool* memoryPool;
    VkDevice device;
    uint32_t fenceCount;
    VkBool32 waitAll;
    uint64_t timeout;
    VkFence* fences;
} WaitForFencesRequest;

typedef struct WaitSemaphoresRequest {
    notifyid_t notifyId;
    NotifyFdPool* notifyFdPool;
    MemoryPool* memoryPool;
    VkDevice device;
    VkSemaphoreWaitInfo waitInfo;
    uint64_t timeout;
} WaitSemaphoresRequest;

static void waitForFencesThread(void* param) {
    WaitForFencesRequest* waitForFencesRequest = param;
    VkResult result = vulkanWrapper.vkWaitForFences(waitForFencesRequest->device, waitForFencesRequest->fenceCount, waitForFencesRequest->fences, waitForFencesRequest->waitAll, waitForFencesRequest->timeout);

    uint64_t value = result == VK_SUCCESS ? 1 : (result == VK_TIMEOUT ? 2 : VK_ERROR_DEVICE_LOST);
    NotifyFdPool_notify(waitForFencesRequest->notifyFdPool, waitForFencesRequest->notifyId, value);

    mp_free(waitForFencesRequest->memoryPool);
}

static void waitSemaphoresThread(void* param) {
    WaitSemaphoresRequest* waitSemaphoresRequest = param;
    VkResult result = vulkanWrapper.vkWaitSemaphores(waitSemaphoresRequest->device, &waitSemaphoresRequest->waitInfo, waitSemaphoresRequest->timeout);

    uint64_t value = result == VK_SUCCESS ? 1 : (result == VK_TIMEOUT ? 2 : VK_ERROR_DEVICE_LOST);
    NotifyFdPool_notify(waitSemaphoresRequest->notifyFdPool, waitSemaphoresRequest->notifyId, value);

    mp_free(waitSemaphoresRequest->memoryPool);
}

void SynchronizationObjectManager_init(SynchronizationObjectManager* manager) {
    for (int i = 0; i < ARRAY_SIZE(manager->memoryPools); i++) mp_init(&manager->memoryPools[i], 1024);
    NotifyFdPool_create(&manager->notifyFdPool, NULL);
}

void SynchronizationObjectManager_destroy(SynchronizationObjectManager* manager) {
    NotifyFdPool_destroy(&manager->notifyFdPool);
    for (int i = 0; i < ARRAY_SIZE(manager->memoryPools); i++) {
        MEMFREE(manager->memoryPools[i].data);
    }
}

void SynchronizationObjectManager_waitForFences(SynchronizationObjectManager* manager, ThreadPool* threadPool, char* inputBuffer) {
    notifyid_t notifyId = *(notifyid_t*)(inputBuffer + 0);
    MemoryPool* memoryPool = &manager->memoryPools[notifyId];
    WaitForFencesRequest* waitForFencesRequest = mp_alloc(memoryPool, sizeof(WaitForFencesRequest));
    waitForFencesRequest->notifyId = notifyId;
    waitForFencesRequest->notifyFdPool = &manager->notifyFdPool;
    waitForFencesRequest->memoryPool = memoryPool;

    inputBuffer = inputBuffer + sizeof(notifyid_t);
    uint64_t deviceId;
    vt_unserialize_vkWaitForFences((VkDevice)&deviceId, &waitForFencesRequest->fenceCount, NULL, &waitForFencesRequest->waitAll, &waitForFencesRequest->timeout, inputBuffer, memoryPool);
    waitForFencesRequest->device  = VkObject_fromId(deviceId);

    waitForFencesRequest->fences = mp_alloc(memoryPool, waitForFencesRequest->fenceCount * sizeof(VkFence));
    vt_unserialize_vkWaitForFences(VK_NULL_HANDLE, NULL, waitForFencesRequest->fences, NULL, NULL, inputBuffer, memoryPool);

    ThreadPool_run(threadPool, waitForFencesThread, waitForFencesRequest);
}

void SynchronizationObjectManager_waitSemaphores(SynchronizationObjectManager* manager, ThreadPool* threadPool, char* inputBuffer) {
    notifyid_t notifyId = *(notifyid_t*)(inputBuffer + 0);
    MemoryPool* memoryPool = &manager->memoryPools[notifyId];
    WaitSemaphoresRequest* waitSemaphoresRequest = mp_alloc(memoryPool, sizeof(WaitSemaphoresRequest));
    waitSemaphoresRequest->notifyId = notifyId;
    waitSemaphoresRequest->notifyFdPool = &manager->notifyFdPool;
    waitSemaphoresRequest->memoryPool = memoryPool;

    inputBuffer = inputBuffer + sizeof(notifyid_t);
    uint64_t deviceId;
    vt_unserialize_vkWaitSemaphores((VkDevice)&deviceId, &waitSemaphoresRequest->waitInfo, &waitSemaphoresRequest->timeout, inputBuffer, memoryPool);
    waitSemaphoresRequest->device = VkObject_fromId(deviceId);

    ThreadPool_run(threadPool, waitSemaphoresThread, waitSemaphoresRequest);
}