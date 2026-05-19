package com.aiagent.android.kiro.keys

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты на state-machine [KeyPool]. Все переходы проверяются
 * детерминированно через инжекцию управляемых часов.
 */
class KeyPoolStateMachineTest {

    private class FakeClock(var current: Long = 0L) {
        fun advance(ms: Long) { current += ms }
        fun read(): Long = current
    }

    private fun mkPool(vararg entries: KeyEntry, clock: FakeClock = FakeClock()): Pair<KeyPool, FakeClock> {
        val pool = KeyPool(ProviderType.KIRO, entries.toList(), now = clock::read)
        return pool to clock
    }

    private fun mkKey(label: String, state: KeyState = KeyState.HEALTHY): KeyEntry =
        KeyEntry(label = label, raw = "ksk_${label.hashCode()}_xxxxx", state = state)

    @Test
    fun `acquire returns null when pool is empty`() = runTest {
        val (pool, _) = mkPool()
        assertNull(pool.acquire())
        assertNull(pool.acquire(forSubagent = true))
    }

    @Test
    fun `acquire returns healthy in round-robin order`() = runTest {
        val a = mkKey("A")
        val b = mkKey("B")
        val c = mkKey("C")
        val (pool, _) = mkPool(a, b, c)

        val first = pool.acquire()!!
        val second = pool.acquire()!!
        val third = pool.acquire()!!
        val fourth = pool.acquire()!!

        // Round-robin: A, B, C, затем снова A
        assertEquals("A", first.label)
        assertEquals("B", second.label)
        assertEquals("C", third.label)
        assertEquals("A", fourth.label)
    }

    @Test
    fun `acquire prefers HEALTHY over UNSTABLE`() = runTest {
        val u = mkKey("unstable", state = KeyState.UNSTABLE)
        val h = mkKey("healthy", state = KeyState.HEALTHY)
        val (pool, _) = mkPool(u, h)
        val picked = pool.acquire()!!
        assertEquals("healthy", picked.label)
    }

    @Test
    fun `acquire falls back to UNSTABLE when no HEALTHY`() = runTest {
        val u = mkKey("u1", state = KeyState.UNSTABLE)
        val (pool, _) = mkPool(u)
        val picked = pool.acquire()!!
        assertEquals(KeyState.UNSTABLE, picked.state)
    }

    @Test
    fun `acquire skips DEAD keys`() = runTest {
        val d = mkKey("dead", state = KeyState.DEAD)
        val (pool, _) = mkPool(d)
        assertNull(pool.acquire())
    }

    @Test
    fun `acquire skips RESERVE for subagent`() = runTest {
        val r = mkKey("reserve", state = KeyState.RESERVE)
        val (pool, _) = mkPool(r)
        assertNull(pool.acquire(forSubagent = true))
        // А обычный потребитель — получает.
        assertNotNull(pool.acquire(forSubagent = false))
    }

    @Test
    fun `release Success heals UNSTABLE to HEALTHY after threshold`() = runTest {
        val u = mkKey("u", state = KeyState.UNSTABLE)
        val (pool, _) = mkPool(u)
        val id = u.id

        repeat(KeyPool.SUCCESSES_TO_HEAL - 1) {
            pool.release(id, KeyOperationResult.Success)
        }
        // Пока ещё UNSTABLE
        assertEquals(KeyState.UNSTABLE, pool.state.value.keys.first().state)

        // Последний — поднимает до HEALTHY.
        pool.release(id, KeyOperationResult.Success)
        assertEquals(KeyState.HEALTHY, pool.state.value.keys.first().state)
        assertEquals(0, pool.state.value.keys.first().consecutiveFailures)
    }

    @Test
    fun `release TransientError destabilizes HEALTHY after 2 failures`() = runTest {
        val h = mkKey("h")
        val (pool, _) = mkPool(h)
        val id = h.id

        pool.release(id, KeyOperationResult.TransientError("timeout"))
        // 1 failure - всё ещё HEALTHY
        assertEquals(KeyState.HEALTHY, pool.state.value.keys.first().state)

        pool.release(id, KeyOperationResult.TransientError("5xx"))
        // 2 failures - UNSTABLE
        assertEquals(KeyState.UNSTABLE, pool.state.value.keys.first().state)
    }

    @Test
    fun `release TransientError kills UNSTABLE after enough failures`() = runTest {
        val u = mkKey("u", state = KeyState.UNSTABLE).copy(consecutiveFailures = 4)
        val (pool, _) = mkPool(u)
        val id = u.id

        pool.release(id, KeyOperationResult.TransientError("5xx-5"))
        // Перешли в DEAD
        assertEquals(KeyState.DEAD, pool.state.value.keys.first().state)
    }

    @Test
    fun `release PermanentError immediately kills`() = runTest {
        val h = mkKey("h")
        val (pool, _) = mkPool(h)
        val id = h.id

        pool.release(id, KeyOperationResult.PermanentError("401 invalid api key"))
        assertEquals(KeyState.DEAD, pool.state.value.keys.first().state)
        assertTrue(pool.state.value.keys.first().recentErrors.first().contains("401"))
    }

    @Test
    fun `acquire respects cooldown timestamp`() = runTest {
        val clock = FakeClock(1000L)
        val h = mkKey("h")
        val (pool, _) = mkPool(h, clock = clock)
        val id = h.id

        // Transient → cooldown 30 сек
        pool.release(id, KeyOperationResult.TransientError("timeout"))
        // Время сразу после ошибки — нельзя выдать
        assertNull(pool.acquire())

        // Сдвинули часы на 29 сек — всё ещё нельзя
        clock.advance(29_000L)
        assertNull(pool.acquire())

        // 30+ сек — можно
        clock.advance(2_000L)
        assertNotNull(pool.acquire())
    }

    @Test
    fun `cooldown grows exponentially with consecutive failures`() = runTest {
        val clock = FakeClock(1000L)
        val h = mkKey("h")
        val (pool, _) = mkPool(h, clock = clock)
        val id = h.id

        pool.release(id, KeyOperationResult.TransientError("e1"))
        val after1 = pool.state.value.keys.first().cooldownUntilMs - clock.read()
        pool.release(id, KeyOperationResult.TransientError("e2"))
        val after2 = pool.state.value.keys.first().cooldownUntilMs - clock.read()
        pool.release(id, KeyOperationResult.TransientError("e3"))
        val after3 = pool.state.value.keys.first().cooldownUntilMs - clock.read()

        // Cooldown растёт: 30c → 60c → 120c
        assertEquals(30_000L, after1)
        assertEquals(60_000L, after2)
        assertEquals(120_000L, after3)
    }

    @Test
    fun `markReserve moves key to RESERVE state`() = runTest {
        val h = mkKey("h")
        val (pool, _) = mkPool(h)
        pool.markReserve(h.id)
        assertEquals(KeyState.RESERVE, pool.state.value.keys.first().state)
    }

    @Test
    fun `unmarkReserve moves RESERVE back to HEALTHY`() = runTest {
        val r = mkKey("r", state = KeyState.RESERVE)
        val (pool, _) = mkPool(r)
        pool.unmarkReserve(r.id)
        assertEquals(KeyState.HEALTHY, pool.state.value.keys.first().state)
    }

    @Test
    fun `RESERVE used only when HEALTHY and UNSTABLE are gone`() = runTest {
        val h = mkKey("h")
        val r = mkKey("r", state = KeyState.RESERVE)
        val (pool, _) = mkPool(h, r)

        val picked1 = pool.acquire()!!
        assertEquals("h", picked1.label)
        // Угробим h
        pool.release(h.id, KeyOperationResult.PermanentError("401"))
        val picked2 = pool.acquire()
        assertNotNull(picked2)
        assertEquals(KeyState.RESERVE, picked2!!.state)
    }

    @Test
    fun `onPingResult success revives DEAD to HEALTHY`() = runTest {
        val d = mkKey("d", state = KeyState.DEAD)
        val (pool, _) = mkPool(d)
        pool.onPingResult(d.id, success = true, reason = null)
        assertEquals(KeyState.HEALTHY, pool.state.value.keys.first().state)
        assertEquals(0, pool.state.value.keys.first().consecutiveFailures)
    }

    @Test
    fun `onPingResult success revives UNSTABLE to HEALTHY in one ping`() = runTest {
        val u = mkKey("u", state = KeyState.UNSTABLE).copy(consecutiveFailures = 3)
        val (pool, _) = mkPool(u)
        pool.onPingResult(u.id, success = true, reason = null)
        assertEquals(KeyState.HEALTHY, pool.state.value.keys.first().state)
    }

    @Test
    fun `masked output does not expose raw key`() {
        val e = mkKey("e")
        val m = e.masked()
        assertFalse("Masked output should not contain full raw", m.contains(e.raw))
        assertTrue(m.contains("…"))
    }

    @Test
    fun `RESERVE state is preserved on any release outcome`() = runTest {
        val r = mkKey("r", state = KeyState.RESERVE)
        val (pool, _) = mkPool(r)
        pool.release(r.id, KeyOperationResult.TransientError("5xx"))
        assertEquals(KeyState.RESERVE, pool.state.value.keys.first().state)
        pool.release(r.id, KeyOperationResult.PermanentError("401"))
        // Это особый случай: даже PermanentError не сбрасывает RESERVE-флаг.
        // Решение пользователя в приоритете; ошибки фиксируются в recentErrors.
        assertEquals(KeyState.RESERVE, pool.state.value.keys.first().state)
        assertTrue(pool.state.value.keys.first().recentErrors.isNotEmpty())
    }
}
