@file:OptIn(ExperimentalStdlibApi::class)

package graphics.scenery.backends.vulkan

import graphics.scenery.textures.Texture
import graphics.scenery.textures.Texture.BorderColor
import graphics.scenery.textures.UpdatableTexture.TextureExtents
import graphics.scenery.textures.Texture.RepeatMode
import graphics.scenery.textures.UpdatableTexture
import graphics.scenery.textures.UpdatableTexture.TextureUpdate
import graphics.scenery.utils.Image
import graphics.scenery.utils.lazyLogger
import kotlinx.coroutines.*
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkImageCreateInfo
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.io.path.readLines
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Vulkan Texture class. Creates a texture on the [device], with [width]x[height]x[depth],
 * of [format], with a given number of [mipLevels]. Filtering can be set via
 * [minFilterLinear] and [maxFilterLinear]. Needs to be supplied with a [queue] to execute
 * generic operations on, and a [transferQueue] for transfer operations. Both are allowed to
 * be the same.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanTexture(val device: VulkanDevice,
                    val commandPools: VulkanRenderer.CommandPools, val queue: VulkanDevice.QueueWithMutex, val transferQueue: VulkanDevice.QueueWithMutex,
                    val width: Int, val height: Int, val depth: Int = 1,
                    val format: Int = VK_FORMAT_R8G8B8_SRGB, var mipLevels: Int = 1,
                    val minFilterLinear: Boolean = true, val maxFilterLinear: Boolean = true,
                    val usage: HashSet<Texture.UsageType> = hashSetOf(Texture.UsageType.Texture)) : AutoCloseable {
    protected val logger by lazyLogger()

    private var initialised: Boolean = false

    /** The Vulkan image associated with this texture. */
    var image: VulkanImage
        protected set

    private var stagingImage: VulkanImage
    private var gt: Texture? = null

    /**
     * Wrapper class for holding on to raw Vulkan [image]s backed by [memory].
     */
    inner class VulkanImage(var image: Long = -1L, var memory: Long = -1L, val maxSize: Long = -1L) {

        /** Raw Vulkan sampler. */
        var sampler: Long = -1L
            internal set
        /** Raw Vulkan view. */
        var view: Long = -1L
            internal set

        /**
         * Copies the content of the image from [buffer]. This gets executed
         * within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, buffer: VulkanBuffer, update: TextureUpdate? = null, bufferOffset: Long = 0) {
            with(commandBuffer) {
                val bufferImageCopy = VkBufferImageCopy.calloc(1)

                bufferImageCopy.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
                bufferImageCopy.bufferOffset(bufferOffset)

                if(update != null) {
                    bufferImageCopy.imageExtent().set(update.extents.w, update.extents.h, update.extents.d)
                    bufferImageCopy.imageOffset().set(update.extents.x, update.extents.y, update.extents.z)
                } else {
                    bufferImageCopy.imageExtent().set(width, height, depth)
                    bufferImageCopy.imageOffset().set(0, 0, 0)
                }

                vkCmdCopyBufferToImage(this,
                    buffer.vulkanBuffer,
                    this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    bufferImageCopy)

                bufferImageCopy.free()
            }

            update?.let {
                it.consumed = true
            }
        }

        /**
         * Copies the content of the image to [buffer] from a series of [updates]. This gets executed
         * within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, buffer: VulkanBuffer, updates: List<TextureUpdate>, bufferOffset: Long = 0) {
            logger.debug("Got {} texture updates for {}", updates.size, this)
            with(commandBuffer) {
                val bufferImageCopy = VkBufferImageCopy.calloc(1)
                var offset = bufferOffset

                updates.forEach { update ->
                    val updateSize = update.contents.remaining()
                    bufferImageCopy.imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)
                    bufferImageCopy.bufferOffset(offset)

                    bufferImageCopy.imageExtent().set(update.extents.w, update.extents.h, update.extents.d)
                    bufferImageCopy.imageOffset().set(update.extents.x, update.extents.y, update.extents.z)

                    vkCmdCopyBufferToImage(this,
                        buffer.vulkanBuffer,
                        this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        bufferImageCopy)

                    offset += updateSize
                    update.consumed = true
                }

                bufferImageCopy.free()
            }
        }

        /**
         * Copies the content of the image from a given [VulkanImage], [image].
         * This gets executed within a given [commandBuffer].
         */
        fun copyFrom(commandBuffer: VkCommandBuffer, image: VulkanImage, extents: TextureExtents? = null) {
            with(commandBuffer) {
                val subresource = VkImageSubresourceLayers.calloc()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseArrayLayer(0)
                    .mipLevel(0)
                    .layerCount(1)

                val region = VkImageCopy.calloc(1)
                    .srcSubresource(subresource)
                    .dstSubresource(subresource)

                if(extents != null) {
                    region.srcOffset().set(extents.x, extents.y, extents.z)
                    region.dstOffset().set(extents.x, extents.y, extents.z)
                    region.extent().set(extents.w, extents.h, extents.d)
                } else {
                    region.srcOffset().set(0, 0, 0)
                    region.dstOffset().set(0, 0, 0)
                    region.extent().set(width, height, depth)
                }

                vkCmdCopyImage(this,
                    image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    this@VulkanImage.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region)

                subresource.free()
                region.free()
            }
        }

        override fun toString(): String {
            return "VulkanImage (${this.image.toHexString()}, ${width}x${height}x${depth}, format=${format.formatToString()}, maxSize=${this.maxSize})"
        }
    }

    init {
        stagingImage = if(depth == 1) {
            createImage(width, height, depth,
                format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_IMAGE_TILING_LINEAR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                mipLevels = 1
            )
        } else {
            createImage(16, 16, 1,
                format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_IMAGE_TILING_LINEAR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                mipLevels = 1)
        }

        var usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        if(device.formatFeatureSupported(format, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, optimalTiling = true)) {
            usage = usage or VK_IMAGE_USAGE_STORAGE_BIT
        }

        image = createImage(width, height, depth,
            format, usage,
            VK_IMAGE_TILING_OPTIMAL, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            mipLevels)

        if (image.sampler == -1L) {
            image.sampler = createSampler()
        }

        if (image.view == -1L) {
            image.view = createImageView(image, format)
        }

        gt?.let { cache.put(it, this) }
    }

    /**
     * Alternative constructor to create a [VulkanTexture] from a [Texture].
     */
    constructor(
        device: VulkanDevice,
        commandPools: VulkanRenderer.CommandPools, queue: VulkanDevice.QueueWithMutex, transferQueue: VulkanDevice.QueueWithMutex,
        texture: Texture, mipLevels: Int = 1) : this(device,
        commandPools,
        queue,
        transferQueue,
        texture.dimensions.x(),
        texture.dimensions.y(),
        texture.dimensions.z(),
        texture.toVulkanFormat(),
        mipLevels, texture.minFilter == Texture.FilteringMode.Linear, texture.maxFilter == Texture.FilteringMode.Linear, usage = texture.usageType) {
        gt = texture
        gt?.let { cache.put(it, this) }
    }

    /**
     * Creates a Vulkan image of [format] with a given [width], [height], and [depth].
     * [usage] and [memoryFlags] need to be given, as well as the [tiling] parameter and number of [mipLevels].
     * A custom memory allocator may be used and given as [customAllocator].
     */
    fun createImage(width: Int, height: Int, depth: Int, format: Int,
                    usage: Int, tiling: Int, memoryFlags: Int, mipLevels: Int,
                    initialLayout: Int? = null,
                    customAllocator: ((VkMemoryRequirements, Long) -> Long)? = null, imageCreateInfo: VkImageCreateInfo? = null): VulkanImage {
        val imageInfo = if(imageCreateInfo != null) {
            imageCreateInfo
        } else {
            val i = VkImageCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(if (depth == 1) {
                    VK_IMAGE_TYPE_2D
                } else {
                    VK_IMAGE_TYPE_3D
                })
                .mipLevels(mipLevels)
                .arrayLayers(1)
                .format(format)
                .tiling(tiling)
                .initialLayout(if(depth == 1) {VK_IMAGE_LAYOUT_PREINITIALIZED} else { VK_IMAGE_LAYOUT_UNDEFINED })
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)

            i.extent().set(width, height, depth)
            i
        }

        if(initialLayout != null) {
            imageInfo.initialLayout(initialLayout)
        }

        val image = VU.getLong("create staging image",
            { vkCreateImage(device.vulkanDevice, imageInfo, null, this) }, {})

        val reqs = VkMemoryRequirements.calloc()
        vkGetImageMemoryRequirements(device.vulkanDevice, image, reqs)
        val memorySize = reqs.size()

        val memory = if(customAllocator == null) {
            val allocInfo = VkMemoryAllocateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(NULL)
                .allocationSize(memorySize)
                .memoryTypeIndex(device.getMemoryType(reqs.memoryTypeBits(), memoryFlags).first())

            VU.getLong("allocate image staging memory of size $memorySize",
                { vkAllocateMemory(device.vulkanDevice, allocInfo, null, this) },
                { imageInfo.free(); allocInfo.free() })
        } else {
            customAllocator.invoke(reqs, image)
        }

        reqs.free()

        vkBindImageMemory(device.vulkanDevice, image, memory, 0)

        return VulkanImage(image, memory, memorySize)
    }

    var tmpBuffer: VulkanBuffer? = null


    fun CoroutineScope.launchPeriodicAsync(
        repeatMillis: Long,
        action: () -> Boolean
    ) = this.async {
        if (repeatMillis > 0) {
            while (isActive) {
                val result = action()
                if(result) {
                    break
                }
                delay(repeatMillis)
            }
        } else {
            action()
        }
    }

    /**
     * Copies the data for this texture from a [ByteBuffer], [data].
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun copyFrom(data: ByteBuffer): VulkanTexture {
        if (depth == 1 && data.remaining() > stagingImage.maxSize) {
            logger.warn("Allocated image size for $this (${stagingImage.maxSize}) less than copy source size ${data.remaining()}.")
            return this
        }

        var deallocate = false
        var sourceBuffer = data

        gt?.let { gt ->
            if (gt.channels == 3) {
                logger.debug("Loading RGB texture, padding channels to 4 to fit RGBA")
                val channelBytes = when (gt.type) {
                    is UnsignedByteType -> 1
                    is ByteType -> 1
                    is UnsignedShortType -> 2
                    is ShortType -> 2
                    is UnsignedIntType -> 4
                    is IntType -> 4
                    is FloatType -> 4
                    is DoubleType -> 8
                    else -> throw UnsupportedOperationException("Don't know how to handle textures of type ${gt.type.javaClass.simpleName}")
                }

                val storage = memAlloc(data.remaining() / 3 * 4)
                val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val tmp = ByteArray(channelBytes * 3)
                val alpha = when(gt.type) {
                    is UnsignedByteType -> ubyteArrayOf(0xffu)
                    is ByteType -> ubyteArrayOf(0xffu)
                    is UnsignedShortType -> ubyteArrayOf(0xffu, 0xffu)
                    is ShortType -> ubyteArrayOf(0xffu, 0xffu)
                    is UnsignedIntType -> ubyteArrayOf(0x3fu, 0x80u, 0x00u, 0x00u)
                    is IntType -> ubyteArrayOf(0xffu, 0xffu, 0x00u, 0x00u)
                    is FloatType -> ubyteArrayOf(0x3fu, 0x80u, 0x00u, 0x00u)
                    is DoubleType -> ubyteArrayOf(0x3fu, 0xf0u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)
                    else -> throw UnsupportedOperationException("Don't know how to handle textures of type ${gt.type.javaClass.simpleName}")
                }

                // pad buffer to 4 channels
                while (view.hasRemaining()) {
                    view.get(tmp, 0, tmp.size)
                    storage.put(tmp)
                    storage.put(alpha.toByteArray())
                }

                storage.flip()
                deallocate = true
                sourceBuffer = storage
            } else {
                deallocate = false
                sourceBuffer = data
            }
        }

        logger.debug("Updating {} with {} miplevels", this, mipLevels)
        if (mipLevels == 1) {
            val block = !(gt?.usageType?.contains(Texture.UsageType.AsyncLoad) ?: false)

            // acquire GPU upload mutex
            gt?.gpuMutex?.acquire()

            val t = thread {
                with(VU.newCommandBuffer(device, commandPools.Transfer, autostart = true)) {
                    val fence = if(!block) {
                        val f = this@VulkanTexture.device.createFence()

                        CoroutineScope(Dispatchers.Default).launchPeriodicAsync(50) {
                            val done = vkGetFenceStatus(this@VulkanTexture.device.vulkanDevice, f) == VK_SUCCESS
                            logger.info("Upload done: $done")

                            if(done) {
                                this@VulkanTexture.device.destroyFence(f)
                                // release GPU upload mutex
                                gt?.gpuMutex?.release()

                                // mark as uploaded
                                gt?.state?.add(Texture.TextureState.Uploaded)
                                gt?.state?.add(Texture.TextureState.AvailableForUse)

//                                vkFreeCommandBuffers(device, commandPools.Transfer, this)
                            }

                            done
                        }

                        f
                    } else {
                        null
                    }

                    if (!initialised) {
                        transitionLayout(
                            stagingImage.image,
                            VK_IMAGE_LAYOUT_PREINITIALIZED,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mipLevels,
                            srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            commandBuffer = this
                        )
                    }

                    if (depth == 1) {
                        val dest = memAllocPointer(1)
                        gt?.mutex?.acquire()
                        vkMapMemory(device, stagingImage.memory, 0, sourceBuffer.remaining() * 1L, 0, dest)
                        memCopy(memAddress(sourceBuffer), dest.get(0), sourceBuffer.remaining().toLong())
                        vkUnmapMemory(device, stagingImage.memory)
                        memFree(dest)
                        gt?.mutex?.release()

                        transitionLayout(
                            image.image,
                            VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mipLevels,
                            srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            commandBuffer = this
                        )


                        image.copyFrom(this, stagingImage)

                        transitionLayout(
                            image.image,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, mipLevels,
                            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            commandBuffer = this
                        )

                    } else {
                        val genericTexture = gt
                        // block only if the texture has no async load flag
                        val requiredCapacity =
                            if (genericTexture is UpdatableTexture && genericTexture.hasConsumableUpdates()) {
                                genericTexture.getConsumableUpdates().map { it.contents.remaining() }.sum().toLong()
                            } else {
                                sourceBuffer.capacity().toLong()
                            }

                        logger.debug(
                            "{} has {} consumeable updates",
                            this@VulkanTexture,
                            (genericTexture as? UpdatableTexture)?.getConsumableUpdates()?.size
                        )

                        if(tmpBuffer == null || (tmpBuffer?.size ?: 0) < requiredCapacity) {
                            logger.debug(
                                "({}) Reallocating tmp buffer, old size={} new size = {} MiB",
                                this@VulkanTexture,
                                tmpBuffer?.size,
                                requiredCapacity.toFloat()/1024.0f/1024.0f
                            )
                            tmpBuffer?.close()
                            // reserve a bit more space if the texture is small, to avoid reallocations
                            val reservedSize = if(requiredCapacity < 1024*1024*8) {
                                (requiredCapacity * 1.33).roundToLong()
                            } else {
                                requiredCapacity
                            }

                            tmpBuffer = VulkanBuffer(this@VulkanTexture.device,
                                                     max(reservedSize, 1024*1024),
                                                     VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     wantAligned = false)
                        }

                        tmpBuffer?.let { buffer ->
                            transitionLayout(
                                image.image,
                                VK_IMAGE_LAYOUT_UNDEFINED,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mipLevels,
                                srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                                commandBuffer = this
                            )

                            if (genericTexture is UpdatableTexture) {
                                if (genericTexture.hasConsumableUpdates()) {
                                    val contents = genericTexture.getConsumableUpdates().map { it.contents }

                                    genericTexture.mutex.acquire()
                                    buffer.copyFrom(contents, keepMapped = true)
                                    image.copyFrom(this, buffer, genericTexture.getConsumableUpdates())
                                    genericTexture.mutex.release()
                                } /*else {
                                // TODO: Semantics, do we want UpdateableTextures to be only
                                // updateable via updates, or shall they read from buffer on first init?
                                buffer.copyFrom(sourceBuffer)
                                image.copyFrom(this, buffer)
                            }*/
                            } else {
                                genericTexture?.mutex?.acquire()
                                buffer.copyFrom(sourceBuffer)
                                image.copyFrom(this, buffer)
                                genericTexture?.mutex?.release()
                            }

                            transitionLayout(
                                image.image,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, mipLevels,
                                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                commandBuffer = this
                            )
                        }

                    }

                    endCommandBuffer(
                        this@VulkanTexture.device,
                        commandPools.Transfer,
                        transferQueue,
                        flush = true,
                        dealloc = !block,
                        block = block,
                        fence = fence
                    )
                }
            }

            if(block) {
                t.join()
                gt?.gpuMutex?.release()
            }
            // necessary to clear updates here, as the command buffer might still access the
            // memory address of the texture update.
            (gt as? UpdatableTexture)?.clearConsumedUpdates()
        } else {
            val buffer = VulkanBuffer(device,
                sourceBuffer.limit().toLong(),
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                wantAligned = false)

            with(VU.newCommandBuffer(device, commandPools.Transfer, autostart = true)) {
                buffer.copyFrom(sourceBuffer)

                transitionLayout(image.image, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, commandBuffer = this,
                    srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)
                image.copyFrom(this, buffer)
                transitionLayout(image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, commandBuffer = this,
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)

                endCommandBuffer(
                    this@VulkanTexture.device,
                    commandPools.Transfer,
                    transferQueue,
                    flush = true,
                    dealloc = true,
                    block = true
                )
            }

            val imageBlit = VkImageBlit.calloc(1)
            with(VU.newCommandBuffer(device, commandPools.Standard, autostart = true)) mipmapCreation@{

                for (mipLevel in 1 until mipLevels) {
                    imageBlit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLevel - 1, 0, 1)
                    imageBlit.srcOffsets(1).set(width shr (mipLevel - 1), height shr (mipLevel - 1), 1)

                    val dstWidth = width shr mipLevel
                    val dstHeight = height shr mipLevel

                    if (dstWidth < 2 || dstHeight < 2) {
                        break
                    }

                    imageBlit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLevel, 0, 1)
                    imageBlit.dstOffsets(1).set(width shr (mipLevel), height shr (mipLevel), 1)

                    val mipSourceRange = VkImageSubresourceRange.calloc()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseArrayLayer(0)
                        .layerCount(1)
                        .baseMipLevel(mipLevel - 1)
                        .levelCount(1)

                    val mipTargetRange = VkImageSubresourceRange.calloc()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseArrayLayer(0)
                        .layerCount(1)
                        .baseMipLevel(mipLevel)
                        .levelCount(1)

                    if (mipLevel > 1) {
                        transitionLayout(image.image,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            subresourceRange = mipSourceRange,
                            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                            commandBuffer = this@mipmapCreation)
                    }

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        subresourceRange = mipTargetRange,
                        srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        commandBuffer = this@mipmapCreation)

                    vkCmdBlitImage(this@mipmapCreation,
                        image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        imageBlit, VK_FILTER_LINEAR)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, subresourceRange = mipSourceRange,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        commandBuffer = this@mipmapCreation)

                    transitionLayout(image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, subresourceRange = mipTargetRange,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT,
                        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        commandBuffer = this@mipmapCreation)

                    mipSourceRange.free()
                    mipTargetRange.free()
                }

                this@mipmapCreation.endCommandBuffer(
                    this@VulkanTexture.device,
                    commandPools.Standard,
                    queue,
                    flush = true,
                    dealloc = true,
                    block = true
                )
            }

            imageBlit.free()
            buffer.close()
        }

        // deallocate in case we moved pixels around
        if (deallocate) {
            memFree(sourceBuffer)
        }

//        image.view = createImageView(image, format)

        initialised = true
        return this
    }

    /**
     * Copies the first layer, first mipmap of the texture to [buffer].
     */
    fun copyTo(buffer: ByteBuffer, inPlace: Boolean = false): ByteBuffer? {
        stackPush().use { stack ->
            val memoryProperties = (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                    or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    or VK_MEMORY_PROPERTY_HOST_CACHED_BIT)

            if (tmpBuffer == null
                || (tmpBuffer?.size!! < image.maxSize)
                || (tmpBuffer?.requestedMemoryProperties != memoryProperties)) {
                tmpBuffer?.close()
                logger.debug("Reallocating temporary buffer")
                tmpBuffer = VulkanBuffer(this@VulkanTexture.device,
                    image.maxSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    memoryProperties,
                    wantAligned = true)
            }

            tmpBuffer?.let { b ->
                with(VU.newCommandBuffer(device, commandPools.Transfer, autostart = true)) {
                    logger.info("${System.nanoTime()}: Copying $width $height $depth")
                    transitionLayout(image.image,
                        from = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        to = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        dstStage = VK_PIPELINE_STAGE_HOST_BIT,
                        dstAccessMask = VK_ACCESS_HOST_READ_BIT,
                        commandBuffer = this)

                    val type = VK_IMAGE_ASPECT_COLOR_BIT

                    val subresource = VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(type)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    val regions = VkBufferImageCopy.calloc(1, stack)
                        .bufferRowLength(0)
                        .bufferImageHeight(0)
                        .imageOffset(VkOffset3D.calloc(stack).set(0, 0, 0))
                        .imageExtent(VkExtent3D.calloc(stack).set(width, height, depth))
                        .imageSubresource(subresource)

                    vkCmdCopyImageToBuffer(
                        this,
                        image.image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        b.vulkanBuffer,
                        regions
                    )

                    transitionLayout(image.image,
                        from = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        to = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcStage = VK_PIPELINE_STAGE_HOST_BIT,
                        srcAccessMask = VK_ACCESS_HOST_READ_BIT,
                        dstStage = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                        dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                        commandBuffer = this)

                    val barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    barrier
                        .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .buffer(b.vulkanBuffer)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                        .size(VK_WHOLE_SIZE)

                    vkCmdPipelineBarrier(
                        this,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        0,
                        null,
                        barrier,
                        null
                    )

                    endCommandBuffer(
                        this@VulkanTexture.device,
                        commandPools.Transfer,
                        transferQueue,
                        flush = true,
                        dealloc = true,
                        block = true
                    )
                }

                val result = if(!inPlace) {
                    b.copyTo(buffer)
                    buffer
                } else {
//                    val p = b.mapIfUnmapped()
                    b.unmap()
                    val p = b.map()
                    logger.info("${System.nanoTime()}: Buffer has ${buffer.remaining()}")
                    memByteBuffer(p.get(0), buffer.remaining())
                }

                return result
            }
        }

        return null
    }

    /**
     * Creates a Vulkan image view with [format] for an [image].
     */
    fun createImageView(image: VulkanImage, format: Int): Long {
        if(image.view != -1L) {
            vkDestroyImageView(device.vulkanDevice, image.view, null)
        }

        val subresourceRange = VkImageSubresourceRange.calloc().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, mipLevels, 0, 1)

        val vi = VkImageViewCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .pNext(NULL)
            .image(image.image)
            .viewType(if (depth > 1) {
                VK_IMAGE_VIEW_TYPE_3D
            } else {
                VK_IMAGE_VIEW_TYPE_2D
            })
            .format(format)
            .subresourceRange(subresourceRange)

        if(gt?.channels == 1 && depth > 1) {
            vi.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R)
        }

        return VU.getLong("Creating image view",
            { vkCreateImageView(device.vulkanDevice, vi, null, this) },
            { vi.free(); subresourceRange.free(); })
    }

    private fun RepeatMode.toVulkan(): Int {
        return when(this) {
            RepeatMode.Repeat -> VK_SAMPLER_ADDRESS_MODE_REPEAT
            RepeatMode.MirroredRepeat -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
            RepeatMode.ClampToEdge -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
            RepeatMode.ClampToBorder -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
        }
    }

    private fun BorderColor.toVulkan(type: NumericType<*>): Int {
        var color = when(this) {
            BorderColor.TransparentBlack -> VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
            BorderColor.OpaqueBlack -> VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK
            BorderColor.OpaqueWhite -> VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE
        }

        if(type !is FloatType) {
            color += 1
        }

        return color
    }

    /**
     * Creates a default sampler for this texture.
     */
    fun createSampler(texture: Texture? = null): Long {
        val t = texture ?: gt

        val (repeatS, repeatT, repeatU) = if(t != null) {
            Triple(
                t.repeatUVW.first.toVulkan(),
                t.repeatUVW.second.toVulkan(),
                t.repeatUVW.third.toVulkan()
            )
        } else {
            if(depth == 1) {
                Triple(VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_REPEAT)
            } else {
                Triple(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            }
        }

        val samplerInfo = VkSamplerCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .pNext(NULL)
            .magFilter(if(minFilterLinear) { VK_FILTER_LINEAR } else { VK_FILTER_NEAREST })
            .minFilter(if(maxFilterLinear) { VK_FILTER_LINEAR } else { VK_FILTER_NEAREST })
            .mipmapMode(if(depth == 1) { VK_SAMPLER_MIPMAP_MODE_LINEAR } else { VK_SAMPLER_MIPMAP_MODE_NEAREST })
            .addressModeU(repeatS)
            .addressModeV(repeatT)
            .addressModeW(repeatU)
            .mipLodBias(0.0f)
            .anisotropyEnable(depth == 1)
            .maxAnisotropy(if(depth == 1) { 8.0f } else { 1.0f })
            .minLod(0.0f)
            .maxLod(if(depth == 1) {mipLevels * 1.0f} else { 0.0f })
            .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
            .compareOp(VK_COMPARE_OP_NEVER)

        if(t != null) {
            samplerInfo.borderColor(t.borderColor.toVulkan(t.type))
        }

        val sampler = VU.getLong("creating sampler",
            { vkCreateSampler(device.vulkanDevice, samplerInfo, null, this) },
            { samplerInfo.free() })

        logger.debug("Created sampler {}", sampler.toHexString().lowercase())
        val oldSampler = image.sampler
        image.sampler = sampler
        if(oldSampler != -1L) {
            vkDestroySampler(device.vulkanDevice, oldSampler, null)
        }
        return sampler
    }

    override fun toString(): String {
        return "VulkanTexture on $device (${this.image.image.toHexString()}, ${width}x${height}x$depth, format=${this.format.formatToString()}, mipLevels=${mipLevels}, gt=${this.gt != null} minFilter=${this.minFilterLinear} maxFilter=${this.maxFilterLinear})"
    }

    /**
     * Deallocates and destroys this [VulkanTexture] instance, freeing all memory
     * related to it.
     */
    override fun close() {
        gt?.let { cache.remove(it) }

        if (image.view != -1L) {
            vkDestroyImageView(device.vulkanDevice, image.view, null)
            image.view = -1L
        }

        if (image.image != -1L) {
            vkDestroyImage(device.vulkanDevice, image.image, null)
            image.image = -1L
        }

        if (image.sampler != -1L) {
            vkDestroySampler(device.vulkanDevice, image.sampler, null)
            image.sampler = -1L
        }

        if (image.memory != -1L) {
            vkFreeMemory(device.vulkanDevice, image.memory, null)
            image.memory = -1L
        }

        if (stagingImage.image != -1L) {
            vkDestroyImage(device.vulkanDevice, stagingImage.image, null)
            stagingImage.image = -1L
        }

        if (stagingImage.memory != -1L) {
            vkFreeMemory(device.vulkanDevice, stagingImage.memory, null)
            stagingImage.memory = -1L
        }

        tmpBuffer?.close()
    }


    /**
     * Utility methods for [VulkanTexture].
     */
    companion object {
        @JvmStatic private val logger by lazyLogger()

        private val cache = HashMap<Texture, VulkanTexture>()

        fun getReference(texture: Texture): VulkanTexture? {
            return cache.get(texture)
        }

        /**
         * Loads a texture from a file given by [filename], and allocates the [VulkanTexture] on [device].
         */
        fun loadFromFile(device: VulkanDevice,
                         commandPools: VulkanRenderer.CommandPools , queue: VulkanDevice.QueueWithMutex, transferQueue: VulkanDevice.QueueWithMutex,
                         filename: String,
                         linearMin: Boolean, linearMax: Boolean,
                         generateMipmaps: Boolean = true): VulkanTexture {
            val stream = FileInputStream(filename)
            val type = filename.substringAfterLast('.')


            logger.debug("Loading${if(generateMipmaps) { " mipmapped" } else { "" }} texture from $filename")

            return if(type == "raw") {
                val path = Paths.get(filename)
                val infoFile = path.resolveSibling(path.fileName.toString().substringBeforeLast(".") + ".info")
                val dimensions = infoFile.readLines().first().split(",").map { it.toLong() }.toLongArray()

                loadFromFileRaw(device,
                        commandPools, queue, transferQueue,
                        stream, type, dimensions)
            } else {
                loadFromFile(device,
                        commandPools, queue, transferQueue,
                        stream, type, linearMin, linearMax, generateMipmaps)
            }
        }

        /**
         * Loads a texture from a file given by a [stream], and allocates the [VulkanTexture] on [device].
         */
        fun loadFromFile(device: VulkanDevice,
                         commandPools: VulkanRenderer.CommandPools, queue: VulkanDevice.QueueWithMutex, transferQueue: VulkanDevice.QueueWithMutex,
                         stream: InputStream, type: String,
                         linearMin: Boolean, linearMax: Boolean,
                         generateMipmaps: Boolean = true): VulkanTexture {
            val image = Image.fromStream(stream, type, true)

            var texWidth = 2
            var texHeight = 2
            var levelsW = 1
            var levelsH = 1

            while (texWidth < image.width) {
                texWidth *= 2
                levelsW++
            }
            while (texHeight < image.height) {
                texHeight *= 2
                levelsH++
            }

            val mipmapLevels = if(generateMipmaps) {
                Math.min(levelsW, levelsH)
            } else {
                1
            }

            val tex = VulkanTexture(
                device,
                commandPools, queue, transferQueue,
                texWidth, texHeight, 1,
                VK_FORMAT_R8G8B8A8_SRGB,
                mipmapLevels,
                linearMin, linearMax)

            tex.copyFrom(image.contents)

            return tex
        }

        /**
         * Loads a texture from a raw file given by a [stream], and allocates the [VulkanTexture] on [device].
         */
        @Suppress("UNUSED_PARAMETER")
        fun loadFromFileRaw(device: VulkanDevice,
                            commandPools: VulkanRenderer.CommandPools, queue: VulkanDevice.QueueWithMutex, transferQueue: VulkanDevice.QueueWithMutex,
                            stream: InputStream, type: String, dimensions: LongArray): VulkanTexture {
            val imageData: ByteBuffer = ByteBuffer.allocateDirect((2 * dimensions[0] * dimensions[1] * dimensions[2]).toInt())
            val buffer = ByteArray(1024*1024)

            var bytesRead = stream.read(buffer)
            while(bytesRead > -1) {
                imageData.put(buffer)
                bytesRead = stream.read(buffer)
            }

            val tex = VulkanTexture(
                device,
                commandPools, queue, transferQueue,
                dimensions[0].toInt(), dimensions[1].toInt(), dimensions[2].toInt(),
                VK_FORMAT_R16_UINT, 1, minFilterLinear = true, maxFilterLinear = true
            )

            tex.copyFrom(imageData)

            stream.close()

            return tex
        }

        /**
         * Transitions Vulkan image layouts, with [srcAccessMask] and [dstAccessMask] explicitly specified.
         */
        fun transitionLayout(image: Long, from: Int, to: Int, mipLevels: Int = 1,
                             subresourceRange: VkImageSubresourceRange? = null,
                             srcStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             dstStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             srcAccessMask: Int,
                             dstAccessMask: Int,
                             commandBuffer: VkCommandBuffer,
                             dependencyFlags: Int = 0,
                             memoryBarrier: Boolean = false,
                             srcQueueFamilyIndex: Int = VK_QUEUE_FAMILY_IGNORED,
                             dstQueueFamilyIndex: Int = VK_QUEUE_FAMILY_IGNORED
                             ) {
            stackPush().use { stack ->
                val barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .pNext(NULL)
                    .oldLayout(from)
                    .newLayout(to)
                    .srcQueueFamilyIndex(srcQueueFamilyIndex)
                    .dstQueueFamilyIndex(dstQueueFamilyIndex)
                    .srcAccessMask(srcAccessMask)
                    .dstAccessMask(dstAccessMask)
                    .image(image)

                if (subresourceRange == null) {
                    barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(0)
                        .layerCount(1)
                } else {
                    barrier.subresourceRange(subresourceRange)
                }

                logger.trace("Transition: {} -> {} with srcAccessMark={}, dstAccessMask={}, srcStage={}, dstStage={}", from, to, barrier.srcAccessMask(), barrier.dstAccessMask(), srcStage, dstStage)

                val memoryBarriers = if(memoryBarrier) {
                    VkMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(srcAccessMask)
                        .dstAccessMask(dstAccessMask)
                } else {
                    null
                }

                vkCmdPipelineBarrier(
                    commandBuffer,
                    srcStage,
                    dstStage,
                    dependencyFlags,
                    memoryBarriers,
                    null,
                    barrier
                )
            }
        }

        /**
         * Transitions Vulkan image layouts.
         */
        fun transitionLayout(image: Long, oldLayout: Int, newLayout: Int, mipLevels: Int = 1,
                             subresourceRange: VkImageSubresourceRange? = null,
                             srcStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStage: Int = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             commandBuffer: VkCommandBuffer) {

            with(commandBuffer) {
                val barrier = VkImageMemoryBarrier.calloc(1)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .pNext(NULL)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)

                if (subresourceRange == null) {
                    barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(0)
                        .layerCount(1)
                } else {
                    barrier.subresourceRange(subresourceRange)
                }

                when {
                    oldLayout == VK_IMAGE_LAYOUT_PREINITIALIZED
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                        barrier
                            .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_PREINITIALIZED
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                        barrier
                            .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                        barrier
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        if(dstStage == VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT) {
                            barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                        } else {
                            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        }
                    }
                    oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                        barrier
                            .srcAccessMask(0)
                            .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                        barrier
                            .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                        && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                        barrier
                            .srcAccessMask(0)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_INPUT_ATTACHMENT_READ_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                            .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    }
                    oldLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                        && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        && newLayout == KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    }
                    oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                        && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                        barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    }
                    else -> {
                        logger.error("Unsupported layout transition: $oldLayout -> $newLayout")
                    }
                }

                logger.trace("Transition: {} -> {} with srcAccessMark={}, dstAccessMask={}, srcStage={}, dstStage={}", oldLayout, newLayout, barrier.srcAccessMask(), barrier.dstAccessMask(), srcStage, dstStage)

                vkCmdPipelineBarrier(this,
                    srcStage,
                    dstStage,
                    0, null, null, barrier)

                barrier.free()
            }
        }

        /**
         * For a given [Texture], this function returns the corresponding
         * Vulkan texture format, based on the [Texture]'s format and channels,
         * and whether it's a normalized format, or not.
         */
        fun Texture.toVulkanFormat(): Int {
            var format = when(this.type) {
                is ByteType -> when(this.channels) {
                    1 -> VK_FORMAT_R8_SNORM
                    2 -> VK_FORMAT_R8G8_SNORM
                    3 -> VK_FORMAT_R8G8B8A8_SNORM
                    4 -> VK_FORMAT_R8G8B8A8_SNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedByteType -> when(this.channels) {
                    1 -> VK_FORMAT_R8_UNORM
                    2 -> VK_FORMAT_R8G8_UNORM
                    3 -> VK_FORMAT_R8G8B8A8_UNORM
                    4 -> VK_FORMAT_R8G8B8A8_UNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is ShortType ->  when(this.channels) {
                    1 -> VK_FORMAT_R16_SNORM
                    2 -> VK_FORMAT_R16G16_SNORM
                    3 -> VK_FORMAT_R16G16B16A16_SNORM
                    4 -> VK_FORMAT_R16G16B16A16_SNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedShortType -> when(this.channels) {
                    1 -> VK_FORMAT_R16_UNORM
                    2 -> VK_FORMAT_R16G16_UNORM
                    3 -> VK_FORMAT_R16G16B16A16_UNORM
                    4 -> VK_FORMAT_R16G16B16A16_UNORM

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is IntType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_SINT
                    2 -> VK_FORMAT_R32G32_SINT
                    3 -> VK_FORMAT_R32G32B32A32_SINT
                    4 -> VK_FORMAT_R32G32B32A32_SINT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is UnsignedIntType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_UINT
                    2 -> VK_FORMAT_R32G32_UINT
                    3 -> VK_FORMAT_R32G32B32A32_UINT
                    4 -> VK_FORMAT_R32G32B32A32_UINT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is FloatType ->  when(this.channels) {
                    1 -> VK_FORMAT_R32_SFLOAT
                    2 -> VK_FORMAT_R32G32_SFLOAT
                    3 -> VK_FORMAT_R32G32B32A32_SFLOAT
                    4 -> VK_FORMAT_R32G32B32A32_SFLOAT

                    else -> { logger.warn("Unknown texture type: $type, with $channels channels, falling back to default"); VK_FORMAT_R8G8B8A8_UNORM }
                }

                is DoubleType -> TODO("Double format textures are not supported")

                else -> throw UnsupportedOperationException("Type ${type.javaClass.simpleName} is not supported")
            }

            if(!this.normalized && this.type !is FloatType && this.type !is ByteType && this.type !is IntType) {
                format += 4
            }

            return format
        }

        /**
         * Returns a given Vulkan format ID as String representation.
         */
        fun Int.formatToString(): String = when(this) {
            0 -> "VK_FORMAT_UNDEFINED"
            1 -> "VK_FORMAT_R4G4_UNORM_PACK8"
            2 -> "VK_FORMAT_R4G4B4A4_UNORM_PACK16"
            3 -> "VK_FORMAT_B4G4R4A4_UNORM_PACK16"
            4 -> "VK_FORMAT_R5G6B5_UNORM_PACK16"
            5 -> "VK_FORMAT_B5G6R5_UNORM_PACK16"
            6 -> "VK_FORMAT_R5G5B5A1_UNORM_PACK16"
            7 -> "VK_FORMAT_B5G5R5A1_UNORM_PACK16"
            8 -> "VK_FORMAT_A1R5G5B5_UNORM_PACK16"
            9 -> "VK_FORMAT_R8_UNORM"
            10 -> "VK_FORMAT_R8_SNORM"
            11 -> "VK_FORMAT_R8_USCALED"
            12 -> "VK_FORMAT_R8_SSCALED"
            13 -> "VK_FORMAT_R8_UINT"
            14 -> "VK_FORMAT_R8_SINT"
            15 -> "VK_FORMAT_R8_SRGB"
            16 -> "VK_FORMAT_R8G8_UNORM"
            17 -> "VK_FORMAT_R8G8_SNORM"
            18 -> "VK_FORMAT_R8G8_USCALED"
            19 -> "VK_FORMAT_R8G8_SSCALED"
            20 -> "VK_FORMAT_R8G8_UINT"
            21 -> "VK_FORMAT_R8G8_SINT"
            22 -> "VK_FORMAT_R8G8_SRGB"
            23 -> "VK_FORMAT_R8G8B8_UNORM"
            24 -> "VK_FORMAT_R8G8B8_SNORM"
            25 -> "VK_FORMAT_R8G8B8_USCALED"
            26 -> "VK_FORMAT_R8G8B8_SSCALED"
            27 -> "VK_FORMAT_R8G8B8_UINT"
            28 -> "VK_FORMAT_R8G8B8_SINT"
            29 -> "VK_FORMAT_R8G8B8_SRGB"
            30 -> "VK_FORMAT_B8G8R8_UNORM"
            31 -> "VK_FORMAT_B8G8R8_SNORM"
            32 -> "VK_FORMAT_B8G8R8_USCALED"
            33 -> "VK_FORMAT_B8G8R8_SSCALED"
            34 -> "VK_FORMAT_B8G8R8_UINT"
            35 -> "VK_FORMAT_B8G8R8_SINT"
            36 -> "VK_FORMAT_B8G8R8_SRGB"
            37 -> "VK_FORMAT_R8G8B8A8_UNORM"
            38 -> "VK_FORMAT_R8G8B8A8_SNORM"
            39 -> "VK_FORMAT_R8G8B8A8_USCALED"
            40 -> "VK_FORMAT_R8G8B8A8_SSCALED"
            41 -> "VK_FORMAT_R8G8B8A8_UINT"
            42 -> "VK_FORMAT_R8G8B8A8_SINT"
            43 -> "VK_FORMAT_R8G8B8A8_SRGB"
            44 -> "VK_FORMAT_B8G8R8A8_UNORM"
            45 -> "VK_FORMAT_B8G8R8A8_SNORM"
            46 -> "VK_FORMAT_B8G8R8A8_USCALED"
            47 -> "VK_FORMAT_B8G8R8A8_SSCALED"
            48 -> "VK_FORMAT_B8G8R8A8_UINT"
            49 -> "VK_FORMAT_B8G8R8A8_SINT"
            50 -> "VK_FORMAT_B8G8R8A8_SRGB"
            51 -> "VK_FORMAT_A8B8G8R8_UNORM_PACK32"
            52 -> "VK_FORMAT_A8B8G8R8_SNORM_PACK32"
            53 -> "VK_FORMAT_A8B8G8R8_USCALED_PACK32"
            54 -> "VK_FORMAT_A8B8G8R8_SSCALED_PACK32"
            55 -> "VK_FORMAT_A8B8G8R8_UINT_PACK32"
            56 -> "VK_FORMAT_A8B8G8R8_SINT_PACK32"
            57 -> "VK_FORMAT_A8B8G8R8_SRGB_PACK32"
            58 -> "VK_FORMAT_A2R10G10B10_UNORM_PACK32"
            59 -> "VK_FORMAT_A2R10G10B10_SNORM_PACK32"
            60 -> "VK_FORMAT_A2R10G10B10_USCALED_PACK32"
            61 -> "VK_FORMAT_A2R10G10B10_SSCALED_PACK32"
            62 -> "VK_FORMAT_A2R10G10B10_UINT_PACK32"
            63 -> "VK_FORMAT_A2R10G10B10_SINT_PACK32"
            64 -> "VK_FORMAT_A2B10G10R10_UNORM_PACK32"
            65 -> "VK_FORMAT_A2B10G10R10_SNORM_PACK32"
            66 -> "VK_FORMAT_A2B10G10R10_USCALED_PACK32"
            67 -> "VK_FORMAT_A2B10G10R10_SSCALED_PACK32"
            68 -> "VK_FORMAT_A2B10G10R10_UINT_PACK32"
            69 -> "VK_FORMAT_A2B10G10R10_SINT_PACK32"
            70 -> "VK_FORMAT_R16_UNORM"
            71 -> "VK_FORMAT_R16_SNORM"
            72 -> "VK_FORMAT_R16_USCALED"
            73 -> "VK_FORMAT_R16_SSCALED"
            74 -> "VK_FORMAT_R16_UINT"
            75 -> "VK_FORMAT_R16_SINT"
            76 -> "VK_FORMAT_R16_SFLOAT"
            77 -> "VK_FORMAT_R16G16_UNORM"
            78 -> "VK_FORMAT_R16G16_SNORM"
            79 -> "VK_FORMAT_R16G16_USCALED"
            80 -> "VK_FORMAT_R16G16_SSCALED"
            81 -> "VK_FORMAT_R16G16_UINT"
            82 -> "VK_FORMAT_R16G16_SINT"
            83 -> "VK_FORMAT_R16G16_SFLOAT"
            84 -> "VK_FORMAT_R16G16B16_UNORM"
            85 -> "VK_FORMAT_R16G16B16_SNORM"
            86 -> "VK_FORMAT_R16G16B16_USCALED"
            87 -> "VK_FORMAT_R16G16B16_SSCALED"
            88 -> "VK_FORMAT_R16G16B16_UINT"
            89 -> "VK_FORMAT_R16G16B16_SINT"
            90 -> "VK_FORMAT_R16G16B16_SFLOAT"
            91 -> "VK_FORMAT_R16G16B16A16_UNORM"
            92 -> "VK_FORMAT_R16G16B16A16_SNORM"
            93 -> "VK_FORMAT_R16G16B16A16_USCALED"
            94 -> "VK_FORMAT_R16G16B16A16_SSCALED"
            95 -> "VK_FORMAT_R16G16B16A16_UINT"
            96 -> "VK_FORMAT_R16G16B16A16_SINT"
            97 -> "VK_FORMAT_R16G16B16A16_SFLOAT"
            98 -> "VK_FORMAT_R32_UINT"
            99 -> "VK_FORMAT_R32_SINT"
            100 -> "VK_FORMAT_R32_SFLOAT"
            101 -> "VK_FORMAT_R32G32_UINT"
            102 -> "VK_FORMAT_R32G32_SINT"
            103 -> "VK_FORMAT_R32G32_SFLOAT"
            104 -> "VK_FORMAT_R32G32B32_UINT"
            105 -> "VK_FORMAT_R32G32B32_SINT"
            106 -> "VK_FORMAT_R32G32B32_SFLOAT"
            107 -> "VK_FORMAT_R32G32B32A32_UINT"
            108 -> "VK_FORMAT_R32G32B32A32_SINT"
            109 -> "VK_FORMAT_R32G32B32A32_SFLOAT"
            110 -> "VK_FORMAT_R64_UINT"
            111 -> "VK_FORMAT_R64_SINT"
            112 -> "VK_FORMAT_R64_SFLOAT"
            113 -> "VK_FORMAT_R64G64_UINT"
            114 -> "VK_FORMAT_R64G64_SINT"
            115 -> "VK_FORMAT_R64G64_SFLOAT"
            116 -> "VK_FORMAT_R64G64B64_UINT"
            117 -> "VK_FORMAT_R64G64B64_SINT"
            118 -> "VK_FORMAT_R64G64B64_SFLOAT"
            119 -> "VK_FORMAT_R64G64B64A64_UINT"
            120 -> "VK_FORMAT_R64G64B64A64_SINT"
            121 -> "VK_FORMAT_R64G64B64A64_SFLOAT"
            122 -> "VK_FORMAT_B10G11R11_UFLOAT_PACK32"
            123 -> "VK_FORMAT_E5B9G9R9_UFLOAT_PACK32"
            124 -> "VK_FORMAT_D16_UNORM"
            125 -> "VK_FORMAT_X8_D24_UNORM_PACK32"
            126 -> "VK_FORMAT_D32_SFLOAT"
            127 -> "VK_FORMAT_S8_UINT"
            128 -> "VK_FORMAT_D16_UNORM_S8_UINT"
            129 -> "VK_FORMAT_D24_UNORM_S8_UINT"
            130 -> "VK_FORMAT_D32_SFLOAT_S8_UINT"
            131 -> "VK_FORMAT_BC1_RGB_UNORM_BLOCK"
            132 -> "VK_FORMAT_BC1_RGB_SRGB_BLOCK"
            133 -> "VK_FORMAT_BC1_RGBA_UNORM_BLOCK"
            134 -> "VK_FORMAT_BC1_RGBA_SRGB_BLOCK"
            135 -> "VK_FORMAT_BC2_UNORM_BLOCK"
            136 -> "VK_FORMAT_BC2_SRGB_BLOCK"
            137 -> "VK_FORMAT_BC3_UNORM_BLOCK"
            138 -> "VK_FORMAT_BC3_SRGB_BLOCK"
            139 -> "VK_FORMAT_BC4_UNORM_BLOCK"
            140 -> "VK_FORMAT_BC4_SNORM_BLOCK"
            141 -> "VK_FORMAT_BC5_UNORM_BLOCK"
            142 -> "VK_FORMAT_BC5_SNORM_BLOCK"
            143 -> "VK_FORMAT_BC6H_UFLOAT_BLOCK"
            144 -> "VK_FORMAT_BC6H_SFLOAT_BLOCK"
            145 -> "VK_FORMAT_BC7_UNORM_BLOCK"
            146 -> "VK_FORMAT_BC7_SRGB_BLOCK"
            147 -> "VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK"
            148 -> "VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK"
            149 -> "VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK"
            150 -> "VK_FORMAT_ETC2_R8G8B8A1_SRGB_BLOCK"
            151 -> "VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK"
            152 -> "VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK"
            153 -> "VK_FORMAT_EAC_R11_UNORM_BLOCK"
            154 -> "VK_FORMAT_EAC_R11_SNORM_BLOCK"
            155 -> "VK_FORMAT_EAC_R11G11_UNORM_BLOCK"
            156 -> "VK_FORMAT_EAC_R11G11_SNORM_BLOCK"
            157 -> "VK_FORMAT_ASTC_4x4_UNORM_BLOCK"
            158 -> "VK_FORMAT_ASTC_4x4_SRGB_BLOCK"
            159 -> "VK_FORMAT_ASTC_5x4_UNORM_BLOCK"
            160 -> "VK_FORMAT_ASTC_5x4_SRGB_BLOCK"
            161 -> "VK_FORMAT_ASTC_5x5_UNORM_BLOCK"
            162 -> "VK_FORMAT_ASTC_5x5_SRGB_BLOCK"
            163 -> "VK_FORMAT_ASTC_6x5_UNORM_BLOCK"
            164 -> "VK_FORMAT_ASTC_6x5_SRGB_BLOCK"
            165 -> "VK_FORMAT_ASTC_6x6_UNORM_BLOCK"
            166 -> "VK_FORMAT_ASTC_6x6_SRGB_BLOCK"
            167 -> "VK_FORMAT_ASTC_8x5_UNORM_BLOCK"
            168 -> "VK_FORMAT_ASTC_8x5_SRGB_BLOCK"
            169 -> "VK_FORMAT_ASTC_8x6_UNORM_BLOCK"
            170 -> "VK_FORMAT_ASTC_8x6_SRGB_BLOCK"
            171 -> "VK_FORMAT_ASTC_8x8_UNORM_BLOCK"
            172 -> "VK_FORMAT_ASTC_8x8_SRGB_BLOCK"
            173 -> "VK_FORMAT_ASTC_10x5_UNORM_BLOCK"
            174 -> "VK_FORMAT_ASTC_10x5_SRGB_BLOCK"
            175 -> "VK_FORMAT_ASTC_10x6_UNORM_BLOCK"
            176 -> "VK_FORMAT_ASTC_10x6_SRGB_BLOCK"
            177 -> "VK_FORMAT_ASTC_10x8_UNORM_BLOCK"
            178 -> "VK_FORMAT_ASTC_10x8_SRGB_BLOCK"
            179 -> "VK_FORMAT_ASTC_10x10_UNORM_BLOCK"
            180 -> "VK_FORMAT_ASTC_10x10_SRGB_BLOCK"
            181 -> "VK_FORMAT_ASTC_12x10_UNORM_BLOCK"
            182 -> "VK_FORMAT_ASTC_12x10_SRGB_BLOCK"
            183 -> "VK_FORMAT_ASTC_12x12_UNORM_BLOCK"
            184 -> "VK_FORMAT_ASTC_12x12_SRGB_BLOCK"
            else -> "(unknown texture format)"
        }
    }
}
