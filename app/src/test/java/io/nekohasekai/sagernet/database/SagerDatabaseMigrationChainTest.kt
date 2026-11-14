package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for upgrade data safety (HuBox v1.2.0).
 *
 * SagerDatabase does not export Room schema JSONs (exportSchema is off and
 * the schemas were never committed), so a MigrationTestHelper run is not
 * possible without changing the build. Instead this test pins the migration
 * chain itself:
 *  - the AutoMigration steps declared in @Database must form a continuous
 *    +1 chain from the oldest supported version to the current version —
 *    removing one step (or bumping the version without adding a step)
 *    fails this test;
 *  - the manual migration objects the builder actually registers (via the
 *    shared SagerDatabase.manualMigrations accessor) must cover 9 -> 10;
 *  - the builder must never use fallbackToDestructiveMigration.
 */
class SagerDatabaseMigrationChainTest {

    private val source: String by lazy {
        File("src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt").readText()
    }

    @Test
    fun autoMigrationChainIsContinuousUpToCurrentVersion() {
        val version = Regex("""version\s*=\s*(\d+)""").find(source)!!
            .groupValues[1].toInt()

        val steps = Regex("""AutoMigration\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)\s*\)""")
            .findAll(source)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

        assertTrue("no autoMigrations declared", steps.isNotEmpty())
        steps.forEach { (from, to) ->
            assertEquals("step $from -> $to must advance by exactly 1", from + 1, to)
        }

        val covered = steps.map { it.first }.toSet()
        assertEquals("duplicate autoMigration steps declared", steps.size, covered.size)

        val oldest = covered.minOrNull()!!
        // The oldest database version this build can migrate from is 3;
        // anything older lands in the emergency-backup path of
        // SagerDatabase.instance instead of a migration.
        assertEquals("oldest supported database version changed", 3, oldest)

        for (v in oldest until version) {
            assertTrue("missing migration step from version $v", covered.contains(v))
        }
        assertEquals(version - 1, covered.maxOrNull()!!)
    }

    @Test
    fun manualMigrationsAreRegisteredThroughSharedAccessor() {
        // Runtime check of the actual objects the builder registers.
        val manual = SagerDatabase.manualMigrations
        assertEquals(
            listOf(9 to 10),
            manual.map { it.startVersion to it.endVersion }
        )
        // …and the builder must really register that shared list.
        assertTrue(source.contains("addMigrations(*manualMigrations.toTypedArray())"))
    }

    @Test
    fun databaseBuilderNeverFallsBackToDestructiveMigration() {
        assertFalse(
            "SagerDatabase must never silently destroy user data on upgrade",
            source.contains("fallbackToDestructiveMigration")
        )
    }
}
