package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleObservationOrderTest {
    private fun ms(value: Long) = value * 1_000_000

    @Test fun onlyNewerObservationsCanReplaceTheSelectedIdentityState() {
        val order = BleObservationOrder(15_000)
        assertTrue(order.accept(ms(2_000), ms(3_000)))
        assertFalse(order.accept(ms(1_000), ms(3_000)))
        assertFalse(order.accept(ms(2_000), ms(4_000)))
        assertTrue(order.accept(ms(3_000), ms(4_000)))
    }

    @Test fun orderingKeepsNanosecondPrecisionWithinTheSameMillisecond() {
        val order = BleObservationOrder(15_000)
        assertTrue(order.accept(1_000_001, 2_000_000))
        assertFalse(order.accept(1_000_000, 2_000_000))
        assertTrue(order.accept(1_000_002, 2_000_000))
    }

    @Test fun freshnessIncludesTheExistingTimeoutBoundary() {
        val order = BleObservationOrder(15_000)
        assertFalse(order.accept(ms(1_000) - 1, ms(16_000)))
        assertTrue(order.accept(ms(1_000), ms(16_000)))
    }

    @Test fun oldResultsCannotEstablishPresenceWithoutAnEarlierObservation() {
        val order = BleObservationOrder(15_000)
        assertFalse(order.accept(ms(1_000), ms(20_000)))
        assertTrue(order.accept(ms(19_000), ms(20_000)))
    }

    @Test fun invalidFutureResultsCannotPoisonLaterValidObservations() {
        val order = BleObservationOrder(15_000)
        assertFalse(order.accept(-1, ms(1_000)))
        assertFalse(order.accept(ms(1_000) + 1, ms(1_000)))
        assertFalse(order.accept(Long.MAX_VALUE, ms(1_000)))
        assertTrue(order.accept(ms(900), ms(1_000)))
    }

    @Test fun changingIdentityResetsOrderingForItsFirstValidObservation() {
        val order = BleObservationOrder(15_000)
        assertTrue(order.accept(ms(10_000), ms(10_000)))
        order.reset()
        assertTrue(order.accept(ms(9_000), ms(11_000)))
        assertFalse(order.accept(ms(8_000), ms(11_000)))
    }

    @Test fun zeroIsAValidObservationTime() {
        val order = BleObservationOrder(15_000)
        assertTrue(order.accept(0, 0))
        assertFalse(order.accept(0, 1))
        assertTrue(order.accept(1, 1))
    }
}
