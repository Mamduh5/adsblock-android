package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSaverModeTest {
    @Test
    fun `mode cycles off balanced max and back to off`() {
        assertEquals(DataSaverMode.BALANCED, DataSaverMode.OFF.next())
        assertEquals(DataSaverMode.MAX, DataSaverMode.BALANCED.next())
        assertEquals(DataSaverMode.OFF, DataSaverMode.MAX.next())
    }

    @Test
    fun `stored values deserialize and corrupt values safely fall back to off`() {
        DataSaverMode.entries.forEach { mode ->
            assertEquals(mode, DataSaverMode.fromStoredValue(mode.name))
        }
        assertEquals(DataSaverMode.OFF, DataSaverMode.fromStoredValue("turbo"))
        assertEquals(DataSaverMode.OFF, DataSaverMode.fromStoredValue(null))
    }

    @Test
    fun `fresh installs start balanced while legacy installs retain off behavior`() {
        assertEquals(DataSaverMode.BALANCED, DataSaverMode.initialMode(hasLegacySettings = false))
        assertEquals(DataSaverMode.OFF, DataSaverMode.initialMode(hasLegacySettings = true))
    }

    @Test
    fun `worker mode snapshot updates atomically`() {
        val store = DataSaverModeStore(DataSaverMode.BALANCED)

        store.update(DataSaverMode.MAX)

        assertEquals(DataSaverMode.MAX, store.snapshot())
    }
}
