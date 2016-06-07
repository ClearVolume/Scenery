package scenery

import java.util.concurrent.ConcurrentHashMap

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class Settings {
    var settingsStore = ConcurrentHashMap<String, Any>()

    inline fun <reified T> get(name: String): T {
        if(!settingsStore.containsKey(name)) {
            System.err.println("WARNING: Settings don't contain '$name'")
        }
        return settingsStore.get(name) as T
    }

    fun <T> getProperty(name: String, type: Class<T>): T{
        if(!settingsStore.containsKey(name)) {
            System.err.println("WARNING: Settings don't contain '$name'")
        }
        return settingsStore.get(name) as T
    }

    fun set(name: String, contents: Any) {
        settingsStore.put(name, contents)
    }
}