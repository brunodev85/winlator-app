#ifndef WINLATOR_MEMORY_H
#define WINLATOR_MEMORY_H

#include <cstddef>
#include <cstdint>

namespace WinlatorMemory {

constexpr uint64_t VIRTUAL_RAM_16GB = 16ULL * 1024ULL * 1024ULL * 1024ULL;

uint64_t getPhysicalRAM();
uint64_t getConfiguredRAM();

bool reserveMemory(std::size_t size);
void releaseMemory(std::size_t size);

std::size_t getReservedMemory();

void initialize();
void shutdown();

}

#endif
