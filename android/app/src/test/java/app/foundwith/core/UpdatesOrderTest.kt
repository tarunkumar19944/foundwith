package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesOrderTest {
    private val raju = "+919845011111"
    private val priya = "+919845022222"
    private val caller = "+919008011223"
    private val meena = "+919845033333"

    @Test
    fun theNumberThatChangedLastIsOnTop() {
        val items = UpdatesOrder.order(
            found = listOf(meena, raju, priya, caller),
            events = listOf(
                UpdateEvent(raju, 100, UpdateWhat.COUNT_UP),
                UpdateEvent(priya, 300, UpdateWhat.JOINED),
                UpdateEvent(caller, 200, UpdateWhat.CALL),
            ),
        )
        assertEquals(listOf(priya, caller, raju, meena), items.map { it.e164 })
        assertNull(items.last().latest)                 // nothing happened to Meena: at the bottom
    }

    @Test
    fun eachNumberOnceWithItsLatestEvent() {
        val items = UpdatesOrder.order(
            found = listOf(caller),
            events = listOf(
                UpdateEvent(caller, 100, UpdateWhat.CALL),
                UpdateEvent(caller, 150, UpdateWhat.NOW_KNOWN),
                UpdateEvent(caller, 400, UpdateWhat.CALL),
            ),
        )
        assertEquals(1, items.size)
        assertEquals(400L, items[0].at)
        assertEquals(UpdateWhat.CALL, items[0].latest?.what)
        assertEquals(UpdateWhat.NOW_KNOWN, items[0].change?.what)   // still says what changed
    }

    @Test
    fun aChangeNamesTheRowWhenItCameWithTheCall() {
        val items = UpdatesOrder.order(listOf(caller), listOf(
            UpdateEvent(caller, 100, UpdateWhat.CALL), UpdateEvent(caller, 100, UpdateWhat.NOW_KNOWN)))
        assertEquals(UpdateWhat.NOW_KNOWN, items[0].latest?.what)
    }

    @Test
    fun numbersNotShownAreLeftOut() {
        val items = UpdatesOrder.order(listOf(raju), listOf(UpdateEvent(priya, 500, UpdateWhat.JOINED)))
        assertEquals(listOf(raju), items.map { it.e164 })
    }

    @Test
    fun newIsWhatHappenedSinceUpdatesWasLastSeen() {
        val items = UpdatesOrder.order(
            found = listOf(meena, raju, priya),
            events = listOf(UpdateEvent(raju, 100, UpdateWhat.COUNT_UP), UpdateEvent(priya, 300, UpdateWhat.JOINED)),
        )
        assertEquals(listOf(priya), UpdatesOrder.newSince(items, seenAt = 200).map { it.e164 })
        assertEquals(listOf(priya, raju), UpdatesOrder.newSince(items, seenAt = 50).map { it.e164 })
        assertEquals(emptyList<UpdateItem>(), UpdatesOrder.newSince(items, seenAt = 300))
        assertEquals(emptyList<UpdateItem>(), UpdatesOrder.newSince(items, seenAt = 0))   // never looked: nothing marked
    }

    @Test
    fun openingANumbersCardClearsItsMarkOnly() {
        val items = UpdatesOrder.order(
            found = listOf(raju, priya, caller),
            events = listOf(UpdateEvent(raju, 100, UpdateWhat.COUNT_UP), UpdateEvent(priya, 300, UpdateWhat.JOINED),
                UpdateEvent(caller, 200, UpdateWhat.CALL)),
        )
        // Priya's card was opened at 310: she is no longer new; Raju and the caller still are.
        assertEquals(listOf(caller, raju), UpdatesOrder.newSince(items, seenAt = 50, seen = mapOf(priya to 310L)).map { it.e164 })
        // A change after the card was opened is new again.
        assertEquals(listOf(priya, caller, raju), UpdatesOrder.newSince(items, seenAt = 50, seen = mapOf(priya to 250L)).map { it.e164 })
    }
}
