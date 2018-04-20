package graphics.scenery.utils

import java.util.*

/**
 * Created by ulrik on 1/8/2017.
 */
open class RingBuffer<T: Any>(var size: Int, default: ((Int) -> T)? = null) {

    protected val backingStore: ArrayList<T> = ArrayList(size)
    var currentReadPosition = 0
        protected set
    var currentWritePosition = 0
        protected set

    init {
        default?.let {
            (0 until size).map { element ->
                put(default(element))
            }
        }
    }

    fun put(element: T) {
        if(backingStore.size <= size)
            backingStore += element
        else {
            currentWritePosition %= backingStore.size
            backingStore[currentWritePosition] = element
        }

        currentWritePosition++
    }

    fun get(): T {
        currentReadPosition %= backingStore.size
        val element = backingStore[currentReadPosition]

        currentReadPosition++
        return element
    }

    fun reset() {
        currentReadPosition = 0
        currentWritePosition = 0

        backingStore.clear()
    }
}
