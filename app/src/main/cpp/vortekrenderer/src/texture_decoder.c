#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "texture_decoder.h"
#include "vulkan_helper.h"
#include "bc_decoder.h"
#include "string_utils.h"
#include "file_utils.h"

#define CACHE_DIR APP_CACHE_DIR "/vortek"
#define CACHE_MIN_IMAGE_WIDTH 1024

#define VK_FORMAT_BC_FLAG(f) (1 << (f - VK_FORMAT_BC1_RGB_UNORM_BLOCK))

static bool isCanDecompressFormat(VkFormat format) {
    switch (format) {
        case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
        case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
        case VK_FORMAT_BC2_UNORM_BLOCK:
        case VK_FORMAT_BC2_SRGB_BLOCK:
        case VK_FORMAT_BC3_UNORM_BLOCK:
        case VK_FORMAT_BC3_SRGB_BLOCK:
        case VK_FORMAT_BC4_UNORM_BLOCK:
        case VK_FORMAT_BC4_SNORM_BLOCK:
        case VK_FORMAT_BC5_UNORM_BLOCK:
        case VK_FORMAT_BC5_SNORM_BLOCK:
            return true;
        default:
            return false;
    }
}

static void getBCInfo(VkFormat format, int* bcN, int* blockSize, bool* isNoAlphaU) {
    *isNoAlphaU = false;
    switch (format) {
        case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
            *isNoAlphaU = true;
        case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
            *blockSize = 8;
            *bcN = 1;
            break;
        case VK_FORMAT_BC2_UNORM_BLOCK:
        case VK_FORMAT_BC2_SRGB_BLOCK:
            *blockSize = 16;
            *bcN = 2;
            break;
        case VK_FORMAT_BC3_UNORM_BLOCK:
        case VK_FORMAT_BC3_SRGB_BLOCK:
            *blockSize = 16;
            *bcN = 3;
            break;
        case VK_FORMAT_BC4_UNORM_BLOCK:
            *isNoAlphaU = true;
        case VK_FORMAT_BC4_SNORM_BLOCK:
            *blockSize = 8;
            *bcN = 4;
            break;
        case VK_FORMAT_BC5_UNORM_BLOCK:
            *isNoAlphaU = true;
        case VK_FORMAT_BC5_SNORM_BLOCK:
            *blockSize = 16;
            *bcN = 5;
            break;
        default:
            *blockSize = 0;
            *bcN = 0;
            break;
    }
}

static void internalDestroyImage(VkDevice device, TextureDecoder_Image* targetImage) {
    if (targetImage->image) vulkanWrapper.vkDestroyImage(device, targetImage->image, NULL);
    MEMFREE(targetImage);
}

static bool readCachedImage(TextureDecoder_Image* image, uint64_t hash, void* result) {
    char filename[128] = {0};
    sprintf(filename, CACHE_DIR "/%lx-%dx%d-%d.imd", hash, image->width, image->height, image->format);

    createDirectory(CACHE_DIR);
    size_t size = image->width * image->height * 4;
    return fileGetContents(filename, result, &size) ? true : false;
}

static void writeImageToCache(TextureDecoder* textureDecoder, TextureDecoder_Image* image, uint64_t hash) {
    if (textureDecoder->imageCacheSize == 0) return;
    createDirectory(CACHE_DIR);

    char* content = fileGetContents(CACHE_DIR "/.cache-size", NULL, NULL);
    uint64_t currentCacheSize = 0;
    if (content) {
        currentCacheSize = strtoll(content, NULL, 10);
        MEMFREE(content);
    }

    uint64_t maxCacheSize = (uint64_t)textureDecoder->imageCacheSize << 20;
    while (currentCacheSize > maxCacheSize) {
        FindFileInfo fileInfo = {0};
        if (findFirstFile(CACHE_DIR, &fileInfo) && remove(fileInfo.path) == 0) {
            currentCacheSize -= fileInfo.size;
        }
        else return;
    }

    char filename[128] = {0};
    sprintf(filename, CACHE_DIR "/%lx-%dx%d-%d.imd", hash, image->width, image->height, image->format);
    size_t size = image->width * image->height * 4;

    bool success = false;
    if (filePutContents(filename, textureDecoder->decompressedData, size)) {
        currentCacheSize += size;
        char value[32] = {0};
        sprintf(value, "%ld", currentCacheSize);
        success = filePutContents(CACHE_DIR "/.cache-size", value, strlen(value));
    }

    if (!success) remove(filename);
}

static uint32_t getDeviceSupportedFormats(VkPhysicalDevice physicalDevice) {
    const VkFormat formats[] = {
        VK_FORMAT_BC1_RGB_UNORM_BLOCK,
        VK_FORMAT_BC1_RGB_SRGB_BLOCK,
        VK_FORMAT_BC1_RGBA_UNORM_BLOCK,
        VK_FORMAT_BC1_RGBA_SRGB_BLOCK,
        VK_FORMAT_BC2_UNORM_BLOCK,
        VK_FORMAT_BC2_SRGB_BLOCK,
        VK_FORMAT_BC3_UNORM_BLOCK,
        VK_FORMAT_BC3_SRGB_BLOCK,
        VK_FORMAT_BC4_UNORM_BLOCK,
        VK_FORMAT_BC4_SNORM_BLOCK,
        VK_FORMAT_BC5_UNORM_BLOCK,
        VK_FORMAT_BC5_SNORM_BLOCK
    };

    uint32_t flags = 0;
    for (int i = 0; i < ARRAY_SIZE(formats); i++) {
        VkFormatProperties formatProperties = {0};
        vulkanWrapper.vkGetPhysicalDeviceFormatProperties(physicalDevice, formats[i], &formatProperties);
        if (formatProperties.optimalTilingFeatures > 0) flags |= VK_FORMAT_BC_FLAG(formats[i]);
    }
    return flags;
}

static void destroyDecodeBuffer(TextureDecoder* textureDecoder) {
    if (textureDecoder->decompressedData) {
        vulkanWrapper.vkUnmapMemory(textureDecoder->device, textureDecoder->memory);
        textureDecoder->decompressedData = NULL;
    }
    if (textureDecoder->buffer) {
        vulkanWrapper.vkDestroyBuffer(textureDecoder->device, textureDecoder->buffer, NULL);
        textureDecoder->buffer = NULL;
    }
    if (textureDecoder->memory) {
        vulkanWrapper.vkFreeMemory(textureDecoder->device, textureDecoder->memory, NULL);
        textureDecoder->memory = NULL;
    }
}

static bool createDecodeBuffer(TextureDecoder* textureDecoder, uint32_t dataSize) {
    destroyDecodeBuffer(textureDecoder);

    VkBufferCreateInfo bufferInfo = {0};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = dataSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    VkBuffer buffer;
    VkResult result = vulkanWrapper.vkCreateBuffer(textureDecoder->device, &bufferInfo, NULL, &buffer);
    if (result != VK_SUCCESS) goto error;
    textureDecoder->buffer = buffer;

    VkMemoryRequirements memReqs = {0};
    vulkanWrapper.vkGetBufferMemoryRequirements(textureDecoder->device, buffer, &memReqs);

    VkMemoryAllocateInfo allocateInfo = {0};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = textureDecoder->bufferSize = memReqs.size;
    allocateInfo.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);

    VkDeviceMemory memory;
    result = vulkanWrapper.vkAllocateMemory(textureDecoder->device, &allocateInfo, NULL, &memory);
    if (result != VK_SUCCESS) goto error;
    textureDecoder->memory = memory;

    result = vulkanWrapper.vkBindBufferMemory(textureDecoder->device, buffer, memory, 0);
    if (result != VK_SUCCESS) goto error;

    result = vulkanWrapper.vkMapMemory(textureDecoder->device, memory, 0, textureDecoder->bufferSize, 0, &textureDecoder->decompressedData);
    if (result != VK_SUCCESS) goto error;
    return true;

error:
    destroyDecodeBuffer(textureDecoder);
    return false;
}

static void destroyCopyCommandBuffer(TextureDecoder* textureDecoder) {
    if (textureDecoder->commandBuffer) {
        vulkanWrapper.vkFreeCommandBuffers(textureDecoder->device, textureDecoder->commandPool, 1, &textureDecoder->commandBuffer);
        textureDecoder->commandBuffer = NULL;
    }
    if (textureDecoder->commandPool) {
        vulkanWrapper.vkDestroyCommandPool(textureDecoder->device, textureDecoder->commandPool, NULL);
        textureDecoder->commandPool = NULL;
    }
    if (textureDecoder->fence) {
        vulkanWrapper.vkDestroyFence(textureDecoder->device, textureDecoder->fence, NULL);
        textureDecoder->fence = NULL;
    }
}

static bool createCopyCommandBuffer(TextureDecoder* textureDecoder, uint32_t graphicsQueueIndex) {
    VkCommandPoolCreateInfo commandPoolInfo = {0};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = graphicsQueueIndex;

    VkResult result = vulkanWrapper.vkCreateCommandPool(textureDecoder->device, &commandPoolInfo, NULL, &textureDecoder->commandPool);
    if (result != VK_SUCCESS) goto error;

    VkCommandBufferAllocateInfo commandBufferInfo = {0};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = textureDecoder->commandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = 1;

    result = vulkanWrapper.vkAllocateCommandBuffers(textureDecoder->device, &commandBufferInfo, &textureDecoder->commandBuffer);
    if (result != VK_SUCCESS) goto error;

    VkFenceCreateInfo fenceInfo = {0};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    result = vulkanWrapper.vkCreateFence(textureDecoder->device, &fenceInfo, NULL, &textureDecoder->fence);
    if (result != VK_SUCCESS) goto error;

    vulkanWrapper.vkGetDeviceQueue(textureDecoder->device, graphicsQueueIndex, 0, &textureDecoder->queue);
    return true;

error:
    destroyCopyCommandBuffer(textureDecoder);
    return false;
}

TextureDecoder* TextureDecoder_create(VkContext* context, VkPhysicalDevice physicalDevice, VkDevice device, VkPhysicalDeviceFeatures* supportedFeatures) {
    if (supportedFeatures->textureCompressionBC) return NULL;
    TextureDecoder* textureDecoder = calloc(1, sizeof(TextureDecoder));
    textureDecoder->device = device;
    textureDecoder->imageCacheSize = context->imageCacheSize;
    textureDecoder->threadPool = context->threadPool;

    createCopyCommandBuffer(textureDecoder, context->graphicsQueueIndex);
    if (context->driverID == VK_DRIVER_ID_SAMSUNG_PROPRIETARY) textureDecoder->deviceSupportedFormats = getDeviceSupportedFormats(physicalDevice);
    return textureDecoder;
}

void TextureDecoder_destroy(TextureDecoder* textureDecoder) {
    destroyDecodeBuffer(textureDecoder);
    destroyCopyCommandBuffer(textureDecoder);

    SparseArray64_free(&textureDecoder->images, true);
    SparseArray64_free(&textureDecoder->boundBuffers, true);
    MEMFREE(textureDecoder->bufferImageCopies.elements);
    MEMFREE(textureDecoder);
}

void TextureDecoder_decodeAll(TextureDecoder* textureDecoder) {
    while (!ArrayDeque_isEmpty(&textureDecoder->bufferImageCopies)) {
        TextureDecoder_BufferImageCopy* bufferImageCopy = ArrayDeque_removeFirst(&textureDecoder->bufferImageCopies);
        if (!bufferImageCopy) goto out;

        TextureDecoder_BoundBuffer* srcBuffer = bufferImageCopy->srcBuffer;
        TextureDecoder_Image* dstImage = bufferImageCopy->dstImage;
        if (!srcBuffer || !dstImage) goto out;

        void* bufferData = mmap(NULL, srcBuffer->memory->allocationSize, PROT_READ, MAP_SHARED, srcBuffer->memory->fd, 0);
        if (bufferData == MAP_FAILED) goto out;

        VkBufferImageCopy* region = &bufferImageCopy->region;
        void* imageData = bufferData + (srcBuffer->memoryOffset + region->bufferOffset);
        region->bufferOffset = 0;

        int blockSize;
        int bcN;
        bool isNoAlphaU;
        getBCInfo(dstImage->format, &bcN, &blockSize, &isNoAlphaU);

        uint64_t hash = 0;
        bool isCachedImage = false;
        if (region->imageExtent.width >= CACHE_MIN_IMAGE_WIDTH && region->imageExtent.height >= CACHE_MIN_IMAGE_WIDTH) {
            int memorySize = (region->imageExtent.width / 4) * (region->imageExtent.height / 4) * blockSize;
            hash = murmurHash64(imageData, memorySize, dstImage->format);
            isCachedImage = readCachedImage(dstImage, hash, textureDecoder->decompressedData);
        }

        if (!isCachedImage) {
            int imageSize = region->imageExtent.width * region->imageExtent.height * 4;
            if (imageSize > textureDecoder->bufferSize) createDecodeBuffer(textureDecoder, imageSize);

            BCDecoder_decode(imageData, textureDecoder->decompressedData, region->imageExtent.width, region->imageExtent.height, bcN, isNoAlphaU, textureDecoder->threadPool);
            if (hash > 0) writeImageToCache(textureDecoder, dstImage, hash);
        }

        munmap(bufferData, srcBuffer->memory->allocationSize);

        VkCommandBufferBeginInfo beginInfo = {0};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vulkanWrapper.vkBeginCommandBuffer(textureDecoder->commandBuffer, &beginInfo);
        vulkanWrapper.vkCmdCopyBufferToImage(textureDecoder->commandBuffer, textureDecoder->buffer, dstImage->image, bufferImageCopy->dstImageLayout, 1, &bufferImageCopy->region);
        vulkanWrapper.vkEndCommandBuffer(textureDecoder->commandBuffer);

        VkSubmitInfo submitInfo = {0};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &textureDecoder->commandBuffer;

        vulkanWrapper.vkQueueSubmit(textureDecoder->queue, 1, &submitInfo, textureDecoder->fence);
        vulkanWrapper.vkWaitForFences(textureDecoder->device, 1, &textureDecoder->fence, VK_TRUE, UINT64_MAX);
        vulkanWrapper.vkResetFences(textureDecoder->device, 1, &textureDecoder->fence);

        out:
        MEMFREE(bufferImageCopy);
    }
}

void TextureDecoder_copyBufferToImage(TextureDecoder* textureDecoder, VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkImage dstImage, VkImageLayout dstImageLayout, uint32_t regionCount, VkBufferImageCopy* regions) {
    TextureDecoder_BoundBuffer* boundBuffer = SparseArray64_get(&textureDecoder->boundBuffers, (int64_t)srcBuffer);
    if (!boundBuffer) return;

    TextureDecoder_Image* targetImage = SparseArray64_get(&textureDecoder->images, (int64_t)dstImage);
    if (!targetImage) return;

    for (int i = 0; i < regionCount; i++) {
        TextureDecoder_BufferImageCopy* bufferImageCopy = calloc(1, sizeof(TextureDecoder_BufferImageCopy));
        bufferImageCopy->srcBuffer = boundBuffer;
        bufferImageCopy->dstImage = targetImage;
        bufferImageCopy->dstImageLayout = dstImageLayout;
        memcpy(&bufferImageCopy->region, &regions[i], sizeof(VkBufferImageCopy));
        ArrayDeque_addLast(&textureDecoder->bufferImageCopies, bufferImageCopy);
    }
}

void TextureDecoder_copyBufferToImage2(TextureDecoder* textureDecoder, VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkImage dstImage, VkImageLayout dstImageLayout, VkCopyBufferToImageInfo2* copyBufferToImageInfo) {
    TextureDecoder_BoundBuffer* boundBuffer = SparseArray64_get(&textureDecoder->boundBuffers, (int64_t)srcBuffer);
    if (!boundBuffer) return;

    TextureDecoder_Image* targetImage = SparseArray64_get(&textureDecoder->images, (int64_t)dstImage);
    if (!targetImage) return;

    for (int i = 0; i < copyBufferToImageInfo->regionCount; i++) {
        TextureDecoder_BufferImageCopy* bufferImageCopy = calloc(1, sizeof(TextureDecoder_BufferImageCopy));
        bufferImageCopy->srcBuffer = boundBuffer;
        bufferImageCopy->dstImage = targetImage;
        bufferImageCopy->dstImageLayout = dstImageLayout;

        const char* region = ((char*)&copyBufferToImageInfo->pRegions[i]) + offsetof(VkBufferImageCopy2, bufferOffset);
        memcpy(&bufferImageCopy->region, region, sizeof(VkBufferImageCopy));
        ArrayDeque_addLast(&textureDecoder->bufferImageCopies, bufferImageCopy);
    }
}

bool TextureDecoder_containsImage(TextureDecoder* textureDecoder, VkImage image) {
    return SparseArray64_indexOfKey(&textureDecoder->images, (int64_t)image) >= 0;
}

VkResult TextureDecoder_createImage(TextureDecoder* textureDecoder, VkImageCreateInfo* imageInfo, VkImage* pImage) {
    *pImage = VK_NULL_HANDLE;
    VkResult result;
    if (!isCanDecompressFormat(imageInfo->format)) return VK_ERROR_FORMAT_NOT_SUPPORTED;

    TextureDecoder_Image* newImage = calloc(1, sizeof(TextureDecoder_Image));
    newImage->format = imageInfo->format;
    newImage->width = imageInfo->extent.width;
    newImage->height = imageInfo->extent.height;

    imageInfo->format = DECOMPRESSED_FORMAT;
    BITMASK_UNSET(imageInfo->flags, VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT);

    VkImageFormatListCreateInfo* formatListInfo = findNextVkStructure(imageInfo->pNext, VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO);
    if (formatListInfo && formatListInfo->pViewFormats) {
        formatListInfo->viewFormatCount = 1;
        VkFormat* viewFormats = (VkFormat*)formatListInfo->pViewFormats;
        viewFormats[0] = DECOMPRESSED_FORMAT;
    }

    VkImage image;
    result = vulkanWrapper.vkCreateImage(textureDecoder->device, imageInfo, NULL, &image);
    if (result != VK_SUCCESS) goto error;;

    newImage->image = image;
    SparseArray64_put(&textureDecoder->images, (int64_t)image, newImage);

    *pImage = image;
    return result;

error:
    internalDestroyImage(textureDecoder->device, newImage);
    return result;
}

void TextureDecoder_destroyImage(TextureDecoder* textureDecoder, VkImage image) {
    TextureDecoder_Image* targetImage = SparseArray64_get(&textureDecoder->images, (int64_t)image);
    if (targetImage) {
        SparseArray64_remove(&textureDecoder->images, (int64_t)image);
        internalDestroyImage(textureDecoder->device, targetImage);
    }
}

void TextureDecoder_addBoundBuffer(TextureDecoder* textureDecoder, ResourceMemory* memory, VkBuffer buffer, VkDeviceSize memoryOffset) {
    if (memory->fd <= 0) return;
    TextureDecoder_removeBoundBuffer(textureDecoder, buffer);

    TextureDecoder_BoundBuffer* boundBuffer = calloc(1, sizeof(TextureDecoder_BoundBuffer));
    boundBuffer->buffer = buffer;
    boundBuffer->memoryOffset = memoryOffset;
    boundBuffer->memory = memory;

    SparseArray64_put(&textureDecoder->boundBuffers, (int64_t)buffer, boundBuffer);
}

void TextureDecoder_removeBoundBuffer(TextureDecoder* textureDecoder, VkBuffer buffer) {
    TextureDecoder_BoundBuffer* boundBuffer = SparseArray64_get(&textureDecoder->boundBuffers, (int64_t)buffer);
    if (boundBuffer) {
        SparseArray64_remove(&textureDecoder->boundBuffers, (int64_t)buffer);
        MEMFREE(boundBuffer);
    }
}

bool TextureDecoder_isFormatSupported(TextureDecoder* textureDecoder, VkFormat format) {
    if (!isCompressedFormat(format)) return false;
    if (textureDecoder->deviceSupportedFormats > 0) {
        bool result = textureDecoder->deviceSupportedFormats & VK_FORMAT_BC_FLAG(format);
        if (result) return false;
    }
    return true;
}

bool isCompressedFormat(VkFormat format) {
    switch(format) {
        case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
        case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
        case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
        case VK_FORMAT_BC2_UNORM_BLOCK:
        case VK_FORMAT_BC2_SRGB_BLOCK:
        case VK_FORMAT_BC3_UNORM_BLOCK:
        case VK_FORMAT_BC3_SRGB_BLOCK:
        case VK_FORMAT_BC4_UNORM_BLOCK:
        case VK_FORMAT_BC4_SNORM_BLOCK:
        case VK_FORMAT_BC5_UNORM_BLOCK:
        case VK_FORMAT_BC5_SNORM_BLOCK:
        case VK_FORMAT_BC6H_UFLOAT_BLOCK:
        case VK_FORMAT_BC6H_SFLOAT_BLOCK:
        case VK_FORMAT_BC7_UNORM_BLOCK:
        case VK_FORMAT_BC7_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
        case VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK:
        case VK_FORMAT_EAC_R11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11_SNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
        case VK_FORMAT_EAC_R11G11_SNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_4x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x4_SRGB_BLOCK:
        case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_5x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_6x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_8x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x5_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x6_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x8_SRGB_BLOCK:
        case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_10x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x10_SRGB_BLOCK:
        case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
        case VK_FORMAT_ASTC_12x12_SRGB_BLOCK:
            return true;
        default:
            return false;
    }
}

VkResult getCompressedImageFormatProperties(VkFormat format, VkImageFormatProperties* pImageFormatProperties) {
    if (isCanDecompressFormat(format)) {
        pImageFormatProperties->maxExtent.width = 16384;
        pImageFormatProperties->maxExtent.height = 16384;
        pImageFormatProperties->maxExtent.depth = 1;
        pImageFormatProperties->maxMipLevels = 15;
        pImageFormatProperties->maxArrayLayers = 2048;
        pImageFormatProperties->sampleCounts = VK_SAMPLE_COUNT_1_BIT;
        pImageFormatProperties->maxResourceSize = 1u << 31;
        return VK_SUCCESS;
    }
    else return VK_ERROR_FORMAT_NOT_SUPPORTED;
}