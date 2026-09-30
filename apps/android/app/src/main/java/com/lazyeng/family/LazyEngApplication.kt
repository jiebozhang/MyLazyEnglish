package com.lazyeng.family

import android.app.Application
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.database.FamilyDatabase
import com.lazyeng.family.core.database.RoomProfileCleaner
import com.lazyeng.family.core.datastore.DataStoreCurrentProfileStore
import com.lazyeng.family.core.datastore.PreferencesOnboardingDraftStore
import com.lazyeng.family.core.datastore.PreferencesRestrictedContentOverrideAudit
import com.lazyeng.family.core.security.KeystorePinRecordStore
import com.lazyeng.family.core.security.PinService
import com.lazyeng.family.feature.onboarding.OnboardingCoordinator
import java.time.Instant
import java.util.UUID
import com.lazyeng.family.core.common.ProfileChanges
import com.lazyeng.family.core.common.ProfileDeletionCoordinator
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.security.ParentSession
import com.lazyeng.family.feature.profiles.ProfileCoordinator
import com.lazyeng.family.core.common.ImportErrors
import com.lazyeng.family.core.common.ImportException
import com.lazyeng.family.core.media.SafImportDocuments
import com.lazyeng.family.core.media.SafMediaReadiness
import com.lazyeng.family.feature.importmedia.ImportSession
import com.lazyeng.family.feature.importmedia.VideoImportCoordinator
import com.lazyeng.family.feature.library.VideoDetailData
import com.lazyeng.family.feature.library.VideoDetailSession
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.model.FileReference
import com.lazyeng.family.core.model.ProfileRole
import com.lazyeng.family.core.model.WatchProgress
import com.lazyeng.family.core.model.VocabularyCapture
import com.lazyeng.family.core.network.ContextExplanationInput
import com.lazyeng.family.core.network.ContextExplanationUseCase
import com.lazyeng.family.core.network.AiProviderFactory
import com.lazyeng.family.core.network.BaseUrlPolicy
import com.lazyeng.family.core.network.LlmConfigValidator
import com.lazyeng.family.core.network.LlmProviderOption
import com.lazyeng.family.core.network.LlmSettingsDraft
import com.lazyeng.family.core.network.RedactingNetworkObserver
import com.lazyeng.family.core.network.SafeLogSink
import com.lazyeng.family.core.network.configuredBaseUrlPolicy
import com.lazyeng.family.core.security.KeystoreSecureSecretStore
import com.lazyeng.family.feature.parent.LlmSettingsGateway
import com.lazyeng.family.core.model.SecretReference
import com.lazyeng.family.core.security.ParentVideoAccess
import com.lazyeng.family.feature.player.PlayerPlaybackSession
import com.lazyeng.family.feature.player.PlayerSubtitleCue
import com.lazyeng.family.feature.player.PlayerSessionResult
import com.lazyeng.family.feature.player.PlayerDictionaryLookup
import com.lazyeng.family.feature.dictionary.LocalDictionaryLookupUseCase
import com.lazyeng.family.feature.dictionary.SqliteDictionaryRepository
import com.lazyeng.family.feature.dictionary.AssetManagerDictionarySource
import com.lazyeng.family.feature.dictionary.DictionaryAssetProvisioner
import com.lazyeng.family.feature.dictionary.DictionaryProvisioningException
import com.lazyeng.family.feature.dictionary.LookupEventCoordinator
import com.lazyeng.family.feature.dictionary.LookupRequest
import com.lazyeng.family.feature.vocabulary.VocabularySession
import com.lazyeng.family.feature.vocabulary.FlashcardReviewSession
import com.lazyeng.family.feature.parent.ParentDashboardData
import com.lazyeng.family.feature.parent.ParentDashboardDataSource
import com.lazyeng.family.feature.parent.RecentLearningItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.ZoneId

class LazyEngApplication : Application() {
    val clock = Clock { Instant.now() }
    val dispatchers = object : com.lazyeng.family.core.common.DispatcherProvider {
        override val io = Dispatchers.IO
        override val computation = Dispatchers.Default
        override val main = Dispatchers.Main.immediate
    }
    private val database by lazy { FamilyDatabase.open(this) }
    @Volatile private var contextExplanationRuntime: Pair<ContextExplanationUseCase, String>? = null

    fun configureContextExplanation(useCase: ContextExplanationUseCase, providerId: String) {
        contextExplanationRuntime = useCase to providerId
    }

    fun clearContextExplanation() {
        contextExplanationRuntime = null
    }
    private val secureSecrets by lazy { KeystoreSecureSecretStore.create(this) }
    private val safeNetworkObserver by lazy { RedactingNetworkObserver(SafeLogSink { }) }
    private val aiProviderFactory by lazy { AiProviderFactory(secureSecrets, dispatchers, safeNetworkObserver) }
    val llmConfigValidator by lazy { LlmConfigValidator(configuredBaseUrlPolicy()) }
    val llmSettingsGateway: LlmSettingsGateway by lazy {
        SecureLlmSettingsGateway(this, secureSecrets, aiProviderFactory, llmConfigValidator, ::activateContextExplanation)
    }

    private fun activateContextExplanation(draft: LlmSettingsDraft) {
        if (!draft.enabled) {
            clearContextExplanation()
            return
        }
        val provider = aiProviderFactory.create(draft.provider.kind)
        val config = com.lazyeng.family.core.common.AiProviderConfig(
            baseUrl = draft.baseUrl.trim(), model = draft.model.trim(),
            secretReference = SecretReference("llm-provider-${draft.provider.name.lowercase()}-key"),
            timeout = kotlin.time.Duration.parse("${draft.timeoutSeconds}s"),
            apiKeyOptional = draft.provider.keyOptional,
            allowPrivateNetworkHttp = draft.provider == LlmProviderOption.OLLAMA,
        )
        val useCase = ContextExplanationUseCase(provider, config, database.aiContentRepository(), database.aiJobRepository(),
            database.aiBudgetUsageRepository(), dispatchers, lookupScope)
        configureContextExplanation(useCase, draft.provider.name.lowercase())
    }
    private val lookupScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.io) }
    private val lookupEventCoordinator by lazy {
        LookupEventCoordinator(
            scope = lookupScope,
            events = database.lookupEventRepository(),
            clock = clock,
            newId = { UUID.randomUUID().toString() },
            isProfileCurrent = { requestedProfile ->
                val familyId = drafts.loadOrCreate().familyId
                selection.getCurrentProfileId(familyId) == requestedProfile
            },
            query = { request -> lookupDictionary(request.sentence, request.start, request.end) },
        )
    }
    private val vocabularyRepository by lazy { database.vocabularyRepository(this) }
    private val profiles by lazy { database.profileRepository() }
    private val drafts by lazy { PreferencesOnboardingDraftStore.create(this) }
    private val selection by lazy { DataStoreCurrentProfileStore.create(this, profiles) }
    val contentOverrideAudit by lazy { PreferencesRestrictedContentOverrideAudit.create(this) }
    private val pins by lazy { PinService(KeystorePinRecordStore.create(this), clock) }
    private val dictionaryRepository by lazy {
        SqliteDictionaryRepository(java.io.File(filesDir, "dictionary/ecdictionary.sqlite"), dispatchers)
    }
    private val dictionaryLookup by lazy { LocalDictionaryLookupUseCase(dictionaryRepository) }
    private val dictionaryProvisioner by lazy {
        DictionaryAssetProvisioner(
            AssetManagerDictionarySource(assets),
            java.io.File(filesDir, "dictionary"),
            dispatchers,
        )
    }
    val dictionaryProvisioningState get() = dictionaryProvisioner.state

    suspend fun ensureDictionaryReady(): java.io.File = dictionaryProvisioner.ensureReady()

    suspend fun vocabularySession(): VocabularySession? {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: return null
        if (profiles.getProfile(familyId, profileId) == null) return null
        return VocabularySession(
            profileId = profileId,
            repository = vocabularyRepository,
            lookupEntry = { lemma ->
                dictionaryProvisioner.ensureReady()
                dictionaryRepository.findEntry(lemma)
            },
            canOpenVideo = { videoId ->
                if (selection.getCurrentProfileId(familyId) == profileId) {
                    val detail = runCatching { detailSession(videoId).data.child() }.getOrNull()
                    detail?.availability == com.lazyeng.family.feature.library.DetailAvailability.READY &&
                        selection.getCurrentProfileId(familyId) == profileId
                } else false
            },
            now = { clock.now() },
        )
    }

    suspend fun flashcardReviewSession(): FlashcardReviewSession? {
        val session = vocabularySession() ?: return null
        return FlashcardReviewSession(
            profileId = session.profileId,
            repository = session.repository,
            now = session.now,
            newEventId = { UUID.randomUUID().toString() },
            lookupEntry = session.lookupEntry,
            canOpenVideo = session.canOpenVideo,
        )
    }

    suspend fun lookupDictionary(sentence: String, start: Int, end: Int): com.lazyeng.family.feature.dictionary.LocalDictionaryLookup? {
        dictionaryProvisioner.ensureReady()
        return dictionaryLookup(sentence, start, end)
    }
    val profileChanges = ProfileChanges()
    override fun onCreate() {
        super.onCreate()
        lookupScope.launch {
            runCatching { llmSettingsGateway.load() }.getOrNull()?.let { saved ->
                val keyPresent = saved.key.exists || saved.draft.provider.keyOptional
                if (saved.draft.enabled && keyPresent && llmConfigValidator.validate(saved.draft, "", saved.key.exists).isEmpty()) {
                    activateContextExplanation(saved.draft)
                }
            }
        }
        lookupScope.launch {
            profileChanges.events.collect { change -> lookupEventCoordinator.activateProfile(change.profileId) }
        }
    }
    val profileDeletions by lazy {
        ProfileDeletionCoordinator(database.profileDeletionTransaction()).apply {
            register("profile-private-data", RoomProfileCleaner(database))
        }
    }
    fun onboardingCoordinator() = OnboardingCoordinator(drafts, pins, database.familyRepository(), profiles, selection)
    suspend fun navigationScope(): String? {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: return null
        return "${familyId.value}:${profileId.value}"
    }
    suspend fun profileCoordinator(): ProfileCoordinator {
        val familyId = drafts.loadOrCreate().familyId
        return ProfileCoordinator(familyId, profiles, database.profileDirectory(), selection,
            ParentSession(familyId, pins, clock), profileDeletions, profileChanges, clock,
            { ProfileId(UUID.randomUUID().toString()) })
    }

    fun parentManagementGateway(parentSession: ParentSession): com.lazyeng.family.feature.parent.ParentManagementGateway {
        val familyId = parentSession.familyId
        val coordinator = ProfileCoordinator(familyId, profiles, database.profileDirectory(), selection,
            parentSession, profileDeletions, profileChanges, clock, { ProfileId(UUID.randomUUID().toString()) })
        return ParentManagementGatewayImpl(this, familyId, database, coordinator, parentSession, dispatchers) { clock.now() }
    }

    fun parentDashboardDataSource(parentSession: ParentSession): ParentDashboardDataSource = ParentDashboardDataSource { profileId ->
        val scope = parentSession.requireScope()
        val profile = profiles.getProfile(scope.familyId, profileId)
            ?.takeIf { it.deletedAt == null } ?: error("Profile is unavailable")
        val catalog = database.videoRepository(SafMediaReadiness(SafImportDocuments(this)), parentSession = parentSession)
        val parentCatalog = catalog as? ParentVideoAccess ?: error("Parent catalog access unavailable")
        val progress = database.watchProgressRepository().listProgress(profileId)
        val videos = parentCatalog.listForAuthorizedParent(profileId, scope).associateBy { it.id }
        val vocabulary = vocabularyRepository.listItems(profileId)
        parentSession.checkScope(scope)
        val zone = ZoneId.systemDefault()
        val today = clock.now().atZone(zone).toLocalDate()
        val watchedDates = progress.map { it.updatedAt.atZone(zone).toLocalDate() }.toSet()
        var streak = 0
        var day = if (today in watchedDates) today else today.minusDays(1)
        while (day in watchedDates) {
            streak++
            day = day.minusDays(1)
        }
        val recent = progress.sortedByDescending { it.updatedAt }.mapNotNull { item ->
            val video = videos[item.videoId] ?: return@mapNotNull null
            RecentLearningItem(
                videoId = item.videoId,
                title = video.metadata.title,
                updatedAt = item.updatedAt,
                completionPercent = (item.completion.coerceIn(0f, 1f) * 100).toInt(),
            )
        }.distinctBy { it.videoId }.take(5)
        parentSession.checkScope(scope)
        ParentDashboardData(
            profileId = profile.id,
            streakDays = streak,
            vocabularyCount = vocabulary.size,
            masteredCount = vocabulary.count { it.status == com.lazyeng.family.core.model.VocabularyStatus.MASTERED },
            recentItems = recent,
        )
    }
    suspend fun detailSession(videoId: VideoId): VideoDetailSession {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: throw ImportException(ImportErrors.scope)
        val documents = SafImportDocuments(this)
        val parent = ParentSession(familyId, pins, clock)
        val videos = database.videoRepository(SafMediaReadiness(documents), parentSession = parent)
        return VideoDetailSession(VideoDetailData(profileId, videoId,
            videos, database.subtitleRepository(),
            database.watchProgressRepository(), database.subtitleReadinessChecker(), documents::checkAccess,
            { selection.getCurrentProfileId(familyId) == profileId && profiles.getProfile(familyId, profileId) != null }), parent)
    }
    suspend fun playerSession(videoId: VideoId, requestedPositionMs: Long): PlayerSessionResult {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId)
            ?: return PlayerSessionResult.Failed("当前档案不可用，请返回后重新选择档案。")
        lookupEventCoordinator.activateProfile(profileId)
        suspend fun scopeValid() = selection.getCurrentProfileId(familyId) == profileId &&
            profiles.getProfile(familyId, profileId) != null
        if (!scopeValid()) return PlayerSessionResult.Failed("当前档案已变化，请返回并重新打开视频。")
        return try {
            val activeProfile = profiles.getProfile(familyId, profileId)
                ?: return PlayerSessionResult.Failed("当前档案已变化，请返回并重新打开视频。")
            val detail = detailSession(videoId).data.child()
                ?: return PlayerSessionResult.Failed("找不到这个视频，请返回视频库重新选择。")
            if (!detail.canWatch) return PlayerSessionResult.Failed("视频尚未准备好播放，请返回详情页查看状态。")
            val video = database.videoRepository().getVideo(profileId, videoId)
                ?: return PlayerSessionResult.Failed("找不到这个视频，请返回视频库重新选择。")
            val duration = detail.durationMs?.takeIf { it > 0L }
                ?: return PlayerSessionResult.Failed("视频时长无效，请让家长重新导入。")
            val persisted = database.watchProgressRepository().getProgress(profileId, videoId)?.positionMs ?: detail.positionMs
            val requested = requestedPositionMs.takeIf { it > 0L } ?: persisted
            val startPosition = requested.coerceIn(0L, duration)
            val subtitleRepository = database.subtitleRepository()
            var subtitleVersionId = "none"
            val subtitleCues = try {
                val track = subtitleRepository.listTracks(profileId, videoId)
                    .firstOrNull { it.language.startsWith("en", ignoreCase = true) && it.currentVersionId != null }
                val versionId = track?.currentVersionId
                subtitleVersionId = versionId?.value ?: "none"
                if (versionId == null || subtitleRepository.getPublishedVersion(profileId, versionId) == null) emptyList()
                else subtitleRepository.listLines(profileId, versionId, 0, Int.MAX_VALUE).map { line ->
                    val (english, chinese) = com.lazyeng.family.feature.player.splitSubtitleLanguages(line.text)
                    PlayerSubtitleCue(line.id, line.sequence, line.startMs, line.endMs, english, chinese)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (!scopeValid()) return PlayerSessionResult.Failed("当前档案已变化，请返回并重新打开视频。")
            PlayerSessionResult.Ready(
                PlayerPlaybackSession(
                    profileId = profileId,
                    videoId = videoId.value,
                    learnerLevel = activeProfile.englishLevel,
                    uiLanguage = "zh-CN",
                    title = detail.title,
                    uri = video.sourceReference.value,
                    durationMs = duration,
                    resumePositionMs = startPosition,
                    subtitles = subtitleCues,
                    isWordSaved = { lemma ->
                        scopeValid() && vocabularyRepository.isWordSaved(profileId, lemma).first()
                    },
                    listSavedWords = {
                        if (scopeValid()) vocabularyRepository.listSavedLemmas(profileId) else emptySet()
                    },
                    lookupWord = { surface, lemma, lineId, sentence, start, end ->
                        try {
                            val result = lookupEventCoordinator.lookup(
                                LookupRequest(profileId, videoId, surface, lemma, lineId, sentence, start, end),
                            )
                            PlayerDictionaryLookup(entry = result?.entry, lemma = result?.matchedLemma)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: DictionaryProvisioningException) {
                            PlayerDictionaryLookup(null, unavailableMessage = failure.message)
                        }
                    },
                    explainContext = { request, localMeaning, forceRegenerate ->
                        val runtime = contextExplanationRuntime
                        if (runtime == null) null else runtime.first.execute(ContextExplanationInput(
                                familyId = familyId,
                                request = request,
                                lineVersion = subtitleVersionId,
                                providerId = runtime.second,
                                localMeaning = localMeaning,
                                forceRegenerate = forceRegenerate,
                            )).explanation
                    },
                    saveWord = { capture ->
                        if (!scopeValid() || capture.profileId != profileId) false
                        else vocabularyRepository.saveWord(
                            VocabularyCapture(
                                profileId = profileId,
                                surface = capture.surface,
                                lemma = capture.lemma,
                                phonetic = capture.dictionaryEntry?.phonetic,
                                definition = capture.dictionaryEntry?.senses?.firstOrNull(),
                                videoId = videoId,
                                lineId = capture.lineId,
                                contextSentence = capture.sentence,
                                timestampMs = capture.timestampMs,
                                createdAt = clock.now(),
                            ),
                        ).isSuccess
                    },
                    scopeValid = ::scopeValid,
                    saveProgress = { positionMs, durationMs ->
                        if (scopeValid()) database.watchProgressRepository().saveProgress(
                            profileId,
                            WatchProgress(
                                profileId = profileId,
                                videoId = videoId,
                                positionMs = positionMs.coerceIn(0L, durationMs),
                                completion = (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                                updatedAt = clock.now(),
                            ),
                        )
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImportException) {
            PlayerSessionResult.Failed(failure.failure.message)
        } catch (_: Exception) {
            PlayerSessionResult.Failed("无法读取视频，请检查文件是否仍可访问后重试。")
        }
    }
    suspend fun importSession(repairVideoId: VideoId? = null): ImportSession {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: throw ImportException(ImportErrors.scope)
        val parent = ParentSession(familyId, pins, clock)
        val documents = SafImportDocuments(this)
        val videos = database.videoRepository(SafMediaReadiness(documents), parentSession = parent)
        val parentVideos = videos as? ParentVideoAccess ?: error("Authorized video access unavailable")
        val coordinator = VideoImportCoordinator(profileId, documents,
            videos, database.videoAssetRepository(),
            database.importJobRepository(), database.subtitleRepository(), database.importTransaction(),
            authorize = {
                if (!parent.authorized() || selection.getCurrentProfileId(familyId) != profileId ||
                    profiles.getProfile(familyId, profileId) == null) throw ImportException(ImportErrors.scope)
                parent.checkScope(parent.requireScope())
            }, clock = clock, newId = { UUID.randomUUID().toString() },
            listVideosInAuthorizedScope = {
                val scope = parent.requireScope()
                parentVideos.listForAuthorizedParent(profileId, scope)
            },
            getVideoInAuthorizedScope = { videoId ->
                val scope = parent.requireScope()
                parentVideos.getForAuthorizedParent(profileId, videoId, scope)
            })
        val repair = repairVideoId?.let { database.videoRepository().getVideo(profileId, it) }
        if (repairVideoId != null && repair == null) throw ImportException(ImportErrors.scope)
        return ImportSession(parent, coordinator, repair?.sourceReference?.value?.let(::FileReference), repairVideoId)
    }
    suspend fun homeSession(): com.lazyeng.family.feature.home.HomeSession {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: throw ImportException(ImportErrors.scope)
        val documents = SafImportDocuments(this)
        return com.lazyeng.family.feature.home.HomeSession(
            com.lazyeng.family.feature.home.HomeData(
                familyId = familyId,
                profileId = profileId,
                profiles = profiles,
                videos = database.videoRepository(SafMediaReadiness(documents)),
                progress = database.watchProgressRepository(),
                scopeValid = { selection.getCurrentProfileId(familyId) == profileId && profiles.getProfile(familyId, profileId) != null },
                clock = clock,
            )
        )
    }

    suspend fun librarySession(): com.lazyeng.family.feature.library.LibrarySession {
        val familyId = drafts.loadOrCreate().familyId
        val profileId = selection.getCurrentProfileId(familyId) ?: throw ImportException(ImportErrors.scope)
        val profile = profiles.getProfile(familyId, profileId) ?: throw ImportException(ImportErrors.scope)
        val parent = ParentSession(familyId, pins, clock)
        val documents = SafImportDocuments(this)
        val catalog = database.videoRepository(SafMediaReadiness(documents), parentSession = parent)
        val access = catalog as? ParentVideoAccess ?: error("Authorized video access unavailable")
        return com.lazyeng.family.feature.library.LibrarySession(
            com.lazyeng.family.feature.library.LibraryData(
                familyId = familyId,
                profileId = profileId,
                role = profile.role,
                catalog = catalog,
                parentAccess = access,
                scopeValid = { selection.getCurrentProfileId(familyId) == profileId && profiles.getProfile(familyId, profileId) != null },
            ), parent, profile.role == ProfileRole.CHILD,
        )
    }
}
