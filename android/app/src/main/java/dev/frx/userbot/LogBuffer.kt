package dev.frx.userbot

import java.io.File
import java.io.FileOutputStream

/** Последние строки вывода в памяти (для экрана) + файл с ротацией (frx.log → frx.log.1 при 2 МБ). */
class LogBuffer(private val file: File?, private val keep: Int = 600) {
    private val lines = ArrayDeque<String>()
    private var out: FileOutputStream? = null
    var seq = 0L
        private set

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > keep) lines.removeFirst()
        seq++
        if (file != null) try {
            if (out == null) {
                file.parentFile?.mkdirs()
                out = FileOutputStream(file, true)
            }
            out!!.write((line + "\n").toByteArray())
            if (file.length() > 2L * 1024 * 1024) {
                out!!.close(); out = null
                val old = File(file.path + ".1")
                old.delete(); file.renameTo(old)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun addMemoryOnly(line: String) {
        lines.addLast(line)
        while (lines.size > keep) lines.removeFirst()
        seq++
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() {
        lines.clear(); seq++
    }

    @Synchronized
    fun flush() {
        try { out?.flush() } catch (_: Exception) {}
    }
}
