package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "CoverageApiSettings",
    // The token lives in the credential store, which is machine-local. Roaming the domain and the
    // project alone would leave the other machine with a half-configured plugin whose token is
    // missing, so this file stays on the machine that wrote it.
    storages = [Storage("coverageApiSettings.xml", roamingType = RoamingType.DISABLED)]
)
@Service
class CoverageApiSettings : PersistentStateComponent<CoverageApiSettings.State> {

    data class State(
        var gitlabDomain: String = "raketa.dev",
        /**
         * Legacy field of the plain-text era. It is only read, to migrate a token written by an
         * older version; [getState] always returns it empty, so no token is ever saved again.
         */
        var bearerToken: String = "",
        var gitlabProjectId: Long = 335,
        var gitlabProjectName: String = "raketa/raketa",
        var coverageBranch: String = "master",
        /** Whether the plugin is enabled for this IDE installation. */
        var enabled: Boolean = false,
        /** Whether the first-run token prompt has been shown. */
        var tokenPrompted: Boolean = false,
    )

    private var state = State()

    private val log by lazy { CoverageLog.get(CoverageApiSettings::class.java) }

    /** Test seam, like `CovdClient.binaryOverride`: replaces the credential store before first use. */
    @Volatile
    internal var tokenStoreOverride: TokenStore? = null

    private val tokenCache: TokenCache by lazy {
        val store = tokenStoreOverride ?: defaultTokenStore()
        defaultTokenCache(store)
    }

    override fun getState(): State = state.copy(bearerToken = "")

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        migrateLegacyToken()
    }

    /**
     * A token found in the XML was written by a version that kept it in plain text: move it into the
     * credential store and clear it from the state, so the next save drops it from disk.
     */
    private fun migrateLegacyToken() {
        val legacy = this.state.bearerToken
        if (legacy.isBlank()) return
        this.state.bearerToken = ""
        tokenCache.set(legacy.trim())
        log.info("Coverage: moved the GitLab token from the plain-text settings into the credential store")
    }

    var gitlabDomain: String
        get() = state.gitlabDomain
        set(value) { state.gitlabDomain = value }

    /** The token; cached in memory, so reading it never blocks the EDT and a set is visible at once. */
    var bearerToken: String
        get() = tokenCache.get()
        set(value) { tokenCache.set(value) }

    var gitlabProjectId: Long
        get() = state.gitlabProjectId
        set(value) { state.gitlabProjectId = value }

    var gitlabProjectName: String
        get() = state.gitlabProjectName
        set(value) { state.gitlabProjectName = value }

    var coverageBranch: String
        get() = state.coverageBranch
        set(value) { state.coverageBranch = value }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var tokenPrompted: Boolean
        get() = state.tokenPrompted
        set(value) { state.tokenPrompted = value }

    /** `https://<domain>`, unless the domain already names a scheme (`http://host:8080` for a plain-HTTP GitLab). */
    val gitlabBaseUrl: String
        get() = baseUrlFor(state.gitlabDomain)

    /** Test seam: waits for the queued credential-store writes. */
    internal fun awaitTokenWritesForTest(timeoutMs: Long = 10_000): Boolean = tokenCache.flush(timeoutMs)

    companion object {
        /**
         * The base URL for [domain]. A domain that already carries a scheme keeps it, so a plain-HTTP
         * GitLab (`http://host:8080`) keeps working; every other domain gets `https://`.
         */
        fun baseUrlFor(domain: String): String {
            val value = domain.trim().trimEnd('/')
            val hasScheme = value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            return if (hasScheme) value else "https://$value"
        }

        fun getInstance(): CoverageApiSettings = service()
    }
}
