package com.lazyeng.family.core.security

import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.model.FamilyId

class ParentAuthorizedScope internal constructor(val familyId: FamilyId, internal val generation: Long)

/** UI-owned session; role labels never grant access. Tokens are memory-only and generation-bound. */
class ParentSession(private val familyId: FamilyId, private val pins: PinService, private val clock: Clock) {
    private var generation = 0L
    private var scope: ParentAuthorizedScope? = null
    private var until = 0L
    @Synchronized fun revoke() { generation++; scope = null; until = 0 }
    @Synchronized fun authorized(): Boolean = scope != null && clock.now().toEpochMilli() < until
    @Synchronized fun touch() { if (authorized()) until = clock.now().toEpochMilli() + 300_000 }
    @Synchronized fun requireScope(): ParentAuthorizedScope = checkNotNull(scope?.takeIf { authorized() })
    @Synchronized fun checkScope(value: ParentAuthorizedScope) {
        check(value === scope && value.familyId == familyId && value.generation == generation && authorized())
    }
    suspend fun unlock(pin: CharArray): PinCheck {
        val started = synchronized(this) { generation }
        val result = pins.verify(familyId, pin)
        synchronized(this) {
            check(started == generation) { "Authorization was cancelled" }
            if (result == PinCheck.Accepted) {
                scope = ParentAuthorizedScope(familyId, generation)
                until = clock.now().toEpochMilli() + 300_000
            }
        }
        return result
    }
    suspend fun confirmDeletion(value: ParentAuthorizedScope, pin: CharArray): PinCheck {
        checkScope(value)
        val result = pins.verify(familyId, pin)
        checkScope(value)
        return result
    }
    suspend fun status() = pins.status(familyId)
}
