#include "xwindow_swapchain.h"
#include "vulkan_helper.h"
#include "time_utils.h"

typedef struct AcquireNextImageRequest {
    RingBuffer* responseRing;
    VkContext* context;
    XWindowSwapchain* swapchain;
    uint64_t timeout;
    VkSemaphore signalSemaphore;
    VkFence fence;
} AcquireNextImageRequest;

typedef struct SetIdleImageRequest {
    XWindowSwapchain* swapchain;
    uint8_t imageIndex;
    uint16_t maxFramerate;
} SetIdleImageRequest;

const VkPresentModeKHR supportedPresentModes[4] = {
    VK_PRESENT_MODE_IMMEDIATE_KHR,
    VK_PRESENT_MODE_MAILBOX_KHR,
    VK_PRESENT_MODE_FIFO_KHR,
    VK_PRESENT_MODE_FIFO_RELAXED_KHR
};

void getWindowSize(JMethods* jmethods, int windowId, short* outSize) {
    outSize[0] = (*jmethods->env)->CallShortMethod(jmethods->env, jmethods->obj, jmethods->getWindowWidth, windowId);
    outSize[1] = (*jmethods->env)->CallShortMethod(jmethods->env, jmethods->obj, jmethods->getWindowHeight, windowId);
}

static AHardwareBuffer* getWindowHardwareBuffer(JMethods* jmethods, int windowId, jboolean useHALPixelFormatBGRA8888) {
    jlong hardwareBufferPtr = (*jmethods->env)->CallLongMethod(jmethods->env, jmethods->obj, jmethods->getWindowHardwareBuffer, windowId, useHALPixelFormatBGRA8888);
    return (AHardwareBuffer*)hardwareBufferPtr;
}

static VkResult createWindowImage(XWindowSwapchain* swapchain, VkFormat imageFormat, VkImageUsageFlags imageUsage) {
    jboolean useHALPixelFormatBGRA8888 = imageFormat == VK_FORMAT_B8G8R8A8_UNORM || imageFormat == VK_FORMAT_B8G8R8A8_SRGB;
    AHardwareBuffer* hardwareBuffer = getWindowHardwareBuffer(swapchain->jmethods, swapchain->windowId, useHALPixelFormatBGRA8888);

    AHardwareBuffer_Desc ahbDesc = {0};
    AHardwareBuffer_describe(hardwareBuffer, &ahbDesc);
    swapchain->imageSize[0] = ahbDesc.width;
    swapchain->imageSize[1] = ahbDesc.height;

    VkExternalFormatANDROID externalFormatAndroid = {0};
    externalFormatAndroid.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormatAndroid.externalFormat = 0;

    VkExternalMemoryImageCreateInfo externalMemoryImageInfo = {0};
    externalMemoryImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalMemoryImageInfo.pNext = &externalFormatAndroid;
    externalMemoryImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    BITMASK_UNSET(imageUsage, VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    BITMASK_SET(imageUsage, VK_IMAGE_USAGE_TRANSFER_DST_BIT);

    VkImageCreateInfo imageInfo = {0};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalMemoryImageInfo;
    imageInfo.flags = VK_IMAGE_CREATE_ALIAS_BIT;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = imageFormat;
    imageInfo.extent.width = swapchain->imageSize[0];
    imageInfo.extent.height = swapchain->imageSize[1];
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = imageUsage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkResult result;
    result = vulkanWrapper.vkCreateImage(swapchain->device, &imageInfo, NULL, &swapchain->windowImage);
    if (result != VK_SUCCESS) return result;

    VkAndroidHardwareBufferPropertiesANDROID ahbProperties = {0};
    ahbProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    vulkanWrapper.vkGetAndroidHardwareBufferPropertiesANDROID(swapchain->device, hardwareBuffer, &ahbProperties);

    VkImportAndroidHardwareBufferInfoANDROID memoryImportInfo = {0};
    memoryImportInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    memoryImportInfo.buffer = hardwareBuffer;

    VkMemoryDedicatedAllocateInfo memoryDedicatedInfo = {0};
    memoryDedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    memoryDedicatedInfo.pNext = &memoryImportInfo;
    memoryDedicatedInfo.image = swapchain->windowImage;
    memoryDedicatedInfo.buffer = VK_NULL_HANDLE;

    VkMemoryAllocateInfo memoryInfo = {0};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.pNext = &memoryDedicatedInfo;
    memoryInfo.allocationSize = ahbProperties.allocationSize;
    memoryInfo.memoryTypeIndex = getMemoryTypeIndex(ahbProperties.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    result = vulkanWrapper.vkAllocateMemory(swapchain->device, &memoryInfo, NULL, &swapchain->windowMemory);
    if (result != VK_SUCCESS) return result;

    result = vulkanWrapper.vkBindImageMemory(swapchain->device, swapchain->windowImage, swapchain->windowMemory, 0);
    return result;
}

static VkResult createOffscreenImage(XWindowSwapchain* swapchain, XWindowSwapchain_Image* swapchainImage, VkFormat imageFormat, VkImageUsageFlags imageUsage) {
    BITMASK_UNSET(imageUsage, VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    BITMASK_SET(imageUsage, VK_IMAGE_USAGE_TRANSFER_SRC_BIT);

    VkImageCreateInfo imageInfo = {0};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.flags = VK_IMAGE_CREATE_ALIAS_BIT;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = imageFormat;
    imageInfo.extent.width = swapchain->imageSize[0];
    imageInfo.extent.height = swapchain->imageSize[1];
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = imageUsage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkResult result;
    result = vulkanWrapper.vkCreateImage(swapchain->device, &imageInfo, NULL, &swapchainImage->image);
    if (result != VK_SUCCESS) return result;

    VkMemoryRequirements memReqs;
    vulkanWrapper.vkGetImageMemoryRequirements(swapchain->device, swapchainImage->image, &memReqs);

    VkMemoryAllocateInfo memoryInfo = {0};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.allocationSize = memReqs.size;
    memoryInfo.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    result = vulkanWrapper.vkAllocateMemory(swapchain->device, &memoryInfo, NULL, &swapchainImage->memory);
    if (result != VK_SUCCESS) return result;

    result = vulkanWrapper.vkBindImageMemory(swapchain->device, swapchainImage->image, swapchainImage->memory, 0);
    if (result != VK_SUCCESS) return result;

    VkFenceCreateInfo fenceInfo = {0};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    result = vulkanWrapper.vkCreateFence(swapchain->device, &fenceInfo, NULL, &swapchainImage->fence);
    return result;
}

int getMinImageCountForPresentMode(VkPresentModeKHR presentMode) {
    if (presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR)
        return 1;
    else if (presentMode == VK_PRESENT_MODE_MAILBOX_KHR)
        return 3;
    else
        return 2;
}

int indexOfPresentMode(VkPresentModeKHR presentMode) {
    for (int i = 0; i < ARRAY_SIZE(supportedPresentModes); i++) {
        if (supportedPresentModes[i] == presentMode) return i;
    }
    return -1;
}

static VkResult createCopyCommandBuffers(XWindowSwapchain* swapchain, uint32_t graphicsQueueIndex) {
    VkCommandPoolCreateInfo commandPoolInfo = {0};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = graphicsQueueIndex;

    VkResult result = vulkanWrapper.vkCreateCommandPool(swapchain->device, &commandPoolInfo, NULL, &swapchain->commandPool);
    if (result != VK_SUCCESS) return result;

    VkCommandBufferAllocateInfo commandBufferInfo = {0};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = swapchain->commandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = swapchain->imageCount;

    swapchain->commandBuffers = calloc(swapchain->imageCount, sizeof(VkCommandBuffer));
    result = vulkanWrapper.vkAllocateCommandBuffers(swapchain->device, &commandBufferInfo, swapchain->commandBuffers);
    return result;
}

static void setIdleImageThread(void* param) {
    SetIdleImageRequest* req = param;

    XWindowSwapchain_Image* swapchainImage = &req->swapchain->images[req->imageIndex];
    const uint64_t timeout = 100000000;
    vulkanWrapper.vkWaitForFences(req->swapchain->device, 1, &swapchainImage->fence, VK_TRUE, timeout);

    uint64_t frameTime = NSEC_PER_SEC / req->maxFramerate;
    uint64_t now = nanoTime();

    uint64_t lastIdleTime = req->swapchain->lastIdleTime > 0 ? req->swapchain->lastIdleTime : now;
    lastIdleTime = lastIdleTime <= (now - frameTime) ? now + frameTime : lastIdleTime + frameTime;
    uint64_t delayNanos = lastIdleTime - now;
    req->swapchain->lastIdleTime = lastIdleTime;

    struct timespec ts = {.tv_sec = 0, .tv_nsec = delayNanos};
    nanosleep(&ts, NULL);

    swapchainImage->acquired = false;
    free(req);
}

VkSurfaceFormatKHR* getSurfaceFormats(uint32_t* formatCount) {
    static const VkFormat supportedFormats[] = {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB};
    int supportedFormatCount = ARRAY_SIZE(supportedFormats);
    VkSurfaceFormatKHR* surfaceFormats = calloc(supportedFormatCount, sizeof(VkSurfaceFormatKHR));

    if (formatCount) *formatCount = supportedFormatCount;

    for (int i = 0; i < supportedFormatCount; i++) {
        surfaceFormats[i].format = supportedFormats[i];
        surfaceFormats[i].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    }

    return surfaceFormats;
}

XWindowSwapchain* XWindowSwapchain_create(VkDevice device, uint32_t graphicsQueueIndex, VkSwapchainCreateInfoKHR* swapchainInfo, JMethods* jmethods, int windowId) {
    XWindowSwapchain* swapchain = calloc(1, sizeof(XWindowSwapchain));
    swapchain->device = device;
    swapchain->windowId = windowId;
    swapchain->presentMode = swapchainInfo->presentMode;
    swapchain->imageCount = swapchainInfo->minImageCount;
    swapchain->images = calloc(swapchain->imageCount, sizeof(XWindowSwapchain_Image));
    swapchain->jmethods = jmethods;
    getWindowSize(swapchain->jmethods, windowId, swapchain->windowSize);

    if (createWindowImage(swapchain, swapchainInfo->imageFormat, swapchainInfo->imageUsage) != VK_SUCCESS) goto error;
    if (createCopyCommandBuffers(swapchain, graphicsQueueIndex) != VK_SUCCESS) goto error;

    for (int i = 0; i < swapchain->imageCount; i++) {
        if (swapchain->presentMode != VK_PRESENT_MODE_IMMEDIATE_KHR) {
            VkResult result = createOffscreenImage(swapchain, &swapchain->images[i], swapchainInfo->imageFormat, swapchainInfo->imageUsage);
            if (result != VK_SUCCESS) goto error;
        }
        else {
            swapchain->images[i].image = swapchain->windowImage;
            swapchain->images[i].memory = swapchain->windowMemory;
        }
    }

    vulkanWrapper.vkGetDeviceQueue(device, graphicsQueueIndex, 0, &swapchain->queue);
    return swapchain;

error:
    XWindowSwapchain_destroy(device, swapchain);
    return NULL;
}

void XWindowSwapchain_destroy(VkDevice device, XWindowSwapchain* swapchain) {
    if (!swapchain) return;
    if (swapchain->windowImage) {
        vulkanWrapper.vkDestroyImage(device, swapchain->windowImage, NULL);
        swapchain->windowImage = VK_NULL_HANDLE;
    }

    if (swapchain->windowMemory) {
        vulkanWrapper.vkFreeMemory(device, swapchain->windowMemory, NULL);
        swapchain->windowMemory = VK_NULL_HANDLE;
    }

    if (swapchain->commandBuffers) {
        vulkanWrapper.vkFreeCommandBuffers(device, swapchain->commandPool, swapchain->imageCount, swapchain->commandBuffers);
        MEMFREE(swapchain->commandBuffers);
    }

    if (swapchain->commandPool) {
        vulkanWrapper.vkDestroyCommandPool(device, swapchain->commandPool, NULL);
        swapchain->commandPool = NULL;
    }

    for (int i = 0; i < swapchain->imageCount; i++) {
        XWindowSwapchain_Image* swapchainImage = &swapchain->images[i];

        if (swapchain->presentMode != VK_PRESENT_MODE_IMMEDIATE_KHR) {
            if (swapchainImage->image) {
                vulkanWrapper.vkDestroyImage(device, swapchainImage->image, NULL);
                swapchainImage->image = VK_NULL_HANDLE;
            }

            if (swapchainImage->memory) {
                vulkanWrapper.vkFreeMemory(device, swapchainImage->memory, NULL);
                swapchainImage->memory = VK_NULL_HANDLE;
            }
        }

        if (swapchainImage->fence) {
            vulkanWrapper.vkDestroyFence(device, swapchainImage->fence, NULL);
            swapchainImage->fence = VK_NULL_HANDLE;
        }
    }

    MEMFREE(swapchain->images);
    MEMFREE(swapchain);
}

static void acquireNextImageThread(void* param) {
    AcquireNextImageRequest* req = param;
    VkResult result = VK_SUCCESS;
    uint64_t endTime = nanoTime() + req->timeout;

    int imageIndex = -1;
    uint32_t busyWaitIter = 0;
    while (req->context->status >= 0) {
        for (int i = 0; i < req->swapchain->imageCount; i++) {
            XWindowSwapchain_Image* swapchainImage = &req->swapchain->images[i];
            if (!swapchainImage->acquired) {
                if (swapchainImage->fence) {
                    VkResult status = vulkanWrapper.vkGetFenceStatus(req->swapchain->device, swapchainImage->fence);
                    if (status == VK_NOT_READY) continue;
                }
                swapchainImage->acquired = true;
                imageIndex = i;
                break;
            }
        }

        if (RingBuffer_hasStatus(req->context->clientRing, SWAPCHAIN_STATUS_SUBOPTIMAL)) {
            result = VK_SUBOPTIMAL_KHR;
            break;
        }

        if (imageIndex >= 0) break;

        if (req->timeout == 0) {
            result = VK_NOT_READY;
            break;
        }
        else if (req->timeout != UINT64_MAX) {
            uint64_t currentTime = nanoTime();
            if (currentTime > endTime) {
                result = VK_TIMEOUT;
                break;
            }
        }

        busyWaitIter = busyWait(busyWaitIter);
    }

    if (req->context->status < 0) result = req->context->status;

    if ((result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) && (req->signalSemaphore || req->fence)) {
        VkSubmitInfo submitInfo = {0};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        if (req->signalSemaphore) {
            submitInfo.pSignalSemaphores = &req->signalSemaphore;
            submitInfo.signalSemaphoreCount = 1;
        }

        vulkanWrapper.vkQueueSubmit(req->swapchain->queue, 1, &submitInfo, req->fence);
    }

    char outputBuffer[8];
    *(int*)(outputBuffer + 0) = result;
    *(int*)(outputBuffer + 4) = imageIndex;
    RingBuffer_write(req->responseRing, outputBuffer, sizeof(outputBuffer));
    free(req);
}

void XWindowSwapchain_acquireNextImage(XWindowSwapchain* swapchain, VkContext* context, uint64_t timeout, VkSemaphore signalSemaphore, VkFence fence, ringid_t ringId) {
    short windowSize[2];
    getWindowSize(swapchain->jmethods, swapchain->windowId, windowSize);

    if (swapchain->windowSize[0] != windowSize[0] || swapchain->windowSize[1] != windowSize[1]) {
        RingBuffer_setStatus(context->clientRing, SWAPCHAIN_STATUS_SUBOPTIMAL);
    }

    AcquireNextImageRequest* acquireNextImageRequest = calloc(1, sizeof(AcquireNextImageRequest));
    acquireNextImageRequest->responseRing = context->ringPool[ringId].ring;
    acquireNextImageRequest->context = context;
    acquireNextImageRequest->swapchain = swapchain;
    acquireNextImageRequest->signalSemaphore = signalSemaphore;
    acquireNextImageRequest->fence = fence;
    acquireNextImageRequest->timeout = timeout;

    ThreadPool_run(context->threadPool, acquireNextImageThread, acquireNextImageRequest);
}

void XWindowSwapchain_presentImage(XWindowSwapchain* swapchain, VkContext* context, uint32_t imageIndex, VkSemaphore* waitSemaphores, uint32_t waitSemaphoreCount) {
    if (swapchain->presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR) {
        (*swapchain->jmethods->env)->CallVoidMethod(swapchain->jmethods->env, swapchain->jmethods->obj,
                                                    swapchain->jmethods->updateWindowContent, swapchain->windowId);
        swapchain->images[imageIndex].acquired = false;
        return;
    }

    XWindowSwapchain_Image* swapchainImage = &swapchain->images[imageIndex];
    VkCommandBuffer commandBuffer = swapchain->commandBuffers[imageIndex];

    VkCommandBufferBeginInfo beginInfo = {0};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vulkanWrapper.vkBeginCommandBuffer(commandBuffer, &beginInfo);

    VkImageCopy region = {0};
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.dstSubresource.layerCount = 1;
    region.extent.width = swapchain->imageSize[0];
    region.extent.height = swapchain->imageSize[1];
    region.extent.depth = 1;

    vulkanWrapper.vkCmdCopyImage(commandBuffer, swapchainImage->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapchain->windowImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    vulkanWrapper.vkEndCommandBuffer(commandBuffer);

    VkSubmitInfo submitInfo = {0};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    submitInfo.waitSemaphoreCount = waitSemaphoreCount;
    submitInfo.pWaitSemaphores = waitSemaphores;

    vulkanWrapper.vkQueueSubmit(swapchain->queue, 1, &submitInfo, swapchainImage->fence);

    (*swapchain->jmethods->env)->CallVoidMethod(swapchain->jmethods->env, swapchain->jmethods->obj,
                                                swapchain->jmethods->updateWindowContent, swapchain->windowId);

    if (swapchain->presentMode == VK_PRESENT_MODE_FIFO_KHR) {
        SetIdleImageRequest* setIdleImageRequest = calloc(1, sizeof(SetIdleImageRequest));
        setIdleImageRequest->swapchain = swapchain;
        setIdleImageRequest->imageIndex = imageIndex;
        setIdleImageRequest->maxFramerate = context->maxFramerate;
        ThreadPool_run(context->threadPool, setIdleImageThread, setIdleImageRequest);
    }
    else swapchainImage->acquired = false;
}