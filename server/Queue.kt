package com.example.llmserver

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class Ev {
    class Delta(val text: String) : Ev()
    class Calls(val calls: List<TC>) : Ev()
    class Done(val finish: String, val promptTokens: Int, val completionTokens: Int) : Ev()
    class Err(val code: Int, val message: String) : Ev()
}

class Job(
    val req: ChatReq,
    val client: Client,
    val priority: Int,
    val deadline: Long,
    val supersedeKey: String?,
    val size: Long,
) {
    val seq = Queue.nextSeq()
    val created = System.currentTimeMillis()
    val events = LinkedBlockingQueue<Ev>()
    @Volatile var cancelled = false
    @Volatile var startedAt = 0L
}

/**
 * Очередь запросов к единственной модели.
 * Порядок: приоритет → клиент, которого дольше всех не обслуживали (справедливо между клиентами) → время прихода.
 */
object Queue {
    const val MAX_WAITING = 50
    const val MAX_PER_CLIENT = 4
    const val MAX_BYTES = 96L * 1024 * 1024

    private val counter = AtomicLong()
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val waiting = ArrayList<Job>()
    private val lastServed = HashMap<String, Long>()

    @Volatile var running: Job? = null
    val changed = MutableStateFlow(0)

    /** клиент → [выполнено, ошибок, суммарное ожидание мс] */
    val stats = ConcurrentHashMap<String, LongArray>()

    fun nextSeq() = counter.incrementAndGet()

    private val order = compareBy<Job>({ it.priority }, { lastServed[it.client.name] ?: 0L }, { it.seq })

    /** Возвращает причину отказа или null, если запрос принят. */
    fun add(job: Job): String? {
        lock.withLock {
            // «последний побеждает»: новый запрос с тем же ключом от того же клиента заменяет ожидающий старый
            if (job.supersedeKey != null) {
                val it = waiting.iterator()
                while (it.hasNext()) {
                    val j = it.next()
                    if (j.client.name == job.client.name && j.supersedeKey == job.supersedeKey) {
                        it.remove()
                        j.events.add(Ev.Err(409, "запрос заменён более новым с тем же X-Supersede-Key"))
                    }
                }
            }
            if (waiting.size >= MAX_WAITING) return "очередь переполнена ($MAX_WAITING запросов)"
            if (waiting.count { it.client.name == job.client.name } >= MAX_PER_CLIENT)
                return "у клиента «${job.client.name}» уже $MAX_PER_CLIENT запроса в очереди"
            if (waiting.sumOf { it.size } + job.size > MAX_BYTES) return "очередь переполнена по объёму данных"
            waiting.add(job)
            cond.signalAll()
        }
        changed.value++
        return null
    }

    /** Блокируется, пока нет работы. */
    fun take(): Job {
        lock.lock()
        try {
            while (true) {
                waiting.removeAll { it.cancelled }
                val best = waiting.minWithOrNull(order)
                if (best != null) {
                    waiting.remove(best)
                    lastServed[best.client.name] = counter.incrementAndGet()
                    changed.value++
                    return best
                }
                cond.await()
            }
        } finally {
            lock.unlock()
        }
    }

    fun cancel(job: Job) {
        job.cancelled = true
        lock.withLock { waiting.remove(job) }
        changed.value++
    }

    fun snapshot(): List<Job> = lock.withLock { waiting.sortedWith(order) }

    fun position(job: Job): Int = snapshot().indexOf(job) + 1

    fun record(client: String, ok: Boolean, waitMs: Long) {
        val s = stats.getOrPut(client) { LongArray(3) }
        synchronized(s) { if (ok) s[0]++ else s[1]++; s[2] += waitMs }
    }
}
