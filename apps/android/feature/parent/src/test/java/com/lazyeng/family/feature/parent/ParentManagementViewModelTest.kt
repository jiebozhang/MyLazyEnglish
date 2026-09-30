package com.lazyeng.family.feature.parent

import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.*
import com.lazyeng.family.core.security.PinCheck
import com.lazyeng.family.core.testing.TestDispatchers
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentManagementViewModelTest {
    private val testDispatchers = TestDispatchers()
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun uniqueChildCannotBeDeletedAndNoGatewayCallIsMade() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val gateway = FakeGateway(members = listOf(profile("only")))
        val model = model(gateway)
        advanceUntilIdle()
        model.onEvent(ParentManagementUiEvent.RequestDeleteMember(ProfileId("only")))
        assertTrue(model.state.value.error.orEmpty().contains("至少需保留一名"))
        assertEquals(0, gateway.deleteCalls)
    }

    @Test fun memberDeletionRequiresImpactConfirmationAndPinBeforeCallback() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val gateway = FakeGateway(members = listOf(profile("a"), profile("b")))
        var refreshed = false
        val model = ParentManagementViewModel(gateway, provider(), ParentManagementSection.MEMBERS) { refreshed = true }
        advanceUntilIdle()
        model.onEvent(ParentManagementUiEvent.RequestDeleteMember(ProfileId("a")))
        assertEquals(ParentManagementConfirmation.DeleteMember(profile("a")), model.state.value.confirmation)
        model.onEvent(ParentManagementUiEvent.Confirm)
        assertEquals(ParentManagementConfirmation.MemberPin, model.state.value.confirmation)
        assertEquals(0, gateway.deleteCalls)
        model.onEvent(ParentManagementUiEvent.SubmitDeletePin("1234".toCharArray()))
        advanceUntilIdle()
        assertEquals(1, gateway.deleteCalls)
        assertTrue(refreshed)
        assertEquals("孩子档案及其私有学习数据已删除。", model.state.value.message)
    }

    @Test fun clearCacheRequiresConfirmationAndBackupIsExportableWithoutSecrets() = runTest(testDispatchers.dispatcher) {
        Dispatchers.setMain(testDispatchers.dispatcher)
        val gateway = FakeGateway()
        val model = model(gateway, ParentManagementSection.STORAGE)
        advanceUntilIdle()
        model.onEvent(ParentManagementUiEvent.RequestClearCache)
        assertEquals(0, gateway.clearCalls)
        model.onEvent(ParentManagementUiEvent.Confirm)
        advanceUntilIdle()
        assertEquals(1, gateway.clearCalls)
        model.onEvent(ParentManagementUiEvent.ExportBackup)
        advanceUntilIdle()
        assertEquals("家庭 JSON 备份已保存：Downloads/backup.json", model.state.value.message)
    }

    private fun model(gateway: FakeGateway, section: ParentManagementSection = ParentManagementSection.MEMBERS) =
        ParentManagementViewModel(gateway, provider(), section)

    private fun provider() = object : DispatcherProvider {
        override val io = testDispatchers.dispatcher
        override val computation = testDispatchers.dispatcher
        override val main = testDispatchers.dispatcher
    }

    private class FakeGateway(var members: List<Profile> = listOf(profile("a"), profile("b"))) : ParentManagementGateway {
        var deleteCalls = 0
        var clearCalls = 0
        override suspend fun load(section: ParentManagementSection) = ParentManagementSnapshot(
            members = members, storage = StorageUsage(1, 2, 3, 4),
        )
        override suspend fun createMember(nickname: String, level: String, avatarId: String) = Unit
        override suspend fun deleteMember(profileId: ProfileId, pin: CharArray): PinCheck {
            deleteCalls++
            members = members.filterNot { it.id == profileId }
            return PinCheck.Accepted
        }
        override suspend fun setVideoVisible(videoId: VideoId, visible: Boolean) = Unit
        override suspend fun deleteVideo(videoId: VideoId) = Unit
        override suspend fun clearCache() { clearCalls++ }
        override suspend fun exportBackupJson() = "{\"format\":\"fixture\"}"
        override suspend fun saveBackupJson(json: String) = "Downloads/backup.json"
    }

    companion object {
        private fun profile(id: String) = Profile(ProfileId(id), FamilyId("family"), id, "sun", ProfileRole.CHILD,
            "child", "A1", emptyMap(), Instant.EPOCH, null)
    }
}
