package com.lazyeng.family

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Opt-in stalled-test diagnostic. Contains stack frames, never local variable values. */
class DiagnosticListener : RunListener() {
    private var worker: Thread? = null
    override fun testStarted(description: Description) {
        worker = Thread({
            try { Thread.sleep(15_000) } catch (_: InterruptedException) { return@Thread }
            val stacks = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                thread.name + " " + thread.state + "\n" + stack.joinToString("\n")
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test-threads.txt").writeText(stacks)
        }, "TestDiagnostic").apply { isDaemon = true; start() }
    }
    override fun testFinished(description: Description) { worker?.interrupt(); worker = null }
}
