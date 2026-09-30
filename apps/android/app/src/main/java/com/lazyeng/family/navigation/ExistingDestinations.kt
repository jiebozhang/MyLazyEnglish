package com.lazyeng.family.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lazyeng.family.LazyEngApplication
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.feature.importmedia.*
import com.lazyeng.family.feature.library.*
import com.lazyeng.family.feature.player.Media3PlayerEngine
import com.lazyeng.family.feature.player.PlayerRoute
import com.lazyeng.family.feature.player.PlayerViewModel
import com.lazyeng.family.feature.profiles.*
import com.lazyeng.family.feature.vocabulary.VocabularyUiEffect
import com.lazyeng.family.feature.vocabulary.VocabularyViewModel
import com.lazyeng.family.feature.vocabulary.VocabularyRoute
import com.lazyeng.family.feature.vocabulary.FlashcardReviewRoute
import com.lazyeng.family.feature.vocabulary.FlashcardReviewViewModel
import com.lazyeng.family.feature.vocabulary.FlashcardReviewEffect
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.feature.parent.ParentDashboardRoute
import com.lazyeng.family.feature.parent.ParentDashboardViewModel
import com.lazyeng.family.feature.parent.LlmSettingsRoute
import com.lazyeng.family.feature.parent.LlmSettingsViewModel
import com.lazyeng.family.feature.parent.ParentManagementSection
import com.lazyeng.family.feature.parent.ParentManagementViewModel
import com.lazyeng.family.feature.parent.ParentManagementRoute
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

internal fun modelFactory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
fun ExistingDestination(dependencies: LazyEngApplication, target: ShellDestination, back: () -> Unit, go: (ShellDestination) -> Unit) {
    when (target) {
        ShellDestination.Profiles, ShellDestination.Parent -> {
            val model: ProfilesViewModel = viewModel(factory = modelFactory { ProfilesViewModel(dependencies::profileCoordinator, dependencies.clock) })
            ProfilesNavigation(model, target == ShellDestination.Parent, back, go,
                llmSettingsFactory = { LlmSettingsViewModel(dependencies.llmSettingsGateway, dependencies.llmConfigValidator, dependencies.dispatchers) },
                managementFactory = { section, onDeleted -> ParentManagementViewModel(
                    dependencies.parentManagementGateway(model.requireAuthorizedParentSession()), dependencies.dispatchers, section, onDeleted) }) { profiles ->
                ParentDashboardViewModel(
                    profiles = profiles,
                    initialProfileId = model.state.value.current?.id,
                    source = dependencies.parentDashboardDataSource(model.requireAuthorizedParentSession()),
                    dispatchers = dependencies.dispatchers,
                    onInteraction = model::touchAuthorizedParentSession,
                )
            }
        }
        ShellDestination.Import, is ShellDestination.Repair -> {
            val repairId = (target as? ShellDestination.Repair)?.id?.let(::VideoId)
            val model: ImportViewModel = viewModel(factory = modelFactory { ImportViewModel({ dependencies.importSession(repairId) }, dependencies.clock) })
            ImportRoute(model, back, { go(ShellDestination.Video(it.value)) })
        }
        is ShellDestination.Video -> {
            val model: VideoDetailViewModel = viewModel(factory = modelFactory {
                VideoDetailViewModel({ dependencies.detailSession(VideoId(target.id)) }, target.parent, dependencies.clock,
                    dependencies.profileChanges.events.map { Unit })
            })
            val effect: (VideoDetailEffect) -> Unit = { when (it) {
                is VideoDetailEffect.Watch -> go(ShellDestination.Player(it.videoId.value, it.positionMs))
                is VideoDetailEffect.Repair -> go(ShellDestination.Repair(it.videoId.value))
            } }
            if (target.parent) ParentVideoDetailRoute(model, back, effect)
            else ChildVideoDetailRoute(model, back, { go(target.copy(parent = true)) }, effect)
        }
        is ShellDestination.Player -> {
            val model: PlayerViewModel = viewModel(factory = modelFactory {
                PlayerViewModel(
                    loadSession = { dependencies.playerSession(VideoId(target.id), target.positionMs) },
                    engineFactory = { Media3PlayerEngine(dependencies) },
                    clock = dependencies.clock,
                    dispatchers = dependencies.dispatchers,
                )
            })
            PlayerRoute(model, back)
        }
        ShellDestination.Review -> {
            val model: FlashcardReviewViewModel = viewModel(factory = modelFactory {
                FlashcardReviewViewModel(dependencies::flashcardReviewSession)
            })
            val context = androidx.compose.ui.platform.LocalContext.current
            val effectHandler: (FlashcardReviewEffect) -> Unit = { effect ->
                when (effect) {
                    FlashcardReviewEffect.Exit -> back()
                    is FlashcardReviewEffect.OpenPlayer -> go(ShellDestination.Player(effect.videoId.value, effect.timestampMs))
                    is FlashcardReviewEffect.Message -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
                }
            }
            FlashcardReviewRoute(model, effectHandler, back)
        }
        else -> NavigationError(back)
    }
}

@Composable
fun ProfilesNavigation(
    model: ProfilesViewModel,
    parent: Boolean,
    back: () -> Unit,
    go: (ShellDestination) -> Unit,
    llmSettingsFactory: () -> LlmSettingsViewModel = { error("LLM settings factory required") },
    managementFactory: (ParentManagementSection, () -> Unit) -> ParentManagementViewModel = { _, _ -> error("Management factory required") },
    dashboardFactory: (List<com.lazyeng.family.core.model.Profile>) -> ParentDashboardViewModel = { error("Dashboard factory required") },
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.phase, parent) {
        if (state.phase == ProfilesPhase.HOME || (parent && state.phase == ProfilesPhase.PICKER)) {
            model.onEvent(if (parent) ProfilesUiEvent.Parent else ProfilesUiEvent.Picker)
        }
    }
    LaunchedEffect(model) { model.effects.collect { if (it == ProfilesUiEffect.ProfileSelected) go(ShellDestination.Tab(ChildTab.HOME)) } }
    DisposableEffect(model) { onDispose { model.onBackground() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle, parent) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.onBackground()
        }
        if (parent) lifecycle.addObserver(observer)
        onDispose { if (parent) lifecycle.removeObserver(observer) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showingLlmSettings by remember { mutableStateOf(false) }
    var managementSection by remember { mutableStateOf<ParentManagementSection?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (parent && state.phase == ProfilesPhase.OVERVIEW) {
            val selectedManagement = managementSection
            if (selectedManagement != null) {
                val management: ParentManagementViewModel = viewModel(
                    key = "parent-management-${selectedManagement.name}",
                    factory = modelFactory { managementFactory(selectedManagement, model::refreshAuthorizedOverview) },
                )
                ParentManagementRoute(management, onBack = { managementSection = null })
                return@Column
            }
            if (showingLlmSettings) {
                val settings: LlmSettingsViewModel = viewModel(
                    key = "parent-llm-settings",
                    factory = modelFactory(llmSettingsFactory),
                )
                LlmSettingsRoute(settings) { showingLlmSettings = false }
                return@Column
            }
            val dashboard: ParentDashboardViewModel = viewModel(
                key = "parent-dashboard",
                factory = modelFactory { dashboardFactory(state.members) },
            )
            LaunchedEffect(state.phase, state.members, state.current?.id) {
                dashboard.refreshAuthorizedProfiles(state.members, state.current?.id)
            }
            ParentDashboardRoute(
                viewModel = dashboard,
                onBackToChild = back,
                onImportVideo = { go(ShellDestination.Import) },
                onLlmSettings = { showingLlmSettings = true },
                onManagement = { managementSection = it },
                onNotAvailable = { action -> Toast.makeText(context, "${action}尚未开放。", Toast.LENGTH_SHORT).show() },
            )
        } else {
            TextButton(onClick = back) { Text("返回") }
            Box(Modifier.weight(1f)) { ProfilesRoute(model) }
        }
    }
}

@Composable
fun HomeTabDestination(
    dependencies: LazyEngApplication,
    memory: TabMemory,
    go: (ShellDestination) -> Unit,
) {
    val model: com.lazyeng.family.feature.home.HomeViewModel = viewModel(factory = modelFactory {
        com.lazyeng.family.feature.home.HomeViewModel(
            factory = dependencies::homeSession,
            clock = dependencies.clock,
            invalidations = dependencies.profileChanges.events.map { Unit },
        )
    })
    val effectHandler: (com.lazyeng.family.feature.home.HomeUiEffect) -> Unit = { effect ->
        when (effect) {
            is com.lazyeng.family.feature.home.HomeUiEffect.NavigateToPlayer ->
                go(ShellDestination.Player(effect.videoId.value, effect.positionMs))
            is com.lazyeng.family.feature.home.HomeUiEffect.NavigateToVideoDetail ->
                go(ShellDestination.Video(effect.videoId.value))
            com.lazyeng.family.feature.home.HomeUiEffect.NavigateToVocabulary ->
                go(ShellDestination.Tab(ChildTab.VOCABULARY))
            com.lazyeng.family.feature.home.HomeUiEffect.NavigateToLibrary ->
                go(ShellDestination.Tab(ChildTab.LIBRARY))
            com.lazyeng.family.feature.home.HomeUiEffect.NavigateToProfiles ->
                go(ShellDestination.Profiles)
            com.lazyeng.family.feature.home.HomeUiEffect.NavigateToParent ->
                go(ShellDestination.Parent)
        }
    }
    com.lazyeng.family.feature.home.HomeRoute(
        model = model,
        listState = memory.list,
        onEffect = effectHandler,
    )
}

@Composable
fun LibraryTabDestination(dependencies: LazyEngApplication, memory: TabMemory, go: (ShellDestination) -> Unit) {
    val model: LibraryViewModel = viewModel(factory = modelFactory {
        LibraryViewModel(
            factory = dependencies::librarySession,
            audit = dependencies.contentOverrideAudit,
            clock = dependencies.clock,
            invalidations = dependencies.profileChanges.events.map { Unit },
        )
    })
    val filters = LibraryFilters(
        query = memory.filter.value,
        level = memory.level.value,
        topic = memory.topic.value,
        source = memory.source.value,
        subtitle = runCatching { LibrarySubtitleFilter.valueOf(memory.subtitle.value) }.getOrDefault(LibrarySubtitleFilter.ALL),
    )
    LibraryRoute(model, filters, { value ->
        memory.filter.value = value.query
        memory.level.value = value.level
        memory.topic.value = value.topic
        memory.source.value = value.source
        memory.subtitle.value = value.subtitle.name
    }, memory.list) { videoId, parent -> go(ShellDestination.Video(videoId.value, parent)) }
}

@Composable
fun MineTabDestination(dependencies: LazyEngApplication, memory: TabMemory, go: (ShellDestination) -> Unit) {
    val model: MineViewModel = viewModel(factory = modelFactory {
        MineViewModel(
            loadProfile = {
                val profile = dependencies.profileCoordinator().current() ?: return@MineViewModel null
                val dashboard = dependencies.homeSession().data.loadDashboard()
                    ?.takeIf { it.profile.profileId == profile.id } ?: return@MineViewModel null
                MineProfileInfo(profile.nickname, profile.avatarId, profile.ageMode, profile.englishLevel,
                    dashboard.streakInfo.streakDays, dashboard.streakInfo.message)
            },
            invalidations = dependencies.profileChanges.events.map { Unit },
        )
    })
    val effectHandler: (MineUiEffect) -> Unit = { effect ->
        when (effect) {
            MineUiEffect.NavigateToProfiles -> go(ShellDestination.Profiles)
            MineUiEffect.NavigateToParentGate -> go(ShellDestination.Parent)
        }
    }
    MineRoute(model, memory.list, effectHandler)
}

@Composable
fun VocabularyTabDestination(dependencies: LazyEngApplication, memory: TabMemory, go: (ShellDestination) -> Unit) {
    val initialStatus = memory.vocabularyStatus.value.takeIf(String::isNotEmpty)
        ?.let { runCatching { VocabularyStatus.valueOf(it) }.getOrNull() }
    val model: VocabularyViewModel = viewModel(factory = modelFactory {
        VocabularyViewModel(
            createSession = dependencies::vocabularySession,
            invalidations = dependencies.profileChanges.events.map { Unit },
            initialStatus = initialStatus,
            initialSource = memory.vocabularySource.value.takeIf(String::isNotEmpty),
        )
    })
    val context = androidx.compose.ui.platform.LocalContext.current
    val effectHandler: (VocabularyUiEffect) -> Unit = { effect ->
        when (effect) {
            is VocabularyUiEffect.OpenPlayer -> go(ShellDestination.Player(effect.videoId.value, effect.timestampMs))
            is VocabularyUiEffect.Message -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
        }
    }
    VocabularyRoute(
        model = model,
        listState = memory.list,
        onEffect = effectHandler,
        onStartReview = { go(ShellDestination.Review) },
        onFiltersChanged = { status, source ->
            memory.vocabularyStatus.value = status?.name.orEmpty()
            memory.vocabularySource.value = source.orEmpty()
        },
    )
}
