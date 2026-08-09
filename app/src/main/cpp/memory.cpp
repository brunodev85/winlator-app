#include "memory.h"

#include <android/log.h>
#include <sys/sysinfo.h>
#include <mutex>
#include <algorithm>

#define LOG_TAG "WinlatorMemory"

namespace WinlatorMemory {

static std::mutex memoryMutex;
static uint64_t reservedMemory = 0;

uint64_t getPhysicalRAM() {
    struct sysinfo info{};

    if (sysinfo(&info) != 0)
        return 0;

    return static_cast<uint64_t>(info.totalram) *
           static_cast<uint64_t>(info.mem_unit);
}

uint64_t getConfiguredRAM() {
    // Limite lógico máximo.
    // NÃO cria RAM física adicional.
    return VIRTUAL_RAM_16GB;
}

void initialize() {
    std::lock_guard<std::mutex> lock(memoryMutex);

    reservedMemory = 0;

    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG,
        "Physical RAM: %llu MB",
        static_cast<unsigned long long>(
            getPhysicalRAM() / (1024ULL * 1024ULL)
        )
    );

    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG,
        "Logical memory limit: 16384 MB"
    );
}

bool reserveMemory(std::size_t size) {
    std::lock_guard<std::mutex> lock(memoryMutex);

    if (size > VIRTUAL_RAM_16GB)
        return false;

    if (reservedMemory + size > VIRTUAL_RAM_16GB)
        return false;

    reservedMemory += size;
    return true;
}

void releaseMemory(std::size_t size) {
    std::lock_guard<std::mutex> lock(memoryMutex);

    if (size >= reservedMemory)
        reservedMemory = 0;
    else
        reservedMemory -= size;
}

std::size_t getReservedMemory() {
    std::lock_guard<std::mutex> lock(memoryMutex);
    return static_cast<std::size_t>(reservedMemory);
}

void shutdown() {
    std::lock_guard<std::mutex> lock(memoryMutex);
    reservedMemory = 0;
}

}
