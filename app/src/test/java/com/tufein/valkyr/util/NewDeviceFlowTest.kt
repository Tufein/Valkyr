package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewDeviceFlowTest {

    @Test fun `empty device leaves every step to do`() {
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input())
        assertTrue(steps.all { it.state == NewDeviceFlow.State.TODO || it.state == NewDeviceFlow.State.SKIPPED })
        // restore is skipped when there is no backup target
        assertEquals(NewDeviceFlow.State.SKIPPED, steps.first { it.kind == NewDeviceFlow.Kind.RESTORE }.state)
    }

    @Test fun `backup present but wrong password keeps restore to do`() {
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input(
            backupTargetConfigured = true,
            backupPresent = true,
            backupPasswordOk = false
        ))
        assertEquals(NewDeviceFlow.State.TODO, steps.first { it.kind == NewDeviceFlow.Kind.RESTORE }.state)
    }

    @Test fun `backup present with right password marks restore done`() {
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input(
            backupTargetConfigured = true,
            backupPresent = true,
            backupPasswordOk = true
        ))
        assertEquals(NewDeviceFlow.State.DONE, steps.first { it.kind == NewDeviceFlow.Kind.RESTORE }.state)
    }

    @Test fun `bios missing for one console keeps bios to do`() {
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input(
            biosChecked = true,
            biosMissing = listOf("psx")
        ))
        assertEquals(NewDeviceFlow.State.TODO, steps.first { it.kind == NewDeviceFlow.Kind.BIOS }.state)
    }

    @Test fun `a complete library marks the sample done`() {
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input(
            librarySampleOk = true,
            foldersOk = true,
            emulatorsOk = true,
            biosChecked = true
        ))
        assertEquals(NewDeviceFlow.State.DONE, steps.first { it.kind == NewDeviceFlow.Kind.LIBRARY_SAMPLE }.state)
        assertEquals(NewDeviceFlow.State.DONE, steps.first { it.kind == NewDeviceFlow.Kind.FIRST_LAUNCH }.state)
    }

    @Test fun `the flow never claims a step passes without running its check`() {
        // null check results must never become DONE
        val steps = NewDeviceFlow.plan(NewDeviceFlow.Input(
            backupTargetConfigured = true,
            backupPresent = true,
            backupPasswordOk = null,
            foldersOk = null,
            emulatorsOk = null,
            librarySampleOk = null
        ))
        assertTrue(steps.none { it.kind in listOf(NewDeviceFlow.Kind.FOLDERS, NewDeviceFlow.Kind.EMULATORS, NewDeviceFlow.Kind.LIBRARY_SAMPLE) && it.state == NewDeviceFlow.State.DONE })
    }
}