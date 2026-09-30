package com.lazyeng.family

import com.lazyeng.family.navigation.*
import org.junit.Assert.*
import org.junit.Test

class ShellRoutesTest {
    @Test fun acceptsFourTabsAndParentWithoutAnAuthorizationFlag() {
        ChildTab.entries.forEach { assertEquals(ShellDestination.Tab(it), ShellRoutes.deepLink("lazyeng://app/${it.route}")) }
        assertEquals(ShellDestination.Parent, ShellRoutes.deepLink("lazyeng://app/parent"))
        assertEquals(ShellDestination.NotFound, ShellRoutes.deepLink("lazyeng://app/parent?authorized=true"))
    }
    @Test fun reviewOriginKeepsExactVideoAndTimestamp() {
        val target = ShellDestination.Player("fixture-video", 12345)
        assertEquals(target, ShellRoutes.deepLink("lazyeng://app/player/fixture-video?positionMs=12345"))
        assertEquals("player/fixture-video?positionMs=12345", ShellRoutes.route(target))
        assertEquals(ShellDestination.Player("fixture-video", 0), ShellRoutes.deepLink("lazyeng://app/player/fixture-video"))
    }
    @Test fun malformedAndUntrustedRequestsAreExplicitErrors() {
        listOf("", "not a uri", "https://app/video/a", "lazyeng://other/video/a", "lazyeng://user@app/video/a",
            "lazyeng://app:12/video/a", "lazyeng://app/video/", "lazyeng://app/video/a/b", "lazyeng://app/video/%2F",
            "lazyeng://app/video/a#fragment", "lazyeng://app/import", "lazyeng://app/video/a?profileId=other",
            "lazyeng://app/player/a?positionMs=-1", "lazyeng://app/player/a?positionMs=9223372036854775808",
            "lazyeng://app/player/a?positionMs=0&authorized=true").forEach {
            assertEquals(it, ShellDestination.NotFound, ShellRoutes.deepLink(it))
        }
    }
    @Test fun internalInvalidIdsAndPositionsCannotReachArgumentParsing() {
        assertEquals("not-found", ShellRoutes.route(ShellDestination.Player("good", -1)))
        assertEquals("not-found", ShellRoutes.route(ShellDestination.Video("../parent")))
        assertEquals("not-found", ShellRoutes.route(ShellDestination.Repair("")))
        assertEquals(ShellDestination.Video("fixture-video"), ShellRoutes.deepLink("lazyeng://app/video/fixture-video"))
    }
}
