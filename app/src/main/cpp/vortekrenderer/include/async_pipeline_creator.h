#ifndef VORTEK_ASYNC_PIPELINE_CREATOR_H
#define VORTEK_ASYNC_PIPELINE_CREATOR_H

#include "vortek.h"

extern void AsyncPipelineCreator_createGraphicsPipelines(VkContext* context);
extern void AsyncPipelineCreator_createComputePipelines(VkContext* context);

#endif