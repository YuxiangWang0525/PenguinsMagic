package me.riverfrozer.penguinsmagic.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object StoreIo {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "penguin-store-io").apply { isDaemon = true }
    }
}

/**
 * 小体积 JSON 文件仓库的写入代理：调用方在自己线程上生成快照，
 * 落盘丢给单线程队列，过期的快照直接丢弃。
 */
internal class DebouncedWriter(private val file: File) {
    private val version = AtomicInteger()

    fun write(snapshot: () -> String) {
        val text = snapshot()
        val current = version.incrementAndGet()
        StoreIo.executor.execute {
            if (current == version.get()) writeToDisk(text)
        }
    }

    fun flush(snapshot: () -> String) {
        val text = snapshot()
        version.incrementAndGet()
        StoreIo.executor.execute { writeToDisk(text) }
    }

    private fun writeToDisk(text: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (ignored: Exception) {
            // 存储不可用（比如磁盘满）时不阻断来电拦截
        }
    }

    fun readArray(): JSONArray = try {
        if (file.exists()) JSONArray(file.readText()) else JSONArray()
    } catch (ignored: Exception) {
        JSONArray()
    }

    fun readObject(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (ignored: Exception) {
        JSONObject()
    }
}
