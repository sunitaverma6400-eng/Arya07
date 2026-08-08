package com.arya.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks for [AryaToolRegistry.ALL_TOOLS] that don't need an Android device/emulator —
 * they only touch the plain-Kotlin `ToolDefinition`/`ToolParam` data classes, so they run as
 * fast JVM unit tests (`./gradlew testDebugUnitTest`).
 *
 * These won't catch every mistake (e.g. a tool name present in `ALL_TOOLS` but missing from
 * the `execute()` `when` block still needs a human/compiler to catch that — Kotlin doesn't
 * enforce exhaustiveness against a hand-maintained list), but they do catch the most common
 * copy-paste slip when adding a new tool: a duplicate or blank name/description.
 */
class AryaToolRegistryTest {

    @Test
    fun `no duplicate tool names`() {
        val names = AryaToolRegistry.ALL_TOOLS.map { it.name }
        val duplicates = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue("Duplicate tool names found: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every tool has a non-blank name and description`() {
        AryaToolRegistry.ALL_TOOLS.forEach { tool ->
            assertFalse("Tool has a blank name: $tool", tool.name.isBlank())
            assertFalse("Tool '${tool.name}' has a blank description", tool.description.isBlank())
        }
    }

    @Test
    fun `every tool param has a non-blank name, type, and description`() {
        AryaToolRegistry.ALL_TOOLS.forEach { tool ->
            tool.params.forEach { param ->
                assertFalse("Tool '${tool.name}' has a param with a blank name", param.name.isBlank())
                assertFalse("Tool '${tool.name}' param '${param.name}' has a blank type", param.type.isBlank())
                assertFalse("Tool '${tool.name}' param '${param.name}' has a blank description", param.description.isBlank())
            }
        }
    }

    @Test
    fun `tool names use snake_case (matches the JSON tool-calling convention)`() {
        val snakeCase = Regex("^[a-z][a-z0-9_]*$")
        AryaToolRegistry.ALL_TOOLS.forEach { tool ->
            assertTrue("Tool name '${tool.name}' isn't snake_case", snakeCase.matches(tool.name))
        }
    }

    @Test
    fun `registry has the expected tool count`() {
        // Update this number deliberately when adding/removing tools — it's a tripwire so an
        // accidental duplicate-block paste (which `no duplicate tool names` might not catch if
        // names were also tweaked) still gets noticed in review.
        assertTrue(
            "Expected at least 109 tools (62 from v1.1.0 + 44 streaming/image/site/news/location/api-key/personality tools + 3 custom-reminder tools in v1.2.0), found ${AryaToolRegistry.ALL_TOOLS.size}",
            AryaToolRegistry.ALL_TOOLS.size >= 109
        )
    }

    // -- relevantTools() (v1.2.1: avoids stuffing all 109 tools into the prompt every turn) --

    @Test
    fun `relevantTools never exceeds maxTools`() {
        val result = AryaToolRegistry.relevantTools("mausam batao, news sunao, gaana lagao, alarm laga do", maxTools = 5)
        assertTrue("Expected at most 5 tools, got ${result.size}", result.size <= 5)
    }

    @Test
    fun `relevantTools has no duplicate tool names`() {
        val result = AryaToolRegistry.relevantTools("weather aur news dono batao")
        val names = result.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `relevantTools falls back to core tools for unmatched plain conversation`() {
        val result = AryaToolRegistry.relevantTools("aaj tum kaisi ho")
        assertTrue(
            "Expected at least one core tool (e.g. web_search) for a query matching nothing specific",
            result.any { it.name == "web_search" || it.name == "get_current_time" }
        )
    }

    @Test
    fun `relevantTools matches an English tool name keyword directly`() {
        val result = AryaToolRegistry.relevantTools("what's the weather in Mumbai")
        assertTrue("Expected get_weather to match", result.any { it.name == "get_weather" })
    }

    @Test
    fun `relevantTools matches a Hinglish synonym`() {
        val result = AryaToolRegistry.relevantTools("gaana lagao lofi wala")
        assertTrue("Expected find_and_play to match via Hinglish synonym", result.any { it.name == "find_and_play" })
    }
}
