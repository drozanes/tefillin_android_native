package com.drozanes.tefillin.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticLoggerTest {

    @Test
    fun testLogLevelFormatting() {
        DiagnosticLogger.info("Camera started successfully")
        DiagnosticLogger.warn("Low light condition detected")
        DiagnosticLogger.error("Inference timed out", RuntimeException("Timeout"))

        val logs = DiagnosticLogger.getRecentLogs()
        assertTrue("Logs should contain INFO tag", logs.contains("[INFO] Camera started successfully"))
        assertTrue("Logs should contain WARN tag", logs.contains("[WARN] Low light condition detected"))
        assertTrue("Logs should contain ERROR tag", logs.contains("[ERROR] Inference timed out"))
        assertTrue("Logs should contain stack trace detail", logs.contains("java.lang.RuntimeException: Timeout"))
    }

    @Test
    fun testBufferCapsPreventMemoryLeak() {
        // Log 300 entries into the buffer (cap is 150)
        for (i in 1..300) {
            DiagnosticLogger.info("Synthetic frame log entry #$i")
        }

        val logs = DiagnosticLogger.getRecentLogs()
        val lines = logs.trim().lines().filter { it.isNotBlank() }

        assertTrue(
            "Recent logs size (${lines.size}) must never exceed MAX_RECENT_LOGS (150)",
            lines.size <= 150
        )
        // Verify the newest entries are retained
        assertTrue("Newest entry #300 should be retained", logs.contains("Synthetic frame log entry #300"))
        assertFalse("Old entry #1 should have rolled out", logs.contains("Synthetic frame log entry #1\n"))
    }

    @Test
    fun testConcurrentLoggingThreadSafety() {
        val executor = Executors.newFixedThreadPool(8)
        val numThreads = 8
        val logsPerThread = 50

        // Run multi-threaded logging concurrently to assert no ConcurrentModificationException
        for (t in 0 until numThreads) {
            executor.execute {
                for (i in 0 until logsPerThread) {
                    DiagnosticLogger.info("Thread $t log #$i")
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(3, TimeUnit.SECONDS)
        assertTrue("All concurrent logging tasks should complete cleanly", finished)

        val logs = DiagnosticLogger.getRecentLogs()
        assertNotNull(logs)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun testBuildDiagnosticReportFormatting() {
        val report = DiagnosticLogger.buildDiagnosticReport(
            userNotes = "Tefillin box is jittering under bright sunlight",
            activeModel = "best_fp16.onnx",
            isGpu = true,
            cameraRes = "1280x720"
        )

        assertTrue(report.contains("TEFILLIN MIRROR NATIVE ANDROID BUG REPORT"))
        assertTrue(report.contains("User Notes: Tefillin box is jittering under bright sunlight"))
        assertTrue(report.contains("Active Model: best_fp16.onnx"))
        assertTrue(report.contains("GPU Acceleration: Active"))
        assertTrue(report.contains("Camera Resolution: 1280x720"))
        assertTrue(report.contains("RECENT LOGS (LAST 150 EVENTS)"))
    }
}
