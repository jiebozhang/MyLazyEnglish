package com.lazyeng.family.spikes.safmedia3

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAccessFailureTest {
    @Test fun securityFailureWithPersistedGrantMeansSourceIsUnavailable() {
        assertEquals(
            MediaAccessFailure.SOURCE_FILE_MISSING,
            MediaAccessFailure.from(SecurityException(), hasPersistedReadGrant = true),
        )
    }
    @Test fun securityFailureWithoutPersistedGrantMeansPermissionWasRevoked() {
        assertEquals(
            MediaAccessFailure.PERMISSION_REVOKED,
            MediaAccessFailure.from(SecurityException(), hasPersistedReadGrant = false),
        )
    }
    @Test fun missingSourceIsDistinctFromOtherIoFailures() {
        assertEquals(MediaAccessFailure.SOURCE_FILE_MISSING, MediaAccessFailure.from(FileNotFoundException()))
        assertEquals(MediaAccessFailure.IO_ERROR, MediaAccessFailure.from(IOException()))
    }
    @Test fun malformedMediaAndUnexpectedErrorsStayDistinct() {
        assertEquals(MediaAccessFailure.UNSUPPORTED_MEDIA, MediaAccessFailure.from(IllegalArgumentException()))
        assertEquals(MediaAccessFailure.MEDIA_ACCESS_ERROR, MediaAccessFailure.from(IllegalStateException()))
    }
}
