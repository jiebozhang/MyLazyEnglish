package com.lazyeng.family

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.FamilyId
import com.lazyeng.family.core.model.Profile
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.security.PinCheck
import com.lazyeng.family.feature.parent.ManagedVideoRow
import com.lazyeng.family.feature.parent.ParentManagementGateway
import com.lazyeng.family.feature.parent.ParentManagementScreen
import com.lazyeng.family.feature.parent.ParentManagementSection
import com.lazyeng.family.feature.parent.ParentManagementSnapshot
import com.lazyeng.family.feature.parent.ParentManagementUiEvent
import com.lazyeng.family.feature.parent.ParentManagementViewModel
import com.lazyeng.family.feature.parent.StorageUsage
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ParentManagementDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun memberRemovalShowsImpactConfirmationAndPinGate() {
        val gateway = FakeGateway()
        val model = viewModel(gateway, ParentManagementSection.MEMBERS)
        compose.setContent { val state by model.state.collectAsState(); ParentManagementScreen(state, model::onEvent) }
        compose.onNodeWithTag("managed-member-child-a").assertIsDisplayed()
        compose.onAllNodesWithText("删除")[0].performClick()
        compose.onNodeWithText("删除孩子档案？").assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("再次验证家长 PIN").assertIsDisplayed()
        compose.onNodeWithTag("member-delete-pin").performTextInput("1234")
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5_000) { gateway.deleted }
        assertEquals(1, gateway.deleteCalls)
        compose.onNodeWithText("孩子档案及其私有学习数据已删除。").assertIsDisplayed()
    }

    @Test fun contentToggleAndStorageSummaryAreRenderedFromGatewaySnapshot() {
        val gateway = FakeGateway()
        val model = viewModel(gateway, ParentManagementSection.CONTENT)
        compose.setContent { val state by model.state.collectAsState(); ParentManagementScreen(state, model::onEvent) }
        compose.onNodeWithTag("managed-video-video-a").assertIsDisplayed()
        compose.onNodeWithText("孩子端可见").assertIsDisplayed()
        compose.onNodeWithTag("visibility-video-a").performClick()
        compose.waitUntil(5_000) { !gateway.videoVisible }
        compose.onNodeWithText("孩子端已隐藏").assertIsDisplayed()

        assertTrue(gateway.loadedSections.contains(ParentManagementSection.CONTENT))
    }

    @Test fun storageScreenShowsUsageAndClearCacheAction() {
        val gateway = FakeGateway()
        val storage = viewModel(gateway, ParentManagementSection.STORAGE)
        compose.setContent { val state by storage.state.collectAsState(); ParentManagementScreen(state, storage::onEvent) }
        compose.onNodeWithText("离线词库与数据库：2 B").assertIsDisplayed()
        compose.onNodeWithText("清理临时缓存").assertIsDisplayed()
        assertTrue(gateway.loadedSections.contains(ParentManagementSection.STORAGE))
    }

    @Test fun videoRemovalRequiresExplicitConsequenceConfirmation() {
        val gateway = FakeGateway()
        val model = viewModel(gateway, ParentManagementSection.CONTENT)
        compose.setContent { val state by model.state.collectAsState(); ParentManagementScreen(state, model::onEvent) }
        compose.onNodeWithText("移除视频").performClick()
        compose.onNodeWithText("彻底移除视频记录？").assertIsDisplayed()
        compose.onNodeWithText("移除").performClick()
        compose.waitUntil(5_000) { gateway.videoDeleted }
    }

    private fun viewModel(gateway: FakeGateway, section: ParentManagementSection) = ParentManagementViewModel(
        gateway, object : DispatcherProvider {
            override val io = Dispatchers.Main.immediate
            override val computation = Dispatchers.Main.immediate
            override val main = Dispatchers.Main.immediate
        }, section,
    )

    private class FakeGateway : ParentManagementGateway {
        val loadedSections = mutableListOf<ParentManagementSection>()
        var deleteCalls = 0
        var deleted = false
        var videoVisible = true
        var videoDeleted = false
        private val profiles = mutableListOf(profile("child-a"), profile("child-b"))
        override suspend fun load(section: ParentManagementSection): ParentManagementSnapshot {
            loadedSections += section
            return ParentManagementSnapshot(
                members = profiles.toList(),
                videos = listOf(ManagedVideoRow(VideoId("video-a"), "Sample video", "sample.mp4", 60_000, 2048, videoVisible, "READY")),
                storage = StorageUsage(2048, 2, 3, 4096),
            )
        }
        override suspend fun createMember(nickname: String, level: String, avatarId: String) = Unit
        override suspend fun deleteMember(profileId: ProfileId, pin: CharArray): PinCheck {
            deleteCalls++
            profiles.removeAll { it.id == profileId }
            deleted = true
            return PinCheck.Accepted
        }
        override suspend fun setVideoVisible(videoId: VideoId, visible: Boolean) { videoVisible = visible }
        override suspend fun deleteVideo(videoId: VideoId) { videoDeleted = true }
        override suspend fun clearCache() = Unit
        override suspend fun exportBackupJson() = "{}"
        override suspend fun saveBackupJson(json: String) = "Downloads/test.json"
    }

    companion object {
        private fun profile(id: String) = Profile(ProfileId(id), FamilyId("fixture-family"), id, "star",
            ProfileRole.CHILD, "child", "A1", emptyMap(), Instant.EPOCH, null)
    }
}
