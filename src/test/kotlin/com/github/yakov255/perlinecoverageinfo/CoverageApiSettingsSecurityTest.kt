package com.github.yakov255.perlinecoverageinfo

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2: the token leaves the plain-text settings and never comes back.
 *
 * These are plain unit tests: there is no Application, so the settings fall back to an in-memory
 * [TokenStore] unless a test injects one through [CoverageApiSettings.tokenStoreOverride].
 */
class CoverageApiSettingsSecurityTest {

    /** The serialized state as the settings file sees it: one `option` per field. */
    private fun savedOptions(settings: CoverageApiSettings): Map<String, String?> =
        options(XmlSerializer.serialize(settings.getState()))
            .associate { it.getAttributeValue("name") to it.getAttributeValue("value") }

    private fun options(element: Element): List<Element> {
        val found = mutableListOf<Element>()
        fun visit(node: Element) {
            for (child in node.children.filterIsInstance<Element>()) {
                if (child.name == "option") found += child
                visit(child)
            }
        }
        visit(element)
        return found
    }

    @Test
    fun `a legacy plain-text token moves into the store and out of the state`() {
        val store = InMemoryTokenStore()
        val settings = CoverageApiSettings()
        settings.tokenStoreOverride = store

        settings.loadState(CoverageApiSettings.State(bearerToken = "legacy-plain-text-token"))

        assertEquals("legacy-plain-text-token", store.read())
        assertEquals("legacy-plain-text-token", settings.bearerToken)
        assertEquals("", settings.getState().bearerToken)
        val saved = savedOptions(settings)
        assertEquals("the collector must see the other fields", "raketa.dev", saved["gitlabDomain"])
        assertFalse(
            "the serialized state must not contain the token",
            saved.values.any { it?.contains("legacy-plain-text-token") == true },
        )
    }

    @Test
    fun `a state without a token keeps the token that is already cached`() {
        val store = InMemoryTokenStore("already-stored")
        val settings = CoverageApiSettings()
        settings.tokenStoreOverride = store

        assertEquals("already-stored", settings.bearerToken)
        settings.loadState(CoverageApiSettings.State())

        assertEquals("already-stored", settings.bearerToken)
        assertEquals("already-stored", store.read())
    }

    @Test
    fun `a token set through the property is never serialized`() {
        val store = InMemoryTokenStore()
        val settings = CoverageApiSettings()
        settings.tokenStoreOverride = store

        settings.bearerToken = "typed-token"

        assertEquals("typed-token", store.read())
        assertEquals("typed-token", settings.bearerToken)
        assertEquals("", settings.getState().bearerToken)
        assertFalse(savedOptions(settings).values.any { it?.contains("typed-token") == true })
    }

    @Test
    fun `the defaults name a host and keep the raketa project`() {
        val state = CoverageApiSettings.State()

        // raketa.dev is GitLab's external_url: the address a user can reach from anywhere, unlike the
        // internal gitlab.raketa.in. The build URL must therefore be https://raketa.dev.
        assertEquals("raketa.dev", state.gitlabDomain)
        assertEquals(335, state.gitlabProjectId)
        assertEquals("raketa/raketa", state.gitlabProjectName)
        assertEquals("https://raketa.dev", CoverageApiSettings.baseUrlFor(state.gitlabDomain))
    }

    @Test
    fun `baseUrlFor keeps an explicit scheme and adds https otherwise`() {
        assertEquals("https://gitlab.example.com", CoverageApiSettings.baseUrlFor("gitlab.example.com"))
        assertEquals("https://gitlab.example.com", CoverageApiSettings.baseUrlFor("  gitlab.example.com/ "))
        assertEquals("https://gitlab.example.com", CoverageApiSettings.baseUrlFor("https://gitlab.example.com/"))
        assertEquals("http://127.0.0.1:41000", CoverageApiSettings.baseUrlFor("http://127.0.0.1:41000"))
        assertEquals("HTTP://gitlab.local", CoverageApiSettings.baseUrlFor("HTTP://gitlab.local"))
        assertEquals("https://gitlab.example.com:8443", CoverageApiSettings.baseUrlFor("gitlab.example.com:8443"))
    }

    @Test
    fun `the settings base url uses the same helper`() {
        val settings = CoverageApiSettings()
        settings.gitlabDomain = "http://127.0.0.1:8080/?a=1"

        assertEquals("http://127.0.0.1:8080/?a=1", settings.gitlabBaseUrl)
        assertTrue(settings.gitlabBaseUrl.startsWith("http://"))
    }
}
