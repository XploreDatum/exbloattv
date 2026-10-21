package dev.xpdt.exbloattv.about

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class NoticesTest {
    private val sample = """
        # Third-party notices

        Intro text with - Version: ignored because it is not under a component.

        ## Shipped in the app

        ### OkHttp
        - Version: 5.3.2
        - Licence: Apache-2.0
        - Source: https://github.com/square/okhttp
        - Copyright: Copyright 2019 Square, Inc.

        ### Thing
        - Version: 1.0
        - Licence: MIT
        - Source: https://example.com/thing
        - Copyright: Copyright (c) 2026 Someone
        - Notes: Modified: grouped into categories.

        ## Downloaded at runtime, not bundled

        ### FLauncher
        - Version: 0.18.0
        - Licence: GPL-3.0-or-later
        - Source: https://gitlab.com/flauncher/flauncher
        - Copyright: Copyright (C) 2021 Étienne Fesser
        - Licence URL: https://www.gnu.org/licenses/gpl-3.0.html

        ## Licence texts

        ### Apache-2.0

        ```text
        Apache text line 1

          indented line 2
        ```

        ### MIT

        ```text
        Permission is hereby granted
        ```
    """.trimIndent()

    @Test
    fun `parses sections, components and fields in file order`() {
        val n = Notices.parse(sample)
        assertThat(n.sections.map { it.title }).containsExactly("Shipped in the app", "Downloaded at runtime, not bundled").inOrder()
        assertThat(n.components.map { it.name }).containsExactly("OkHttp", "Thing", "FLauncher").inOrder()
        val ok = n.components[0]
        assertThat(ok).isEqualTo(
            Component("OkHttp", "5.3.2", "Apache-2.0", "https://github.com/square/okhttp", "Copyright 2019 Square, Inc."),
        )
        assertThat(n.components[1].notes).isEqualTo("Modified: grouped into categories.")
        assertThat(n.components[2].licenceUrl).isEqualTo("https://www.gnu.org/licenses/gpl-3.0.html")
    }

    @Test
    fun `licence texts keep their exact lines and are not listed as a section`() {
        val n = Notices.parse(sample)
        assertThat(n.texts.keys).containsExactly("Apache-2.0", "MIT").inOrder()
        assertThat(n.texts["Apache-2.0"]).isEqualTo("Apache text line 1\n\n  indented line 2")
        assertThat(n.textFor(n.components[0])).isEqualTo(n.texts["Apache-2.0"])
        assertThat(n.textFor(n.components[2])).isNull()
    }

    @Test
    fun `windows line endings parse the same`() {
        assertThat(Notices.parse(sample.replace("\n", "\r\n"))).isEqualTo(Notices.parse(sample))
    }

    @Test
    fun `a component without a required field is an error naming it`() {
        val broken = "## S\n\n### Lib\n- Version: 1\n- Licence: MIT\n- Source: https://x\n"
        val e = assertThrows(IllegalArgumentException::class.java) { Notices.parse(broken) }
        assertThat(e).hasMessageThat().contains("\"Lib\" has no \"Copyright\" line")
    }

    @Test
    fun `an unclosed licence text is an error`() {
        val broken = "## Licence texts\n\n### MIT\n\n```text\nPermission"
        assertThrows(IllegalArgumentException::class.java) { Notices.parse(broken) }
    }

    @Test
    fun `empty input gives no sections`() {
        assertThat(Notices.parse("").sections).isEmpty()
    }
}
