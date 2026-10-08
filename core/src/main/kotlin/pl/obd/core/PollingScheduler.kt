package pl.obd.core

/** Single-consumer due-time scheduler. Caller awaits each prompt before calling complete. */
class PollingScheduler {
    private data class Task(var last: Long? = null, var failures: Int = 0, var retryAt: Long = 0)
    private val tasks = mutableMapOf<Int, Task>()
    @Synchronized fun next(selected: Set<Int>, now: Long, fastMs: Long): Int? {
        tasks.keys.retainAll(selected)
        selected.forEach { tasks.getOrPut(it) { Task() } }
        val fresh = selected.filter { tasks.getValue(it).last == null }.minOrNull()
        if (fresh != null) return fresh
        // Earliest due deadline first: slow tasks cannot starve behind an overloaded fast group.
        return selected.filter { id ->
            val t = tasks.getValue(id)
            now >= t.retryAt && now - requireNotNull(t.last) >= interval(id, fastMs)
        }.minByOrNull { id -> requireNotNull(tasks.getValue(id).last) + interval(id, fastMs) }
    }
    @Synchronized fun complete(id: Int, now: Long, success: Boolean) {
        val t = tasks.getOrPut(id) { Task() }; t.last = now
        t.failures = if (success) 0 else t.failures + 1
        t.retryAt = if (t.failures >= 3) now + minOf(60_000L, 10_000L * (t.failures - 2)) else now
    }
    @Synchronized fun reset() = tasks.clear()
    companion object {
        private val fast = setOf(0x04, 0x0B, 0x0C, 0x0D, 0x10, 0x11, 0x23, 0x45, 0x49, 0x4A, 0x4B, 0x4C, 0x59, 0x5A, 0x69)
        private val slow = setOf(0x1F, 0x21, 0x30, 0x31, 0x33, 0x4D, 0x4E)
        fun isFast(pid: Int) = pid in fast
        fun interval(pid: Int, fastMs: Long = 500) = when (pid) { in fast -> fastMs.coerceIn(100, 5000); in slow -> 60_000L; else -> 5000L }
        fun label(pid: Int?, fastMs: Long = 500) = when (pid) { in fast -> "Szybki · cel ${fastMs} ms"; in slow -> "Wolny · 60 s"; else -> "Średni · 5 s" }
    }
}
