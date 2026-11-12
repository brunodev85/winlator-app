#include "synchronization_object_manager.h"
#include "vortek_serializer.h"
#include "string_utils.h"
#include "vulkan_helper.h"

typedef struct WaitForFencesRequest {
    RingBuffer* responseRing;
    VkDevice device;
    uint32_t fenceCount;
    VkBool32 waitAll;
    uint64_t timeout;
    VkFence* fences;
    MemoryPool memoryPool;
} WaitForFencesRequest;

typedef struct WaitSemaphoresRequest {
    RingBuffer* responseRing;
    VkDevice device;
    VkSemaphoreWaitInfo waitInfo;
    uint64_t timeout;
    MemoryPool memoryPool;
} WaitSemaphoresRequest;

static void waitForFencesThread(void* param) {
    WaitForFencesRequest* req = param;
    VkResult result = vulkanWrapper.vkWaitForFences(req->device, req->fenceCount, req->fences, req->waitAll, req->timeout);
    RingBuffer_write(req->responseRing, &result, sizeof(VkResult));

    mp_free(&req->memoryPool);
    free(req);
}

static void waitSemaphoresThread(void* param) {
    WaitSemaphoresRequest* req = param;
    VkResult result = vulkanWrapper.vkWaitSemaphores(req->device, &req->waitInfo, req->timeout);
    RingBuffer_write(req->responseRing, &result, sizeof(VkResult));

    mp_free(&req->memoryPool);
    free(req);
}

void SynchronizationObjectManager_waitForFences(VkContext* context) {
    ringid_t ringId = *(ringid_t*)(context->inputBuffer + 0);
    WaitForFencesRequest* waitForFencesRequest = calloc(1, sizeof(WaitForFencesRequest));
    waitForFencesRequest->responseRing = context->ringPool[ringId].ring;

    char* inputBuffer = context->inputBuffer + sizeof(ringid_t);
    uint64_t deviceId;
    vt_unserialize_vkWaitForFences((VkDevice)&deviceId, &waitForFencesRequest->fenceCount, NULL, &waitForFencesRequest->waitAll, &waitForFencesRequest->timeout, inputBuffer, &waitForFencesRequest->memoryPool);
    waitForFencesRequest->device  = VkObject_fromId(deviceId);

    waitForFencesRequest->fences = mp_alloc(&waitForFencesRequest->memoryPool, waitForFencesRequest->fenceCount * sizeof(VkFence));
    vt_unserialize_vkWaitForFences(VK_NULL_HANDLE, NULL, waitForFencesRequest->fences, NULL, NULL, inputBuffer, &waitForFencesRequest->memoryPool);

    ThreadPool_run(context->threadPool, waitForFencesThread, waitForFencesRequest);
}

void SynchronizationObjectManager_waitSemaphores(VkContext* context) {
    ringid_t ringId = *(ringid_t*)(context->inputBuffer + 0);
    WaitSemaphoresRequest* waitSemaphoresRequest = calloc(1, sizeof(WaitSemaphoresRequest));
    waitSemaphoresRequest->responseRing = context->ringPool[ringId].ring;;

    char* inputBuffer = context->inputBuffer + sizeof(ringid_t);
    uint64_t deviceId;
    vt_unserialize_vkWaitSemaphores((VkDevice)&deviceId, &waitSemaphoresRequest->waitInfo, &waitSemaphoresRequest->timeout, inputBuffer, &waitSemaphoresRequest->memoryPool);
    waitSemaphoresRequest->device = VkObject_fromId(deviceId);

    ThreadPool_run(context->threadPool, waitSemaphoresThread, waitSemaphoresRequest);
}