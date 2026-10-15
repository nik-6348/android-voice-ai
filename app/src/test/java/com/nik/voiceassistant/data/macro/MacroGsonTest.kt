package com.nik.voiceassistant.data.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trigger/Constraint/Action are sealed classes; MacroGson hand-writes their polymorphism. */
class MacroGsonTest {

    private fun roundTrip(macro: Macro): Macro {
        val json = MacroGson.gson.toJson(macro)
        return MacroGson.gson.fromJson(json, Macro::class.java)
    }

    @Test
    fun everyTriggerTypeRoundTrips() {
        val triggers = listOf(
            Trigger.Phrase("subah ka alarm"),
            Trigger.Manual,
            Trigger.Scheduled(7, 30, setOf(1, 2, 3)),
            Trigger.AppOpened("com.whatsapp", "WhatsApp"),
            Trigger.NotificationReceived("com.whatsapp", "WhatsApp", "Mom", null),
            Trigger.IncomingCall("+919876543210", "Mom"),
            Trigger.MissedCall(null, null),
            Trigger.ChargerConnected,
            Trigger.ChargerDisconnected,
            Trigger.WifiConnected("Home"),
            Trigger.BootCompleted
        )
        for (t in triggers) {
            val macro = Macro(title = "t", triggers = listOf(t), instruction = "i", action = Action.AlwaysAsk)
            assertEquals(t, roundTrip(macro).triggers.single())
        }
    }

    @Test
    fun everyConstraintTypeRoundTrips() {
        val constraints = listOf(
            Constraint.TimeWindow(9, 0, 18, 0),
            Constraint.Days(setOf(2, 3, 4, 5, 6)),
            Constraint.BatteryAtLeast(20),
            Constraint.BatteryAtMost(80),
            Constraint.WifiConnected,
            Constraint.ScreenOff
        )
        for (c in constraints) {
            val macro = Macro(title = "t", triggers = listOf(Trigger.Manual), constraints = listOf(c), instruction = "i", action = Action.AlwaysAsk)
            assertEquals(c, roundTrip(macro).constraints.single())
        }
    }

    @Test
    fun replayActionKeepsStepsAndArgs() {
        val steps = listOf(
            RecordedStep(toolName = "open_app", args = mapOf("app_name" to "YouTube"), description = "Open YouTube"),
            RecordedStep(toolName = "tap", args = mapOf("text" to "Search", "times" to 2), description = "Tap Search")
        )
        val macro = Macro(title = "t", triggers = listOf(Trigger.Phrase("play music")), instruction = "play music", action = Action.Replay(steps))
        val back = roundTrip(macro).action as Action.Replay
        assertEquals(2, back.steps.size)
        assertEquals("YouTube", back.steps[0].args["app_name"])
        assertEquals("Search", back.steps[1].args["text"])
    }

    @Test
    fun handleCallActionRoundTrips() {
        val action = Action.HandleCall(goal = "Order 2 pizzas", discloseAi = true, maxDurationMs = 120_000L)
        val macro = Macro(title = "t", triggers = listOf(Trigger.Manual), instruction = "i", action = action)
        val back = roundTrip(macro).action as Action.HandleCall
        assertEquals("Order 2 pizzas", back.goal)
        assertTrue(back.discloseAi)
        assertEquals(120_000L, back.maxDurationMs)
    }

    @Test
    fun arrayRoundTripsLikeMacroStoreUsesIt() {
        val macros = arrayOf(
            Macro(title = "A", triggers = listOf(Trigger.Phrase("a")), instruction = "a", action = Action.AlwaysAsk),
            Macro(title = "B", triggers = listOf(Trigger.Scheduled(6, 0, emptySet())), instruction = "b", action = Action.Replay(emptyList()))
        )
        val json = MacroGson.gson.toJson(macros)
        val back = MacroGson.gson.fromJson(json, Array<Macro>::class.java)
        assertEquals(2, back.size)
        assertEquals("A", back[0].title)
        assertTrue(back[1].triggers.single() is Trigger.Scheduled)
    }
}
