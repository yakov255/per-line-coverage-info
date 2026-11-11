package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.BehatTestRunner
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageTestNavigator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.behat.run.BehatRunConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The Behat coverage artifacts name a test as `<git-relative feature path>.feature:<line>`.
 * Coverage built by `behat-coverage-per-line-report` 2.0.0 writes `app/raketa/features/...`
 * (the older build wrote `app/raketa/...`, without the `features/` segment), so these tests pin
 * the whole user-visible chain for the new shape: the feature file is found, the scenario is
 * resolved from the line, and the Behat launch receives a path relative to the Behat working
 * directory.
 */
class HeavyBehatTestPathTest : BasePlatformTestCase() {

    private val featureRelativePath = "app/raketa/features/E2E/Sample/feature.feature"

    private val featureText = """
        |# language: ru
        |Функциональность: Пример
        |
        |  @sprint:1
        |  Сценарий: Первый
        |    Дано что-то есть
        |    Когда что-то делают
        |
        |  Сценарий: Второй
        |    Дано другое есть
        |""".trimMargin()

    private fun scenarioLine(name: String): Int =
        featureText.lines().indexOfFirst { it.trim() == name }.let { require(it >= 0) { "no $name" }; it + 1 }

    /** The directory `VfsUtil.findRelativeFile` resolves against (a light test project uses `<base>/src`). */
    /** Real directory used only for the path arithmetic (the run configuration is never executed). */
    private val realRoot: File = kotlin.io.path.createTempDirectory("behat-paths").toFile()

    override fun tearDown() {
        try {
            CoverageDataService.getInstance(project).clear()
            realRoot.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /**
     * Adds the feature to the project fixture the way the real repository has it (the light test
     * project has no writable directory on disk) and returns the directory used for path math.
     */
    private fun writeFeature(): File {
        myFixture.addFileToProject(featureRelativePath, featureText)
        myFixture.addFileToProject("app/raketa/features/behat.yml",
            "default:\n  suites:\n    default:\n      paths: [ . ]\n")
        CoverageDataService.getInstance(project).setCoverageContext("deadbeef", realRoot)
        return realRoot
    }

    fun testNewTestPathNavigatesToTheScenario() {
        writeFeature()
        awaitSmartMode()
        val line = scenarioLine("Сценарий: Первый")

        ApplicationManager.getApplication().invokeAndWait {
            CoverageTestNavigator.navigateToTest(project, "$featureRelativePath:$line")
        }

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("a feature editor must open for the new test path shape", editor)
        assertEquals(featureRelativePath, editor!!.virtualFile.path.removePrefix("/src/"))
        assertEquals("the caret must be on the scenario line", line - 1, editor.caretModel.logicalPosition.line)
    }

    fun testScenarioNameIsResolvedFromTheNewTestPath() {
        writeFeature()
        awaitSmartMode()
        val line = scenarioLine("Сценарий: Второй")

        // The panel resolves labels on a pooled thread; ReadAction.computeCancellable is not allowed
        // on the EDT, so the test calls it the same way the production code does.
        val name = java.util.concurrent.CompletableFuture<String?>()
        ApplicationManager.getApplication().executeOnPooledThread {
            name.complete(CoverageTestNavigator.resolveScenarioName(project, featureRelativePath, line.toString()))
        }

        assertEquals("Второй", name.get(60, java.util.concurrent.TimeUnit.SECONDS))
    }

    fun testBundledRunGetsAPathRelativeToTheBehatWorkingDirectory() {
        writeFeature()
        val line = scenarioLine("Сценарий: Первый")
        val feature = File(realRoot, featureRelativePath).apply { parentFile.mkdirs(); writeText(featureText) }
        val workingDir = File(realRoot, "app/raketa/features").absolutePath

        // 1. With no test-framework config the plugin falls back to the project root and passes a
        //    project-relative path (Behat would be started from there by the template).
        val settings = buildMultiPathsConfig(mapOf(feature.absolutePath to listOf(line)))
        assertNotNull("the plugin must build a Behat run configuration for the feature", settings)
        val configuration = settings!!.configuration as BehatRunConfiguration
        val options = configuration.settings.runnerSettings.testRunnerOptions.orEmpty()
        assertTrue("the launch must name the feature file and the scenario line, was: $options",
            options.contains("$featureRelativePath:$line"))
        assertTrue("the launch must not pass an absolute path, was: $options", !options.contains(feature.absolutePath))

        // 2. With the Behat config at app/raketa/features/behat.yml the working directory is that
        //    directory, so the argument loses that prefix exactly like in CI.
        val relative = relativize(mapOf(feature.absolutePath to listOf(line)), workingDir)
        assertEquals(setOf("E2E/Sample/feature.feature:$line"),
            relative.map { (path, lines) -> lines.joinToString(" ") { "$path:$it" } }.toSet())
    }

    /** Newly created files and the Gherkin language are available only outside dumb mode. */
    private fun awaitSmartMode(timeoutMs: Long = 120_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (!com.intellij.openapi.project.DumbService.isDumb(project)) return
            Thread.sleep(50)
        }
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("the project stayed in dumb mode", !com.intellij.openapi.project.DumbService.isDumb(project))
    }

    /**
     * The user opens a service directory (`api/hotels`) as the project root. Coverage keeps
     * git-root-relative paths, so the plugin must strip the project's own prefix before asking the
     * VFS (`api/hotels/features/...` -> `features/...`) and must pass the feature to Behat relative
     * to the Behat config directory (`api/hotels`, where behat.yml lives).
     */
    fun testSubprojectStripsItsOwnPrefixForNavigationAndRun() {
        // The light project lives in `<root>/src`, so the git root is its parent and `src` plays the
        // role of `api/hotels`: coverage paths carry that prefix, the project does not.
        val projectDir = project.guessProjectDir() ?: error("no project dir")
        val base = File(project.basePath!!)
        val gitRoot = base.parentFile!!
        myFixture.addFileToProject("features/E2E/Sample/feature.feature", featureText)
        CoverageDataService.getInstance(project).setCoverageContext("deadbeef", gitRoot)
        awaitSmartMode()

        val line = scenarioLine("Сценарий: Второй")
        // The project directory plays `api/hotels`: coverage names carry it, the project does not.
        val serviceDir = base.name
        val rawFeature = "$serviceDir/features/E2E/Sample/feature.feature"
        val gitRelativeTest = "$rawFeature:$line"

        val resolved = java.util.concurrent.CompletableFuture<String?>()
        ApplicationManager.getApplication().executeOnPooledThread {
            resolved.complete(CoverageTestNavigator.resolveScenarioName(project, rawFeature, line.toString()))
        }
        assertNotNull("the scenario must be found through the project prefix",
            resolved.get(60, java.util.concurrent.TimeUnit.SECONDS))

        ApplicationManager.getApplication().invokeAndWait {
            CoverageTestNavigator.navigateToTest(project, gitRelativeTest)
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("the feature must open although the coverage path has the project prefix", editor)
        assertEquals("features/E2E/Sample/feature.feature",
            editor!!.virtualFile.path.removePrefix(projectDir.path).removePrefix("/"))
        assertEquals(line - 1, editor.caretModel.logicalPosition.line)

        // Behat is started from the directory of its config file; the argument loses that prefix.
        val feature = File(realRoot, "api/hotels/features/E2E/Sample/feature.feature")
            .apply { parentFile.mkdirs(); writeText(featureText) }
        val relative = relativize(mapOf(feature.absolutePath to listOf(line)),
            File(realRoot, "api/hotels").absolutePath)
        assertEquals(listOf("features/E2E/Sample/feature.feature:$line"),
            relative.entries.map { (path, lines) -> lines.joinToString(" ") { "$path:$it" } })
    }

    // --- reflection on the private helpers (they depend on the PHP plugin, so they cannot be
    // --- extracted to a pure test helper without changing production code) -------------------

    private fun buildMultiPathsConfig(paths: Map<String, List<Int>>): com.intellij.execution.RunnerAndConfigurationSettings? {
        val method = BehatTestRunner::class.java.getDeclaredMethod(
            "createMultiPathsConfig", com.intellij.openapi.project.Project::class.java, Map::class.java,
        ).apply { isAccessible = true }
        val result = AtomicReference<com.intellij.execution.RunnerAndConfigurationSettings?>()
        ApplicationManager.getApplication().invokeAndWait {
            @Suppress("UNCHECKED_CAST")
            result.set(method.invoke(BehatTestRunner, project, paths) as com.intellij.execution.RunnerAndConfigurationSettings?)
        }
        return result.get()
    }

    private fun relativize(paths: Map<String, List<Int>>, workingDir: String): Map<String, List<Int>> {
        val method = BehatTestRunner::class.java.getDeclaredMethod(
            "relativizePaths", Map::class.java, String::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(BehatTestRunner, paths, workingDir) as Map<String, List<Int>>
    }
}
