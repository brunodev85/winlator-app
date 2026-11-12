#ifndef VORTEK_CONTEXT_H
#define VORTEK_CONTEXT_H

#include "vortek.h"
#include "xwindow_swapchain.h"
#include "texture_decoder.h"
#include "async_pipeline_creator.h"
#include "synchronization_object_manager.h"

typedef struct VkContext {
    int clientFd;
    int vkMaxVersion;
    short maxDeviceMemory;
    short imageCacheSize;
    ResourceMemoryType resourceMemoryType;
    ArrayList* exposedDeviceExtensions;
    ArrayList* disabledDeviceExtensions;
    uint64_t totalAllocationSize;

    bool hasExternalMemoryFd;
    bool hasExternalMemoryDMABuf;

#if ENABLE_VALIDATION_LAYER
    VkDebugReportCallbackEXT debugReportCallback;
#endif

    char* inputBuffer;
    int inputBufferSize;
    MemoryPool memoryPool;

    pthread_t requestHandlerThread;
    RingBuffer* clientRing;
    RingBuffer* serverRing;
    RingBufferPool ringPool[RING_POOL_ARRAY_LEN];
    VkResult status;

    int graphicsQueueIndex;

    TextureDecoder* textureDecoder;
    ShaderInspector* shaderInspector;
    ThreadPool* threadPool;

    ArrayList extraDataRequests;
    pthread_mutex_t extraDataRequestsMutex;

    JMethods jmethods;
    char* engineName;
    uint32_t engineVersion;
    VkDriverId driverID;
} VkContext;

typedef struct ExtraDataRequest {
    uint16_t requestId;
    void* data;
    int size;
} ExtraDataRequest;

extern VkContext* createVkContext(JNIEnv *env, jobject obj, int clientFd, jobject options);
extern void destroyVkContext(JNIEnv *env, VkContext* context);
extern bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength);

#endif
