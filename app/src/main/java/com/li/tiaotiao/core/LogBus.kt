package com.li.tiaotiao.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 内存日志，UI 里实时展示，方便调规则 */
object LogBus {

    private val buf = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > 300) buf.removeFirst()
        }
        listeners.forEach { runCatching { it(line) } }
    }

    fun snapshot(): List<String> = synchronized(buf) { buf.toList() }

    fun clear() = synchronized(buf) { buf.clear() }

    fun addListener(l: (String) -> Unit) = listeners.add(l)

    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
}
