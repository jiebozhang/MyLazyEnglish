package com.lazyeng.family.spikes.safmedia3

import java.io.FileNotFoundException
import java.io.IOException

internal enum class MediaAccessFailure {
    PERMISSION_REVOKED, SOURCE_FILE_MISSING, UNSUPPORTED_MEDIA, IO_ERROR, MEDIA_ACCESS_ERROR;

    companion object {
        fun from(error: Throwable, hasPersistedReadGrant: Boolean = false): MediaAccessFailure = when (error) {
            is SecurityException -> if (hasPersistedReadGrant) SOURCE_FILE_MISSING else PERMISSION_REVOKED
            is FileNotFoundException -> SOURCE_FILE_MISSING
            is IOException -> IO_ERROR
            is IllegalArgumentException -> UNSUPPORTED_MEDIA
            else -> MEDIA_ACCESS_ERROR
        }
    }
}
