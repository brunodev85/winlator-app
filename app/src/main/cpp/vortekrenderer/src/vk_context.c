#include "vk_context.h"
#include "vulkan_helper.h"
#include "request_handler.h"
#include "sysvshared_memory.h"
#include "string_utils.h"
#include "jni_utils.h"

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->getWindowWidth = (*env)->GetMethodID(env, cls, "getWindowWidth", "(I)S");
    jmethods->getWindowHeight = (*env)->GetMethodID(env, cls, "getWindowHeight", "(I)S");
    jmethods->getWindowHardwareBuffer = (*env)->GetMethodID(env, cls, "getWindowHardwareBuffer", "(IZ)J");
    jmethods->updateWindowContent = (*env)->GetMethodID(env, cls, "updateWindowContent", "(I)V");
}

static ExtraDataRequest* waitForExtraDataRequest(VkContext* context, uint16_t requestId) {
    ExtraDataRequest* result = NULL;
    uint32_t busyWaitIter = 0;

    while (context->status >= 0) {
        result = NULL;
        pthread_mutex_lock(&context->extraDataRequestsMutex);
        for (int i = 0; i < context->extraDataRequests.size; i++) {
            ExtraDataRequest* extraDataRequest = context->extraDataRequests.elements[i];
            if (extraDataRequest->requestId == requestId) {
                result = extraDataRequest;
                ArrayList_removeAt(&context->extraDataRequests, i);
                break;
            }
        }
        pthread_mutex_unlock(&context->extraDataRequestsMutex);

        if (result) break;
        busyWaitIter = busyWait(busyWaitIter);
    }

    return context->status >= 0 ? result : NULL;
}

static void* requestHandlerThread(void* param) {
    VkContext* context = param;
    loadJMethods(&context->jmethods);
    ExtraDataRequest* extraDataRequest = NULL;

    while (context->status >= 0) {
        int requestCode = vt_recv(context->serverRing, &context->inputBuffer, &context->inputBufferSize, &context->memoryPool);
        if (requestCode < 0) break;

        if (requestCode > INT16_MAX) {
            uint16_t requestId = requestCode & 0xffff;
            requestCode = requestCode >> 16;
            extraDataRequest = waitForExtraDataRequest(context, requestId);
            if (!extraDataRequest) break;
            context->inputBufferSize = extraDataRequest->size;
            context->inputBuffer = extraDataRequest->data;
        }

#if DEBUG_MODE
        println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBufferSize);
#endif

        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (handleRequestFunc) handleRequestFunc(context);

        mp_free(&context->memoryPool);

        if (extraDataRequest) {
            MEMFREE(extraDataRequest->data);
            MEMFREE(extraDataRequest);
        }

        context->inputBuffer = NULL;
        context->inputBufferSize = 0;
    }

    (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    mp_free(&context->memoryPool);
    return NULL;
}

static bool setupRingBuffers(VkContext* context) {
    int shmFds[2 + RING_POOL_ARRAY_LEN];
    shmFds[0] = ashmemCreateRegion("vt-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    shmFds[1] = ashmemCreateRegion("vt-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) return false;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) return false;

    for (int i = 0; i < RING_POOL_ARRAY_LEN; i++) {
        shmFds[2+i] = ashmemCreateRegion("vt-ring-pool", RingBuffer_getSHMemSize(RING_POOL_BUFFER_SIZE));
        context->ringPool[i].ring = RingBuffer_create(shmFds[2+i], RING_POOL_BUFFER_SIZE);
        context->ringPool[i].ring->useSemaphores = true;
        context->ringPool[i].acquired = false;
        if (!context->ringPool[i].ring) return false;
    }

    int result = send_fds(context->clientFd, shmFds, ARRAY_SIZE(shmFds), NULL, 0);
    for (int i = 0; i < ARRAY_SIZE(shmFds); i++) close(shmFds[i]);
    if (result < 0) return false;

    pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context);
    return true;
}

VkContext* createVkContext(JNIEnv* env, jobject obj, int clientFd, jobject options) {
    jobjectArray exposedDeviceExtensions = getJFieldByName(env, options, "exposedDeviceExtensions", JSIGNATURE_ARRAY_STRING).l;

    VkContext* context = calloc(1, sizeof(VkContext));
    context->clientFd = clientFd;
    context->vkMaxVersion = getJFieldByName(env, options, "vkMaxVersion", "I").i;
    context->maxDeviceMemory = getJFieldByName(env, options, "maxDeviceMemory", "S").s;
    context->imageCacheSize = getJFieldByName(env, options, "imageCacheSize", "S").s;
    context->resourceMemoryType = getJFieldByName(env, options, "resourceMemoryType", "B").b;
    context->presentMode = getJFieldByName(env, options, "presentMode", "B").b;
    context->exposedDeviceExtensions = jstringArrayToCharArray(env, exposedDeviceExtensions);
    context->maxFramerate = getJFieldByName(env, options, "maxFramerate", "S").s;

    if (context->maxFramerate > 0) context->presentMode = indexOfPresentMode(VK_PRESENT_MODE_FIFO_KHR);
    if (context->presentMode == indexOfPresentMode(VK_PRESENT_MODE_FIFO_KHR) && context->maxFramerate == 0) {
        context->maxFramerate = 60;
    }

    pthread_mutex_init(&context->extraDataRequestsMutex, NULL);

    mp_init(&context->memoryPool, MEMORY_POOL_MAX_SIZE);
    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);

    (*env)->GetJavaVM(env, &context->jmethods.jvm);
    context->jmethods.obj = (*env)->NewGlobalRef(env, obj);

    if (!setupRingBuffers(context)) {
        destroyVkContext(env, context);
        return NULL;
    }

    return context;
}

void destroyVkContext(JNIEnv* env, VkContext* context) {
    context->status = VK_ERROR_DEVICE_LOST;

    if (context->requestHandlerThread) {
        RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
        RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);

        for (int i = 0; i < RING_POOL_ARRAY_LEN; i++) {
            RingBuffer_setStatus(context->ringPool[i].ring, RING_STATUS_EXIT);
        }

        pthread_join(context->requestHandlerThread, NULL);

        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;

        context->requestHandlerThread = 0;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);

        for (int i = 0; i < RING_POOL_ARRAY_LEN; i++) {
            RingBuffer_free(context->ringPool[i].ring);
        }
    }

    if (context->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    context->graphicsQueueIndex = 0;

    if (context->textureDecoder) {
        TextureDecoder_destroy(context->textureDecoder);
        context->textureDecoder = NULL;
    }

    ArrayList_free(context->exposedDeviceExtensions, true);
    context->exposedDeviceExtensions = NULL;

    ArrayList_free(context->disabledDeviceExtensions, true);
    context->disabledDeviceExtensions = NULL;

    ArrayList_free(&context->extraDataRequests, true);
    pthread_mutex_destroy(&context->extraDataRequestsMutex);

    MEMFREE(context->memoryPool.data);
    MEMFREE(context->engineName);
    free(context);
}

bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength) {
    void* data = NULL;
    if (requestLength > 0) {
        data = calloc(requestLength, 1);
        int bytesRead = sock_read(context->clientFd, data, requestLength);
        if (bytesRead != requestLength) return false;
    }

    ExtraDataRequest* extraDataRequest = calloc(1, sizeof(ExtraDataRequest));
    extraDataRequest->requestId = requestId;
    extraDataRequest->size = requestLength;
    extraDataRequest->data = data;

    pthread_mutex_lock(&context->extraDataRequestsMutex);
    ArrayList_add(&context->extraDataRequests, extraDataRequest);
    pthread_mutex_unlock(&context->extraDataRequestsMutex);
    return true;
}