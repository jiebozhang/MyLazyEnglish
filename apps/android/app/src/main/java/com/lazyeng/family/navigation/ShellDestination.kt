package com.lazyeng.family.navigation

import java.net.URI

enum class ChildTab(val route: String, val label: String) {
    HOME("home", "首页"), LIBRARY("library", "视频库"), VOCABULARY("vocabulary", "生词本"), ME("me", "我的")
}

sealed interface ShellDestination {
    data class Tab(val tab: ChildTab) : ShellDestination
    data object Profiles : ShellDestination
    data object Parent : ShellDestination
    data object Import : ShellDestination
    data class Video(val id: String, val parent: Boolean = false) : ShellDestination
    data class Repair(val id: String) : ShellDestination
    data class Player(val id: String, val positionMs: Long) : ShellDestination
    data object Dictionary : ShellDestination
    data object Review : ShellDestination
    data object NotFound : ShellDestination
}

object ShellRoutes {
    fun validId(value: String?) = value != null && Regex("[A-Za-z0-9_-]{1,128}").matches(value)

    fun route(destination: ShellDestination): String = when (destination) {
        is ShellDestination.Tab -> destination.tab.route
        ShellDestination.Profiles -> "profiles"
        ShellDestination.Parent -> "parent"
        ShellDestination.Import -> "import"
        is ShellDestination.Video -> if (validId(destination.id)) "${if (destination.parent) "parent-video" else "video"}/${destination.id}" else "not-found"
        is ShellDestination.Repair -> if (validId(destination.id)) "repair/${destination.id}" else "not-found"
        is ShellDestination.Player -> if (validId(destination.id) && destination.positionMs >= 0) "player/${destination.id}?positionMs=${destination.positionMs}" else "not-found"
        ShellDestination.Dictionary -> "dictionary"
        ShellDestination.Review -> "review"
        ShellDestination.NotFound -> "not-found"
    }

    // External links never supply a Profile, an authorization flag, or an arbitrary graph route.
    fun deepLink(raw: String): ShellDestination = try {
        val uri = URI(raw)
        val path = uri.path.orEmpty().removePrefix("/").split('/')
        when {
            uri.scheme != "lazyeng" || uri.host != "app" || uri.userInfo != null || uri.port != -1 || uri.fragment != null -> ShellDestination.NotFound
            path.size == 1 && uri.query == null -> when (path.single()) {
                "parent" -> ShellDestination.Parent
                else -> ChildTab.entries.firstOrNull { it.route == path.single() }?.let(ShellDestination::Tab) ?: ShellDestination.NotFound
            }
            path.size != 2 || !validId(path[1]) -> ShellDestination.NotFound
            path[0] == "video" && uri.query == null -> ShellDestination.Video(path[1])
            path[0] == "player" -> {
                val query = uri.query
                val position = if (query == null) 0L else if (Regex("positionMs=[0-9]+").matches(query)) query.substringAfter('=').toLongOrNull() else null
                if (position == null) ShellDestination.NotFound else ShellDestination.Player(path[1], position)
            }
            else -> ShellDestination.NotFound
        }
    } catch (_: Exception) { ShellDestination.NotFound }
}
