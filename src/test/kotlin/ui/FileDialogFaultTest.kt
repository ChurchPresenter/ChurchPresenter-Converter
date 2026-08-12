package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the file pickers do when the dialog itself throws.
 *
 * `JFileChooser` can throw out of its own focus handling — Swing's `FilePane` repaints the
 * selection with a null rectangle, JDK-6561072, open since 2007 — and the exception unwinds
 * through the picker and off the end of the event dispatch thread. The dialog cannot be retried
 * and the operator did nothing wrong, so it is taken as dismissed; the alternative is the
 * converter dying because someone clicked Browse.
 */
class FileDialogFaultTest {

    @Test
    fun `a dialog that returns normally is left alone`() {
        assertEquals("/home/leader/Bibles", dialogOrCancelled(null) { "/home/leader/Bibles" })
    }

    @Test
    fun `a dialog that throws reads as cancelled`() {
        assertNull(
            dialogOrCancelled<String?>(null) {
                throw NullPointerException("Cannot read field \"x\" because \"<parameter1>\" is null")
            },
        )
    }

    @Test
    fun `the cancelled value is the caller's, not always null`() {
        // The multi-file picker answers with a list, and an empty one is its "nothing chosen".
        assertEquals(emptyList(), dialogOrCancelled(emptyList<String>()) { error("dialog fell over") })
    }
}
