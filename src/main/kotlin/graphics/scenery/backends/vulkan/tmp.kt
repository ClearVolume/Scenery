package graphics.scenery.backends.vulkan

import glm_.L
import glm_.i
import glm_.set
import kool.*
import org.lwjgl.system.*
import org.lwjgl.system.MemoryStack.stackGet
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import uno.kotlin.buffers.lastIndex
import vkk.*
import vkk.`object`.*
import java.nio.Buffer
import java.nio.IntBuffer
import java.nio.LongBuffer

operator fun VkDeviceSize.rem(b: VkDeviceSize) = VkDeviceSize(L % b.L)
val VkDeviceSize.i
    get() = L.i

operator fun Int.rem(b: VkDeviceSize) = VkDeviceSize(this % b.L)
operator fun Int.plus(b: VkDeviceSize) = VkDeviceSize(this + b.L)
operator fun VkDeviceSize.minus(b: VkDeviceSize) = VkDeviceSize(L - b.L)
operator fun VkDeviceSize.times(b: Int) = VkDeviceSize(L * b)

inline var Buffer.lim: Int
    get() = limit()
    set(value) {
        limit(value)
    }

infix fun Buffer.copyTo(ptr: Ptr) = MemoryUtil.memCopy(adr, ptr, remSize.L)
infix fun Buffer.copyFrom(ptr: Ptr) = MemoryUtil.memCopy(ptr, adr, remSize.L)

val VkDeviceMemory.asHexString: String
    get() = "0x%X".format(L)
val VkBuffer.asHexString: String
    get() = "0x%X".format(L)
val VkDescriptorSetLayout.asHexString: String
    get() = "0x%X".format(L)
val VkDescriptorSet.asHexString: String
    get() = "0x%X".format(L)

fun vk.DeviceQueueCreateInfo(capacity: Int): VkDeviceQueueCreateInfo.Buffer =
    VkDeviceQueueCreateInfo.callocStack(capacity).apply {
        for (i in this)
            i.type = VkStructureType.DEVICE_QUEUE_CREATE_INFO
    }

infix fun VkPhysicalDevice.createDevice(createInfo: VkDeviceCreateInfo): Pair<VkDevice, VkResult> =
    stak {
        val adr = it.nmalloc(Pointer.POINTER_SIZE, Pointer.POINTER_SIZE)
        val res = VK10.nvkCreateDevice(this, createInfo.adr, NULL, adr)
        VkDevice(MemoryUtil.memGetAddress(adr), this, createInfo) to VkResult(res)
    }

val VkResult.description: String
    get() = when (this) {
        // Success Codes
        SUCCESS -> "Command successfully completed"
        NOT_READY -> "A fence or query has not yet completed"
        TIMEOUT -> "A wait operation has not completed in the specified time"
        EVENT_SET -> "An event is signaled"
        EVENT_RESET -> "An event is unsignaled"
        INCOMPLETE -> "A return array was too small for the result"
        SUBOPTIMAL_KHR -> "A swapchain no longer matches the surface properties exactly, but can still be used to present to the surface successfully"
        // Error codes
        ERROR_OUT_OF_HOST_MEMORY -> "A host memory allocation has failed"
        ERROR_OUT_OF_DEVICE_MEMORY -> "A device memory allocation has failed"
        ERROR_INITIALIZATION_FAILED -> "Initialization of an object could not be completed for implementation-specific reasons"
        ERROR_DEVICE_LOST -> "The logical or physical device has been lost. See Lost Device"
        ERROR_MEMORY_MAP_FAILED -> "Mapping of a memory object has failed"
        ERROR_LAYER_NOT_PRESENT -> "A requested layer is not present or could not be loaded"
        ERROR_EXTENSION_NOT_PRESENT -> "A requested extension is not supported"
        ERROR_FEATURE_NOT_PRESENT -> "A requested feature is not supported"
        ERROR_INCOMPATIBLE_DRIVER -> "The requested version of Vulkan is not supported by the driver or is otherwise incompatible for implementation-specific reasons"
        ERROR_TOO_MANY_OBJECTS -> "Too many objects of the type have already been created"
        ERROR_FORMAT_NOT_SUPPORTED -> "A requested format is not supported on this device"
        ERROR_FRAGMENTED_POOL -> "A pool allocation has failed due to fragmentation of the pool’s memory. This must only be returned if no attempt to allocate host or device memory was made to accomodate the new allocation. This should be returned in preference to VK_ERROR_OUT_OF_POOL_MEMORY, but only if the implementation is certain that the pool allocation failure was due to fragmentation"
        ERROR_SURFACE_LOST_KHR -> "A surface is no longer available"
        ERROR_NATIVE_WINDOW_IN_USE_KHR -> "The requested window is already in use by Vulkan or another API in a manner which prevents it from being used again"
        ERROR_OUT_OF_DATE_KHR -> "A surface has changed in such a way that it is no longer compatible with the swapchain, and further presentation requests using the swapchain will fail. Applications must query the new surface properties and recreate their swapchain if they wish to continue presenting to the surface"
        ERROR_INCOMPATIBLE_DISPLAY_KHR -> "The display used by a swapchain does not use the same presentable image layout, or is incompatible in a way that prevents sharing an image"
        ERROR_INVALID_SHADER_NV -> "One or more shaders failed to compile or link. More details are reported back to the application via ../../html/vkspec.html#VK_EXT_debug_report if enabled"
        ERROR_OUT_OF_POOL_MEMORY -> "A pool memory allocation has failed. This must only be returned if no attempt to allocate host or device memory was made to accomodate the new allocation. If the failure was definitely due to fragmentation of the pool, VK_ERROR_FRAGMENTED_POOL should be returned instead"
        ERROR_INVALID_EXTERNAL_HANDLE -> "An external handle is not a valid handle of the specified type"
        ERROR_FRAGMENTATION_EXT -> "A descriptor pool creation has failed due to fragmentation"
        else -> "Unknown VkResult type"
    }

fun vk.AttachmentDescription(size: Int, init: (Int) -> VkAttachmentDescription): VkAttachmentDescription.Buffer {
    val res = VkAttachmentDescription.callocStack(size)
    for (i in res.indices)
        res[i] = init(i)
    return res
}

inline val <SELF : CustomBuffer<SELF>>CustomBuffer<SELF>.cap
    get() = capacity()

inline var <SELF : CustomBuffer<SELF>>CustomBuffer<SELF>.lim
    get() = limit()
    set(value) {
        limit(value)
    }

inline val <SELF : CustomBuffer<SELF>>CustomBuffer<SELF>.rem
    get() = remaining()

inline val <SELF : CustomBuffer<SELF>>CustomBuffer<SELF>.indices: IntRange
    get() = IntRange(0, lastIndex)

inline val <SELF : CustomBuffer<SELF>>CustomBuffer<SELF>.lastIndex: Int
    get() = cap - 1


fun vk.ImageViewBuffer(size: Int, init: (Int) -> VkImageView) = VkImageViewBuffer(stackGet().LongBuffer(size) { init(it).L })
fun VkImageViewBuffer.free() = buffer.free()

fun LongBuffer(size: Int, init: (Int) -> Long): LongBuffer {
    val res = memCallocLong(size)
    for (i in 0 until size)
        res[i] = init(i)
    return res
}

fun MemoryStack.LongBuffer(size: Int, init: (Int) -> Long): LongBuffer {
    val res = mallocLong(size)
    for (i in 0 until size)
        res[i] = init(i)
    return res
}

inline fun VkPipelineInputAssemblyStateCreateInfo(block: VkPipelineInputAssemblyStateCreateInfo.() -> Unit): VkPipelineInputAssemblyStateCreateInfo =
    VkPipelineInputAssemblyStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
        block()
    }

fun VkPipelineInputAssemblyStateCreateInfo(topology: VkPrimitiveTopology,
                                           flags: VkPipelineInputAssemblyStateCreateFlags = 0,
                                           primitiveRestartEnable: Boolean = false): VkPipelineInputAssemblyStateCreateInfo =
    VkPipelineInputAssemblyStateCreateInfo {
        this.topology = topology
        this.flags = flags
        this.primitiveRestartEnable = primitiveRestartEnable
    }

inline fun VkPipelineRasterizationStateCreateInfo(block: VkPipelineRasterizationStateCreateInfo.() -> Unit): VkPipelineRasterizationStateCreateInfo =
    VkPipelineRasterizationStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_RASTERIZATION_STATE_CREATE_INFO
        block()
    }

fun VkPipelineColorBlendAttachmentState(): VkPipelineColorBlendAttachmentState = VkPipelineColorBlendAttachmentState.calloc()
fun VkPipelineColorBlendAttachmentState(capacity: Int): VkPipelineColorBlendAttachmentState.Buffer = VkPipelineColorBlendAttachmentState.calloc(capacity)
inline fun VkPipelineColorBlendAttachmentState(block: VkPipelineColorBlendAttachmentState.() -> Unit): VkPipelineColorBlendAttachmentState = VkPipelineColorBlendAttachmentState().also(block)

inline fun VkPipelineViewportStateCreateInfo(block: VkPipelineViewportStateCreateInfo.() -> Unit): VkPipelineViewportStateCreateInfo =
    VkPipelineViewportStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_VIEWPORT_STATE_CREATE_INFO
        block()
    }

inline fun VkPipelineColorBlendStateCreateInfo(block: VkPipelineColorBlendStateCreateInfo.() -> Unit): VkPipelineColorBlendStateCreateInfo =
    VkPipelineColorBlendStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
        block()
    }

fun VkPipelineDynamicStateCreateInfo(dynamicStates: Collection<VkDynamicState>, flags: VkPipelineDynamicStateCreateFlags = 0): VkPipelineDynamicStateCreateInfo =
    VkPipelineDynamicStateCreateInfo {
        this.dynamicStates = vkDynamicStateBufferOf(dynamicStates)
        this.flags = flags
    }

fun VkPipelineDynamicStateCreateInfo(dynamicStates: VkDynamicStateBuffer, flags: VkPipelineDynamicStateCreateFlags = 0): VkPipelineDynamicStateCreateInfo =
    VkPipelineDynamicStateCreateInfo {
        this.dynamicStates = dynamicStates
        this.flags = flags
    }

inline fun VkPipelineDynamicStateCreateInfo(block: VkPipelineDynamicStateCreateInfo.() -> Unit): VkPipelineDynamicStateCreateInfo =
    VkPipelineDynamicStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_DYNAMIC_STATE_CREATE_INFO
        block()
    }

fun vkDynamicStateBufferOf(dynamicStates: Collection<VkDynamicState>): VkDynamicStateBuffer {
    val buffer = VkDynamicStateBuffer(dynamicStates.size)
    for (i in dynamicStates.indices)
        buffer[i] = dynamicStates.elementAt(i)
    return buffer
}

fun vkDynamicStateBufferOf(vararg dynamicStates: VkDynamicState): VkDynamicStateBuffer {
    val buffer = VkDynamicStateBuffer(dynamicStates.size)
    for (i in dynamicStates.indices)
        buffer[i] = dynamicStates.elementAt(i)
    return buffer
}

fun VkDynamicStateBuffer(size: Int) = VkDynamicStateBuffer(memCallocInt(size))

fun VkDynamicStateBuffer.free() = buffer.free()

fun VkPipelineDepthStencilStateCreateInfo(): VkPipelineDepthStencilStateCreateInfo =
    VkPipelineDepthStencilStateCreateInfo.calloc().apply { type = VkStructureType.PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO }

inline fun VkPipelineDepthStencilStateCreateInfo(block: VkPipelineDepthStencilStateCreateInfo.() -> Unit): VkPipelineDepthStencilStateCreateInfo =
    VkPipelineDepthStencilStateCreateInfo().also(block)

inline fun VkPipelineMultisampleStateCreateInfo(block: VkPipelineMultisampleStateCreateInfo.() -> Unit): VkPipelineMultisampleStateCreateInfo =
    VkPipelineMultisampleStateCreateInfo.calloc().apply {
        type = VkStructureType.PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
        block()
    }

fun VkPipelineShaderStageCreateInfo(capacity: Int): VkPipelineShaderStageCreateInfo.Buffer =
    VkPipelineShaderStageCreateInfo.calloc(capacity).also {
        for (info in it)
            info.type = VkStructureType.PIPELINE_SHADER_STAGE_CREATE_INFO
    }

inline class VkDescriptorSetLayoutBuffer(val buffer: LongBuffer)

fun VkDescriptorSetLayoutBuffer(size: Int, init: (Int) -> VkDescriptorSetLayout) = VkDescriptorSetLayoutBuffer(LongBuffer(size) { init(it).L })

fun VkDescriptorSetLayoutBuffer(collection: Collection<VkDescriptorSetLayout>) = VkDescriptorSetLayoutBuffer(collection.size) { collection.elementAt(it) }

fun VkImageArray(size: Int, init: (Int) -> VkImage): VkImageArray = VkImageArray(LongArray(size) { init(it).L })

fun VkSubmitInfo() = VkSubmitInfo.calloc().apply { type = VkStructureType.SUBMIT_INFO }

val VkQueryPool.isValid get() = L != NULL
val VkImage.isValid get() = L != NULL

//fun VkDevice.destroyQueryPool(queryPool: VkQueryPool) = VK10.nvkDestroyQueryPool(this, queryPool.L, NULL) TODO infix

infix fun VkDevice.createQueryPool(createInfo: VkQueryPoolCreateInfo): VkQueryPool = VkQueryPool(stak.longAddress { VK10.nvkCreateQueryPool(this, createInfo.adr, NULL, it) })

fun MemoryStack.reset() {
    val size = Configuration.STACK_SIZE.get(64) * 1024
    pointer = size
}

fun VkSemaphoreCreateInfo() = VkSemaphoreCreateInfo.calloc().apply { type = VkStructureType.SEMAPHORE_CREATE_INFO }

fun VkPipelineVertexInputStateCreateInfo(): VkPipelineVertexInputStateCreateInfo = VkPipelineVertexInputStateCreateInfo.calloc().apply { type = VkStructureType.PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO }
fun VkPipelineVertexInputStateCreateInfo(capacity: Int): VkPipelineVertexInputStateCreateInfo.Buffer = VkPipelineVertexInputStateCreateInfo.calloc(capacity).also {
    for (i in it)
        i.type = VkStructureType.PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
}

fun VkVertexInputAttributeDescription(): VkVertexInputAttributeDescription = VkVertexInputAttributeDescription.calloc()
fun VkVertexInputAttributeDescription(capacity: Int): VkVertexInputAttributeDescription.Buffer = VkVertexInputAttributeDescription.calloc(capacity)

fun VkVertexInputBindingDescription(): VkVertexInputBindingDescription = VkVertexInputBindingDescription.calloc()
fun VkVertexInputBindingDescription(capacity: Int): VkVertexInputBindingDescription.Buffer = VkVertexInputBindingDescription.calloc(capacity)

fun VkDescriptorSetLayoutBinding(): VkDescriptorSetLayoutBinding = VkDescriptorSetLayoutBinding.calloc()
fun VkDescriptorSetLayoutBinding(capacity: Int): VkDescriptorSetLayoutBinding.Buffer = VkDescriptorSetLayoutBinding.calloc(capacity)

fun VkClearValue(capacity: Int): VkClearValue.Buffer = VkClearValue.calloc(capacity)

fun VkRenderPassBeginInfo(): VkRenderPassBeginInfo = VkRenderPassBeginInfo.calloc().apply { type = VkStructureType.RENDER_PASS_BEGIN_INFO }
//inline fun VkRenderPassBeginInfo(block: VkRenderPassBeginInfo.() -> Unit): VkRenderPassBeginInfo = RenderPassBeginInfo().also(block)

fun VkRect2D(): VkRect2D = VkRect2D.calloc()
fun VkViewport(): VkViewport = VkViewport.calloc()

fun IntBuffer.fill(value: Int) = fill(0, lastIndex, value)
fun IntBuffer.fill(begin: Int, end: Int, value: Int) {
    for (i in begin .. end)
        set(0, value)
}
