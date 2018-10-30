package graphics.scenery.backends.vulkan

import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import vkk.*
import vkk.`object`.VkFence

/**
 * Vulkan Command Buffer class. Wraps command buffer and fencing functionality.
 * Allocates the command buffer on [device], and can wrap an existing raw [commandBuffer].
 * [VulkanCommandBuffer]s are by default [fenced].
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VulkanCommandBuffer(val device: VulkanDevice, var commandBuffer: VkCommandBuffer?, val fenced: Boolean = true): AutoCloseable {
    /** Whether this command buffer is stale and needs to be re-recorded. */
    var stale: Boolean = true

    private var fenceInitialized: Boolean = false
    var fence = VkFence(NULL)
        private set

    /** Whether this command buffer has already been submitted to a queue. */
    var submitted = false

    init {
        if(fenced) {
            addFence()
        }
    }

    val vkDev get() = device.vulkanDevice

    /**
     * Adds a fence to this command buffer for synchronisation.
     */
    fun addFence() {
        fence = vkDev createFence vk.FenceCreateInfo()
        fenceInitialized = true
    }

    /**
     * Waits for the command buffer's execution to complete via a fence,
     * waiting for [timeout] milliseconds.
     */
    fun waitForFence(timeout: Long? = null) {
        if(fenced && fenceInitialized)
            vkDev.waitForFence(fence, true, timeout ?: -1L)
    }

    /**
     * Resets this command buffer's fence.
     */
    fun resetFence() {
        if(fenced && fenceInitialized)
            vkDev resetFence fence
    }

    /**
     * Closes and deallocates this command buffer.
     */
    override fun close() {
        if(fenced && fenceInitialized)
            vkDev destroyFence fence
    }

    /**
     * Prepares this command buffer for recording, either initialising or
     * resetting the associated Vulkan command buffer. Recording will take place in command pool [pool].
     */
    fun prepareAndStartRecording(pool: Long): VkCommandBuffer {
        // start command buffer recording
        if (commandBuffer == null) {
            commandBuffer = VU.newCommandBuffer(device, pool, autostart = true)
        }

        val cmd = commandBuffer ?: throw IllegalStateException("Command buffer cannot be null for recording")

        vkResetCommandBuffer(cmd, 0)
        VU.beginCommandBuffer(cmd)

        return cmd
    }
}
