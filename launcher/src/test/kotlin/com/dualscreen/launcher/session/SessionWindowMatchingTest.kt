package com.dualscreen.launcher.session

import com.dualscreen.launcher.GameCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionWindowMatchingTest {
    private val isaac = checkNotNull(GameCatalog.byId("isaac-repentance"))

    @Test
    fun `isaac executable class matches the play session`() {
        assertThat(matchesSessionWindow(" isaac-ng.exe", isaac, configurationLauncher = false)).isTrue()
    }

    @Test
    fun `isaac title is matched without punctuation`() {
        assertThat(
            matchesSessionWindow("The Binding of Isaac: Repentance+", isaac, configurationLauncher = false),
        ).isTrue()
    }

    @Test
    fun `unrelated window does not dismiss the launch cover`() {
        assertThat(matchesSessionWindow("explorer.exe Wine Desktop", isaac, configurationLauncher = false))
            .isFalse()
    }

    @Test
    fun `direct executable match uses the catalog executable`() {
        assertThat(matchesDirectExecutableWindow(" isaac-ng.exe", isaac)).isTrue()
    }
}
