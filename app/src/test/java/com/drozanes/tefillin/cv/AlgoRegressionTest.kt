package com.drozanes.tefillin.cv

import com.drozanes.tefillin.model.AlignmentStatus
import com.drozanes.tefillin.model.TefillinBox
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import kotlin.math.abs
import kotlin.math.hypot

/**
 * AlgoRegressionTest - Executes the ACTUAL production Kotlin algorithms:
 *   - [TefillinEngine.analyzeFaceGeometry] (trichion & hairline detection, facial thirds clamping)
 *   - [TefillinEngine.detectVisualHairline] (visual forehead skin contrast scanning)
 *   - [TefillinEngine.isValidTefillinBox] (halachic candidate box validation)
 *   - [TefillinEngine.evaluateAlignment] (halachic alignment status & symmetry evaluation)
 *
 * Ground Truth and mistake rate benchmarks are managed via algo_ground_truth.json.
 * Tests pass as long as the hairline mistake percentage does not increase above the benchmark.
 */
class AlgoRegressionTest {

    data class FaceMistake(
        val name: String,
        val detectedRatio: Float,
        val expectedRatio: Float,
        val tolerance: Float
    ) {
        override fun toString(): String =
            "$name (got %.3f, expected %.3f ±%.2f, diff=%.3f)".format(
                detectedRatio, expectedRatio, tolerance, abs(detectedRatio - expectedRatio)
            )
    }

    data class HalachaMistake(
        val name: String,
        val detectedStatus: AlignmentStatus,
        val expectedStatus: AlignmentStatus,
        val detectedKosher: Boolean,
        val expectedKosher: Boolean,
        val distAbove: Float,
        val horizOffset: Float
    ) {
        override fun toString(): String =
            "$name (got $detectedStatus/kosher=$detectedKosher, expected $expectedStatus/kosher=$expectedKosher, distAbove=$distAbove, horizOffset=$horizOffset)"
    }

    companion object {
        private lateinit var fixturesJson: JSONObject
        private lateinit var groundTruthJson: JSONObject
        private var groundTruthFile: File? = null

        @BeforeClass
        @JvmStatic
        fun loadData() {
            // 1. Load fixtures (raw landmarks & patches)
            val fixStream = AlgoRegressionTest::class.java.classLoader?.getResourceAsStream("algo_test_fixtures.json")
                ?: throw IllegalStateException("algo_test_fixtures.json resource not found in classpath")
            fixturesJson = JSONObject(InputStreamReader(fixStream, Charsets.UTF_8).use { it.readText() })

            // 2. Load ground truth & benchmarks
            val gtStream = AlgoRegressionTest::class.java.classLoader?.getResourceAsStream("algo_ground_truth.json")
                ?: throw IllegalStateException("algo_ground_truth.json resource not found in classpath")
            groundTruthJson = JSONObject(InputStreamReader(gtStream, Charsets.UTF_8).use { it.readText() })

            // Locate file on disk if running locally for auto-saving record improvements
            val candidateFiles = listOf(
                File("app/src/test/resources/algo_ground_truth.json"),
                File("src/test/resources/algo_ground_truth.json")
            )
            groundTruthFile = candidateFiles.firstOrNull { it.exists() }
        }

        private fun getExcludedImages(): Set<String> {
            val set = HashSet<String>()
            val arr = groundTruthJson.optJSONArray("excluded_images") ?: return set
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            return set
        }

        private fun createPixelReader(patchObj: JSONObject?, fullWidth: Int, fullHeight: Int): TefillinEngine.PixelReader? {
            if (patchObj == null) return null
            val originX = patchObj.getInt("originX")
            val originY = patchObj.getInt("originY")
            val patchWidth = patchObj.getInt("patchWidth")
            val patchHeight = patchObj.getInt("patchHeight")
            val b64 = patchObj.getString("grayBase64")
            val grayBytes = Base64.getDecoder().decode(b64)

            return object : TefillinEngine.PixelReader {
                override val width: Int get() = fullWidth
                override val height: Int get() = fullHeight
                override fun getPixel(x: Int, y: Int): Int {
                    val px = x - originX
                    val py = y - originY
                    if (px in 0 until patchWidth && py in 0 until patchHeight) {
                        val g = grayBytes[py * patchWidth + px].toInt() and 0xFF
                        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    return -1
                }
            }
        }
    }

    /**
     * Test Suite 1: Trichion & Hairline Detection on Diverse Face Images
     * Asserts that the percentage of hairline detection mistakes does not increase
     * beyond the established benchmark recorded in algo_ground_truth.json.
     */
    @Test
    fun testTrichionDetectionOnDiverseFaces() {
        val faces = fixturesJson.getJSONArray("faces")
        val hairlineGt = groundTruthJson.optJSONObject("hairline_gt") ?: JSONObject()
        val benchmarkObj = groundTruthJson.getJSONObject("benchmark")
        val benchmarkMistakePercentage = benchmarkObj.getDouble("best_mistake_percentage")
        val excludedImages = getExcludedImages()

        assertTrue("Faces fixture must not be empty", faces.length() > 0)

        val mistakes = mutableListOf<FaceMistake>()
        val allDiffs = mutableListOf<Float>()
        var activeFaces = 0

        for (i in 0 until faces.length()) {
            val item = faces.getJSONObject(i)
            val name = item.getString("name")
            if (excludedImages.contains(name)) {
                continue
            }
            activeFaces++
            val width = item.getInt("width")
            val height = item.getInt("height")
            val lmsArray = item.getJSONArray("landmarks")

            val landmarks = ArrayList<TefillinEngine.LandmarkPoint>(lmsArray.length())
            for (j in 0 until lmsArray.length()) {
                val pt = lmsArray.getJSONArray(j)
                landmarks.add(
                    TefillinEngine.LandmarkPoint(
                        pt.getDouble(0).toFloat(),
                        pt.getDouble(1).toFloat(),
                        pt.getDouble(2).toFloat()
                    )
                )
            }

            val patchObj = item.optJSONObject("patch")
            val pixelReader = createPixelReader(patchObj, width, height)

            // EXECUTE ACTUAL PRODUCTION KOTLIN CODE
            val geometry = TefillinEngine.analyzeFaceGeometry(
                landmarks = landmarks,
                width = width,
                height = height,
                hairlineUserAdj = 0f,
                pixelReader = pixelReader,
                stabilizer = null
            )

            assertNotNull("Face geometry must not be null for $name", geometry)
            geometry!!

            // Glabella -> Hairline (Trichion)
            val distFromGlabella = hypot(
                geometry.hairlinePt.x - geometry.glabellaPt.x,
                geometry.hairlinePt.y - geometry.glabellaPt.y
            )
            val detectedRatio = distFromGlabella / geometry.eyeDist

            // Hairline must sit physiologically above Glabella on the head (Y decreases upwards in screen coords)
            assertTrue("Hairline Y must be above Glabella Y for $name", geometry.hairlinePt.y < geometry.glabellaPt.y)

            // Ground Truth comparison
            val gt = hairlineGt.optJSONObject(name)
            val expectedRatio = gt?.optDouble("expected_ratio", 0.70)?.toFloat() ?: 0.70f
            val tolerance = gt?.optDouble("tolerance", 0.05)?.toFloat() ?: 0.05f

            val diff = abs(detectedRatio - expectedRatio)
            allDiffs.add(diff)
            val isWithinTolerance = diff <= tolerance
            val isWithinAnatomicalBounds = detectedRatio in 0.60f..0.91f

            if (!isWithinTolerance || !isWithinAnatomicalBounds) {
                mistakes.add(FaceMistake(name, detectedRatio, expectedRatio, tolerance))
            }
        }

        val currentMistakePercentage = if (activeFaces > 0) (mistakes.size.toDouble() / activeFaces) * 100.0 else 0.0
        val mae = if (allDiffs.isNotEmpty()) allDiffs.average() else 0.0
        val medianDiff = if (allDiffs.isNotEmpty()) {
            val sorted = allDiffs.sorted()
            sorted[sorted.size / 2]
        } else 0f

        println("--------------------------------------------------------------------------------")
        println("Hairline Detection Results: %d/%d active passed (Mistakes: %d, %.2f%%)".format(
            activeFaces - mistakes.size, activeFaces, mistakes.size, currentMistakePercentage
        ))
        println("Accuracy: %.2f%% | Mean Absolute Error (MAE): %.4f (%.2f%% of eyeDist) | Median Error: %.4f".format(
            100.0 - currentMistakePercentage, mae, mae * 100.0, medianDiff
        ))
        println("Allowed Benchmark Mistake Rate: %.2f%%".format(benchmarkMistakePercentage))
        if (mistakes.isNotEmpty()) {
            println("Current deviations:")
            mistakes.forEach { println("   • $it") }
        }
        println("--------------------------------------------------------------------------------")

        // Auto-update benchmark if mistake rate strictly improved (e.g. fewer mistakes)
        if (currentMistakePercentage < benchmarkMistakePercentage && groundTruthFile != null) {
            try {
                benchmarkObj.put("best_mistake_percentage", Math.round(currentMistakePercentage * 100.0) / 100.0)
                benchmarkObj.put("best_mistake_count", mistakes.size)
                benchmarkObj.put("total_faces", activeFaces)
                benchmarkObj.put("last_updated", java.time.LocalDateTime.now().toString())
                groundTruthFile!!.writeText(groundTruthJson.toString(2))
                println("🎉 New best execution mistake rate recorded: %.2f%%!".format(currentMistakePercentage))
            } catch (_: Exception) {}
        }

        // Assert that mistake rate did not increase beyond the benchmark
        assertTrue(
            "Hairline mistake rate increased! Current: %.2f%% (%d/%d), Benchmark: %.2f%%. Regressed faces: %s".format(
                currentMistakePercentage, mistakes.size, activeFaces, benchmarkMistakePercentage, mistakes
            ),
            currentMistakePercentage <= benchmarkMistakePercentage + 0.05
        )
    }

    /**
     * Test Suite 2: Tefillin Alignment on Images of Men Wearing Tefillin
     * Compares actual verdict against Halachic Ground Truth in algo_ground_truth.json.
     */
    @Test
    fun testTefillinHalachicAlignmentOnRealSamples() {
        val tefillinList = fixturesJson.getJSONArray("tefillin")
        val halachaGt = groundTruthJson.optJSONObject("halacha_gt") ?: JSONObject()
        val excludedImages = getExcludedImages()
        assertTrue("Tefillin fixture must not be empty", tefillinList.length() > 0)

        val halachaMistakes = mutableListOf<HalachaMistake>()
        var activeTefillin = 0

        for (i in 0 until tefillinList.length()) {
            val item = tefillinList.getJSONObject(i)
            val name = item.getString("name")
            if (excludedImages.contains(name)) {
                continue
            }
            activeTefillin++
            val width = item.getInt("width")
            val height = item.getInt("height")
            val lmsArray = item.getJSONArray("landmarks")

            // Look up Ground Truth
            val gt = halachaGt.optJSONObject(name)
            val expectedStatusStr = gt?.optString("expected_status", item.optString("expectedStatus", "ALIGNED"))
                ?: item.optString("expectedStatus", "ALIGNED")
            val expectedKosher = gt?.optBoolean("expected_kosher", item.optBoolean("expectedKosher", true))
                ?: item.optBoolean("expectedKosher", true)

            val landmarks = ArrayList<TefillinEngine.LandmarkPoint>(lmsArray.length())
            for (j in 0 until lmsArray.length()) {
                val pt = lmsArray.getJSONArray(j)
                landmarks.add(
                    TefillinEngine.LandmarkPoint(
                        pt.getDouble(0).toFloat(),
                        pt.getDouble(1).toFloat(),
                        pt.getDouble(2).toFloat()
                    )
                )
            }

            val patchObj = item.optJSONObject("patch")
            val pixelReader = createPixelReader(patchObj, width, height)

            // 1. EXECUTE ACTUAL KOTLIN GEOMETRY EXTRACTION
            val geometry = TefillinEngine.analyzeFaceGeometry(
                landmarks = landmarks,
                width = width,
                height = height,
                hairlineUserAdj = 0f,
                pixelReader = pixelReader,
                stabilizer = null
            )
            assertNotNull("Geometry must not be null for sample $name", geometry)
            geometry!!

            // 2. Parse candidate YOLO box
            val boxObj = item.optJSONObject("yoloBox")
            assertNotNull("Candidate YOLO box must exist for sample $name", boxObj)
            boxObj!!

            val cx = boxObj.getDouble("x").toFloat()
            val cy = boxObj.getDouble("y").toFloat()
            val bw = boxObj.getDouble("boxWidth").toFloat()
            val bh = boxObj.getDouble("boxHeight").toFloat()
            val candBox = TefillinBox(
                x = cx,
                y = cy,
                boxWidth = bw,
                boxHeight = bh,
                leftX = cx - bw / 2f,
                rightX = cx + bw / 2f,
                topY = cy - bh / 2f,
                bottomY = cy + bh / 2f,
                confidence = boxObj.getDouble("confidence").toFloat()
            )

            // 3. EXECUTE ACTUAL KOTLIN CANDIDATE VALIDATION
            val isValid = TefillinEngine.isValidTefillinBox(candBox, geometry)
            assertTrue("Candidate box must pass geometric validation for genuine tefillin sample $name", isValid)

            // 4. EXECUTE ACTUAL KOTLIN HALACHIC ALIGNMENT EVALUATION
            val alignmentResult = TefillinEngine.evaluateAlignment(candBox, geometry)

            // 5. VERIFY VERDICT AGAINST GROUND TRUTH
            val expectedStatus = AlignmentStatus.valueOf(expectedStatusStr)
            val isStatusMatch = expectedStatus == alignmentResult.status
            val isKosherMatch = expectedKosher == alignmentResult.isKosher

            if (!isStatusMatch || !isKosherMatch) {
                halachaMistakes.add(
                    HalachaMistake(
                        name = name,
                        detectedStatus = alignmentResult.status,
                        expectedStatus = expectedStatus,
                        detectedKosher = alignmentResult.isKosher,
                        expectedKosher = expectedKosher,
                        distAbove = alignmentResult.distAboveHairline,
                        horizOffset = alignmentResult.horizOffset
                    )
                )
            }
        }

        val benchmarkObj = groundTruthJson.getJSONObject("benchmark")
        val benchmarkHalachaMistakePct = benchmarkObj.optDouble("best_halacha_mistake_percentage", 15.0)
        val currentHalachaPct = if (activeTefillin > 0) (halachaMistakes.size.toDouble() / activeTefillin) * 100.0 else 0.0

        println("--------------------------------------------------------------------------------")
        println("Halacha Alignment Results: %d/%d active passed (Mistakes: %d, %.2f%%)".format(
            activeTefillin - halachaMistakes.size, activeTefillin, halachaMistakes.size, currentHalachaPct
        ))
        println("Allowed Benchmark Halacha Mistake Rate: %.2f%%".format(benchmarkHalachaMistakePct))
        if (halachaMistakes.isNotEmpty()) {
            println("Current Halacha deviations:")
            halachaMistakes.forEach { println("   • $it") }
        }
        println("--------------------------------------------------------------------------------")

        // Auto-update benchmark if mistake rate strictly improved
        if (currentHalachaPct < benchmarkHalachaMistakePct && groundTruthFile != null) {
            try {
                benchmarkObj.put("best_halacha_mistake_percentage", Math.round(currentHalachaPct * 100.0) / 100.0)
                benchmarkObj.put("best_halacha_mistake_count", halachaMistakes.size)
                benchmarkObj.put("total_tefillin", activeTefillin)
                groundTruthFile!!.writeText(groundTruthJson.toString(2))
                println("🎉 New best execution Halacha mistake rate recorded: %.2f%%!".format(currentHalachaPct))
            } catch (_: Exception) {}
        }

        assertTrue(
            "Halacha mistake rate increased! Current: %.2f%% (%d/%d), Benchmark: %.2f%%. Deviations: %s".format(
                currentHalachaPct, halachaMistakes.size, activeTefillin, benchmarkHalachaMistakePct, halachaMistakes
            ),
            currentHalachaPct <= benchmarkHalachaMistakePct + 0.05
        )
    }
}
