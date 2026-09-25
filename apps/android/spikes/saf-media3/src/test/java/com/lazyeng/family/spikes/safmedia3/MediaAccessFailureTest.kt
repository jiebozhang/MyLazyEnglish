package com.lazyeng.family.spikes.safmedia3

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAccessFailureTest {
    @Test fun revokedPermissionIsActionable() {
        assertEquals(MediaAccessFailure.PERMISSION_REVOKED, MediaAccessFailure.from(SecurityException()))
    }
    @Test fun missingFileIsDistinctFromOtherIoFailures() {
        assertEquals(MediaAccessFailure.FILE_MISSING, MediaAccessFailure.from(FileNotFoundException()))
        assertEquals(MediaAccessFailure.IO_ERROR, MediaAccessFailure.from(IOException()))
    }
    @Test fun malformedMediaAndUnexpectedErrorsStayDistinct() {
        assertEquals(MediaAccessFailure.UNSUPPORTED_MEDIA, MediaAccessFailure.from(IllegalArgumentException()))
        assertEquals(MediaAccessFailure.MEDIA_ACCESS_ERROR, MediaAccessFailure.from(IllegalStateException()))
    }
}
