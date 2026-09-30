package com.lazyeng.family.feature.importmedia

import com.lazyeng.family.core.model.VideoId
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRepairChoiceTest {
    @Test fun detailRepairCannotCreateOrChooseAnotherDuplicate() {
        val target = VideoId("repair-target")
        assertEquals(DuplicateChoice.Reuse(target), resolveImportChoice(DuplicateChoice.ImportNew, target))
        assertEquals(DuplicateChoice.Reuse(target), resolveImportChoice(DuplicateChoice.Reuse(VideoId("other")), target))
        assertEquals(DuplicateChoice.ImportNew, resolveImportChoice(DuplicateChoice.ImportNew, null))
    }
}
