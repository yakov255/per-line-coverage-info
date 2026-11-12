package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.gitlabTokenAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.SerializationFilter
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * H2 inside a real IDE: the GitLab token goes to [PasswordSafe] and never into the serialized
 * `coverageApiSettings.xml` state, a token left in the old XML is migrated, and the getter on the
 * EDT does not wait for the credential store.
 *
 * The heavy JVM runs in unit-test mode, so [PasswordSafe] is the platform's in-memory test
 * implementation: no keychain or master-password dialog can appear here.
 */
class HeavyTokenStorageTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            onPooledThread { PasswordSafe.instance.set(gitlabTokenAttributes(), null) }
        } finally {
            super.tearDown()
        }
    }

    fun testTokenIsStoredOutsideTheSettingsXml() {
        val settings = CoverageApiSettings()
        settings.bearerToken = "heavy-secret-token"

        assertEquals("heavy-secret-token", settings.bearerToken)
        assertTrue("the queued credential-store write must finish", settings.awaitTokenWritesForTest())
        assertEquals("heavy-secret-token", storedToken())

        assertEquals("", settings.getState().bearerToken)
        val allFields = savedOptions(settings.getState())
        assertEquals("the collector must see the other fields", "raketa.dev", allFields["gitlabDomain"])
        assertFalse(
            "the token must not be serialized: $allFields",
            allFields.values.any { it?.contains("heavy-secret-token") == true },
        )
        val likeTheFile = savedOptions(settings.getState(), SkipDefaultsSerializationFilter())
        assertFalse("bearerToken must not be part of the saved file: $likeTheFile", likeTheFile.containsKey("bearerToken"))
    }

    fun testALegacyPlainTextTokenInTheXmlIsMigrated() {
        val settings = CoverageApiSettings()
        settings.loadState(
            CoverageApiSettings.State(
                gitlabDomain = "gitlab.raketa.in",
                bearerToken = "legacy-plain-text-token",
            ),
        )

        // The cache is filled at once, so the startup load already sees the token.
        assertEquals("legacy-plain-text-token", settings.bearerToken)
        assertEquals("", settings.getState().bearerToken)
        assertFalse(
            savedOptions(settings.getState()).values.any { it?.contains("legacy-plain-text-token") == true },
        )
        assertTrue(settings.awaitTokenWritesForTest())
        assertEquals("legacy-plain-text-token", storedToken())
    }

    fun testTheGetterOnTheEdtNeverWaitsForTheCredentialStore() {
        val settings = CoverageApiSettings()

        // Cache miss on the EDT: the getter returns at once and asks for a background load.
        var firstRead: String? = null
        ApplicationManager.getApplication().invokeAndWait { firstRead = settings.bearerToken }
        assertEquals("", firstRead)

        settings.bearerToken = "edt-token"
        var afterSet: String? = null
        ApplicationManager.getApplication().invokeAndWait { afterSet = settings.bearerToken }
        assertEquals("edt-token", afterSet)
        assertTrue(settings.awaitTokenWritesForTest())
        assertEquals("edt-token", storedToken())
    }

    private fun storedToken(): String? = onPooledThread {
        PasswordSafe.instance.get(gitlabTokenAttributes())?.getPasswordAsString()
    }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication()
            .executeOnPooledThread(Callable { task() })
            .get(30, TimeUnit.SECONDS)

    private fun savedOptions(
        state: CoverageApiSettings.State,
        filter: SerializationFilter? = null,
    ): Map<String, String?> = options(
        if (filter == null) XmlSerializer.serialize(state) else XmlSerializer.serialize(state, filter),
    ).associate { it.getAttributeValue("name") to it.getAttributeValue("value") }

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
}
