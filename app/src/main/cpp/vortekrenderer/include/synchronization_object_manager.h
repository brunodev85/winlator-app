#ifndef VORTEK_SYNCHRONIZATION_OBJECT_MANAGER_H
#define VORTEK_SYNCHRONIZATION_OBJECT_MANAGER_H

#include "vortek.h"

extern void SynchronizationObjectManager_waitForFences(VkContext* context);
extern void SynchronizationObjectManager_waitSemaphores(VkContext* context);

#endif