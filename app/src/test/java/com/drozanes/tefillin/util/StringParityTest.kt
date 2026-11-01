package com.drozanes.tefillin.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * StringParityTest verifies that all localizations (Hebrew, English) are 100% complete
 * and synchronized, preventing missing string crashes or UI fallback glitches.
 */
class StringParityTest {

    private fun extractStringKeys(xmlFile: File): Map<String, String> {
        assertTrue("Resource file must exist: ${xmlFile.absolutePath}", xmlFile.exists())
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile)
        val stringNodes = doc.getElementsByTagName("string")

        val map = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val key = node.attributes.getNamedItem("name")?.nodeValue
            if (key != null) {
                map[key] = node.textContent.trim()
            }
        }
        return map
    }

    private fun findResFile(relPath: String): File {
        // Try relative to workspace or module directory
        val candidates = listOf(
            File("app/src/main/res", relPath),
            File("src/main/res", relPath),
            File("/Users/drozanes/Dev/tefillin_android_native/app/src/main/res", relPath)
        )
        return candidates.firstOrNull { it.exists() } ?: File("app/src/main/res", relPath)
    }

    @Test
    fun testEnglishTranslationsAreComplete() {
        val defaultStringsFile = findResFile("values/strings.xml")
        val englishStringsFile = findResFile("values-en/strings.xml")

        val defaultKeys = extractStringKeys(defaultStringsFile)
        val englishKeys = extractStringKeys(englishStringsFile)

        assertTrue("Default strings should not be empty", defaultKeys.isNotEmpty())
        assertTrue("English strings should not be empty", englishKeys.isNotEmpty())

        val missingInEnglish = defaultKeys.keys - englishKeys.keys
        assertTrue(
            "English localization is missing strings found in default values/strings.xml: $missingInEnglish",
            missingInEnglish.isEmpty()
        )

        val orphanedInEnglish = englishKeys.keys - defaultKeys.keys
        assertTrue(
            "English localization has orphaned keys not in default values/strings.xml: $orphanedInEnglish",
            orphanedInEnglish.isEmpty()
        )
    }

    @Test
    fun testHebrewTranslationsAreComplete() {
        val defaultStringsFile = findResFile("values/strings.xml")
        val hebrewStringsFile = findResFile("values-he/strings.xml")

        val defaultKeys = extractStringKeys(defaultStringsFile)
        val hebrewKeys = extractStringKeys(hebrewStringsFile)

        val missingInHebrew = defaultKeys.keys - hebrewKeys.keys
        assertTrue(
            "Hebrew (values-he) localization is missing strings: $missingInHebrew",
            missingInHebrew.isEmpty()
        )
    }

    @Test
    fun testNoEmptyStringValues() {
        val filesToCheck = listOf(
            findResFile("values/strings.xml"),
            findResFile("values-en/strings.xml"),
            findResFile("values-he/strings.xml")
        )

        for (file in filesToCheck) {
            val keys = extractStringKeys(file)
            for ((key, value) in keys) {
                assertFalse(
                    "String key '$key' in ${file.name} must not be blank",
                    value.isBlank()
                )
            }
        }
    }
}
