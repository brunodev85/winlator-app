#ifndef VORTEK_XWINDOW_SWAPCHAIN_H
#define VORTEK_XWINDOW_SWAPCHAIN_H

#include "vortek.h"

typedef struct XWindowSwapchain_Image {
    VkImage image;
    VkDeviceMemory memory;
    VkFence fence;
    bool acquired;
} XWindowSwapchain_Image;

typedef struct XWindowSwapchain {
    VkDevice device;
    int windowId;
    VkPresentModeKHR presentMode;
    XWindowSwapchain_Image* images;
    int imageCount;
    short imageSize[2];
    short windowSize[2];
    VkQueue queue;
    JMethods* jmethods;
    VkImage windowImage;
    VkDeviceMemory windowMemory;
    VkCommandPool commandPool;
    VkCommandBuffer* commandBuffers;
    uint64_t lastIdleTime;
} XWindowSwapchain;

extern void getWindowSize(JMethods* jmethods, int windowId, short* outSize);
extern int getMinImageCountForPresentMode(VkPresentModeKHR presentMode);
extern int indexOfPresentMode(VkPresentModeKHR presentMode);
extern VkSurfaceFormatKHR* getSurfaceFormats(uint32_t* formatCount);

extern XWindowSwapchain* XWindowSwapchain_create(VkDevice device, uint32_t graphicsQueueIndex, VkSwapchainCreateInfoKHR* swapchainInfo, JMethods* jmethods, int windowId);
extern void XWindowSwapchain_destroy(VkDevice device, XWindowSwapchain* swapchain);
extern void XWindowSwapchain_acquireNextImage(XWindowSwapchain* swapchain, VkContext* context, uint64_t timeout, VkSemaphore signalSemaphore, VkFence fence, ringid_t ringId);
extern void XWindowSwapchain_presentImage(XWindowSwapchain* swapchain, VkContext* context, uint32_t imageIndex, VkSemaphore* waitSemaphores, uint32_t waitSemaphoreCount);

extern const VkPresentModeKHR supportedPresentModes[4];

#endif