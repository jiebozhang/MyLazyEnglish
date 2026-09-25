package com.lazyeng.family.core.common

import com.lazyeng.family.core.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Subscribers must clear old scope before loading the event's explicit new profileId. */
class ProfileChanges {
    private val mutable = MutableSharedFlow<ProfileChanged>()
    val events: SharedFlow<ProfileChanged> = mutable.asSharedFlow()
    suspend fun publish(event: ProfileChanged) { mutable.emit(event) }
}

/** Register at application assembly; exceptions/cancellation roll back ALL database cleaners. */
class ProfileDeletionCoordinator(private val transaction: ProfileDeletionTransaction) {
    private val cleaners = linkedMapOf<String, ProfileCleaner>()
    private val mutex = Mutex()
    @Synchronized fun register(name: String, cleaner: ProfileCleaner) {
        check(name.isNotBlank() && name !in cleaners)
        cleaners[name] = cleaner
    }
    suspend fun request(request: ProfileDeletionRequested, authorize: () -> Unit = {}) = mutex.withLock {
        val snapshot = synchronized(this) { cleaners.values.toList() }
        check(snapshot.isNotEmpty())
        transaction.run {
            authorize()
            snapshot.forEach { it.clean(request) }
            authorize()
        }
    }
}
