package com.lazyeng.family.spikes.safmedia3

import java.io.FileNotFoundException
import java.io.IOException

internal enum class MediaAccessFailure {
    PERMISSION_REVOKED, FILE_MISSING, UNSUPPORTED_MEDIA, IO_ERROR, MEDIA_ACCESS_ERROR;

    companion object {
        fun from(error: Throwable): MediaAccessFailure = when (error) {
            is SecurityException -> PERMISSION_REVOKED
            is FileNotFoundException -> FILE_MISSING
            is IOException -> IO_ERROR
            is IllegalArgumentException -> UNSUPPORTED_MEDIA
            else -> MEDIA_ACCESS_ERROR
        }
    }
}
