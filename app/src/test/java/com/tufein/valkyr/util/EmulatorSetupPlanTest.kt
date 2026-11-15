package com.tufein.valkyr.util

import com.tufein.valkyr.util.EmulatorSetupPlan.Action
import com.tufein.valkyr.util.EmulatorSetupPlan.Detail
import com.tufein.valkyr.util.EmulatorSetupPlan.Input
import com.tufein.valkyr.util.EmulatorSetupPlan.Kind
import com.tufein.valkyr.util.EmulatorSetupPlan.State
import com.tufein.valkyr.util.EmulatorSetupPlan.Writes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.4: the steps from "missing" to "set up" ([EmulatorSetupPlan]). */
class EmulatorSetupPlanTest {
    private fun pkg(id: String) = EmulatorCatalog.byId(id)!!.variants.first().packageName
    private val retroArch = setOf(pkg("retroarch"))
    private fun EmulatorSetupPlan.Plan.step(kind: Kind) = steps.single { it.kind == kind }

    private fun bios(consoleId: String, present: Set<String>): BiosCatalog.SystemResult {
        val system = BiosCatalog.systemsFor(listOf(consoleId)).single()
        return BiosCatalog.check(system, present.associateWith { it }) { null }
    }

    @Test
    fun `a standalone emulator that is installed leaves nothing to install and no core step`() {
        val plan = EmulatorSetupPlan.plan(Input("nintendo_ds", setOf(pkg("drastic"))))
        assertEquals(State.DONE, plan.step(Kind.EMULATOR).state)
        assertEquals(Detail.EMULATOR_STANDALONE, plan.step(Kind.EMULATOR).detail)
        assertEquals(State.NOT_NEEDED, plan.step(Kind.CORE).state)
        assertTrue(plan.ready)
    }

    @Test
    fun `RetroArch alone asks for a core, which Valkyr cannot see`() {
        val plan = EmulatorSetupPlan.plan(Input("nintendo_gameboy_advance", retroArch, savesFolderSet = true))
        assertEquals(Detail.EMULATOR_RETROARCH, plan.step(Kind.EMULATOR).detail)
        val core = plan.step(Kind.CORE)
        assertEquals(State.CHECK, core.state)
        assertEquals(EmulatorCatalog.coreLabel("mgba"), core.args.first())
        assertEquals(Writes.IN_RETROARCH, core.writes)
        assertEquals(State.DONE, plan.step(Kind.SAVES).state)
        assertTrue(plan.ready)
    }

    @Test
    fun `nothing installed names the apps to choose from, standalone first, and writes nothing here`() {
        val plan = EmulatorSetupPlan.plan(Input("nintendo_ds", emptySet()))
        val step = plan.step(Kind.EMULATOR)
        assertEquals(State.TODO, step.state)
        assertEquals(Action.INSTALL, step.action)
        assertEquals(Writes.NOTHING_HERE, step.writes)
        assertTrue(step.installs.isNotEmpty())
        assertFalse(step.installs.first().retroArch)
        assertTrue(step.installs.last().retroArch)
        assertTrue(step.installs.all { it.packageName.isNotBlank() && it.label.isNotBlank() })
        assertFalse(plan.ready)
        // Saves are no topic before there is an emulator.
        assertEquals(State.NOT_NEEDED, plan.step(Kind.SAVES).state)
    }

    @Test
    fun `an unknown console is no dead end`() {
        val plan = EmulatorSetupPlan.plan(Input("some_unknown_machine", retroArch))
        assertEquals(Detail.EMULATOR_UNKNOWN, plan.step(Kind.EMULATOR).detail)
        assertEquals(State.CHECK, plan.step(Kind.EMULATOR).state)
        assertEquals(null, plan.system)
        assertTrue(plan.steps.none { it.state == State.TODO })
    }

    @Test
    fun `a disc console wants its disc lists, and says which setting that is`() {
        val on = EmulatorSetupPlan.plan(Input("sony_playstation", setOf(pkg("duckstation")), autoM3u = true))
        assertEquals(Detail.DISCS_AUTO, on.step(Kind.DISCS).detail)
        val off = EmulatorSetupPlan.plan(Input("sony_playstation", setOf(pkg("duckstation")), autoM3u = false))
        assertEquals(State.TODO, off.step(Kind.DISCS).state)
        assertEquals(Writes.M3U_SETTING, off.step(Kind.DISCS).writes)
        assertEquals(State.NOT_NEEDED, EmulatorSetupPlan.plan(Input("nintendo_ds", emptySet())).step(Kind.DISCS).state)
    }

    @Test
    fun `a missing BIOS file is named, and blocks ready`() {
        val none = EmulatorSetupPlan.plan(Input("sony_playstation", setOf(pkg("duckstation")), bios = bios("sony_playstation", emptySet()), biosFolderSet = false))
        assertEquals(Detail.BIOS_NO_FOLDER, none.step(Kind.BIOS).detail)
        assertFalse(none.ready)
        val missing = EmulatorSetupPlan.plan(Input("sony_playstation", setOf(pkg("duckstation")), bios = bios("sony_playstation", emptySet()), biosFolderSet = true))
        assertEquals(Detail.BIOS_MISSING, missing.step(Kind.BIOS).detail)
        assertTrue(missing.step(Kind.BIOS).args.isNotEmpty())
        assertEquals(Action.OPEN_BIOS, missing.step(Kind.BIOS).action)
        assertFalse(missing.ready)
        val all = BiosCatalog.systemsFor(listOf("sony_playstation")).single().files.map { it.path.lowercase() }.toSet()
        val ok = EmulatorSetupPlan.plan(Input("sony_playstation", setOf(pkg("duckstation")), bios = bios("sony_playstation", all), biosFolderSet = true))
        assertEquals(State.DONE, ok.step(Kind.BIOS).state)
        assertTrue(ok.ready)
    }

    @Test
    fun `the emulator's own save folder counts once it is added`() {
        val preset = EmulatorSaveFolders.presets.first { it.emulatorId == "drastic" }
        val folder = EmulatorSaveFolder(preset.label, preset.platforms, "content://x/tree/1")
        val with = EmulatorSetupPlan.plan(Input("nintendo_ds", setOf(pkg("drastic")), emulatorSaveFolders = listOf(folder)))
        assertEquals(Detail.SAVES_EMULATOR_FOLDER, with.step(Kind.SAVES).detail)
        val without = EmulatorSetupPlan.plan(Input("nintendo_ds", setOf(pkg("drastic"))))
        assertEquals(State.TODO, without.step(Kind.SAVES).state)
        assertEquals(Action.OPEN_SAVE_SYNC, without.step(Kind.SAVES).action)
        // Saves never block playing.
        assertTrue(without.ready)
    }

    @Test
    fun `every step that asks for something says what it changes`() {
        val inputs = listOf(
            Input("nintendo_ds", emptySet()), Input("nintendo_gameboy_advance", retroArch),
            Input("sony_playstation", setOf(pkg("duckstation")), bios = bios("sony_playstation", emptySet()), biosFolderSet = true, autoM3u = false)
        )
        inputs.flatMap { EmulatorSetupPlan.plan(it).open }.filter { it.action != null }.forEach { step ->
            assertTrue("${step.kind} has no 'writes'", step.writes != Writes.NOTHING)
            assertNotNull(step.action)
        }
    }
}
