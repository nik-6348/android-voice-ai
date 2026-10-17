package com.nik.voiceassistant.data.macro

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Gson doesn't know sealed classes are polymorphic on its own: a plain registration would need every
 * subtype's generic signature to survive R8 (fragile) or would just serialize as the base type's own
 * (empty) fields. Each adapter here writes a "type" field by hand and reads it back; no reflection, so
 * nothing here depends on signatures R8 could strip. Add a line here whenever a subtype is added above.
 */
object MacroGson {

    val gson = GsonBuilder()
        .registerTypeAdapter(Trigger::class.java, TriggerAdapter())
        .registerTypeAdapter(Constraint::class.java, ConstraintAdapter())
        .registerTypeAdapter(Action::class.java, ActionAdapter())
        .create()

    private class TriggerAdapter : JsonSerializer<Trigger>, JsonDeserializer<Trigger> {
        override fun serialize(src: Trigger, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
            val type = when (src) {
                is Trigger.Phrase -> "phrase"
                Trigger.Manual -> "manual"
                is Trigger.Scheduled -> "scheduled"
                is Trigger.AppOpened -> "app_opened"
                is Trigger.NotificationReceived -> "notification"
                is Trigger.IncomingCall -> "incoming_call"
                is Trigger.MissedCall -> "missed_call"
                Trigger.ChargerConnected -> "charger_connected"
                Trigger.ChargerDisconnected -> "charger_disconnected"
                is Trigger.WifiConnected -> "wifi_connected"
                Trigger.BootCompleted -> "boot"
                is Trigger.Webhook -> "webhook"
                is Trigger.BroadcastEvent -> "broadcast_event"
                is Trigger.Condition -> "condition"
            }
            val obj = ctx.serialize(src, src.javaClass).asJsonObject
            obj.addProperty("type", type)
            return obj
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): Trigger {
            val obj = json.asJsonObject
            return when (obj.get("type")?.asString) {
                "phrase" -> ctx.deserialize(obj, Trigger.Phrase::class.java)
                "scheduled" -> ctx.deserialize(obj, Trigger.Scheduled::class.java)
                "app_opened" -> ctx.deserialize(obj, Trigger.AppOpened::class.java)
                "notification" -> ctx.deserialize(obj, Trigger.NotificationReceived::class.java)
                "incoming_call" -> ctx.deserialize(obj, Trigger.IncomingCall::class.java)
                "missed_call" -> ctx.deserialize(obj, Trigger.MissedCall::class.java)
                "charger_connected" -> Trigger.ChargerConnected
                "charger_disconnected" -> Trigger.ChargerDisconnected
                "wifi_connected" -> ctx.deserialize(obj, Trigger.WifiConnected::class.java)
                "boot" -> Trigger.BootCompleted
                "webhook" -> ctx.deserialize(obj, Trigger.Webhook::class.java)
                "broadcast_event" -> ctx.deserialize(obj, Trigger.BroadcastEvent::class.java)
                "condition" -> ctx.deserialize(obj, Trigger.Condition::class.java)
                else -> Trigger.Manual
            }
        }
    }

    private class ConstraintAdapter : JsonSerializer<Constraint>, JsonDeserializer<Constraint> {
        override fun serialize(src: Constraint, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
            val type = when (src) {
                is Constraint.TimeWindow -> "time_window"
                is Constraint.Days -> "days"
                is Constraint.BatteryAtLeast -> "battery_at_least"
                is Constraint.BatteryAtMost -> "battery_at_most"
                Constraint.WifiConnected -> "wifi_connected"
                Constraint.ScreenOff -> "screen_off"
            }
            val obj = ctx.serialize(src, src.javaClass).asJsonObject
            obj.addProperty("type", type)
            return obj
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): Constraint {
            val obj = json.asJsonObject
            return when (obj.get("type")?.asString) {
                "time_window" -> ctx.deserialize(obj, Constraint.TimeWindow::class.java)
                "days" -> ctx.deserialize(obj, Constraint.Days::class.java)
                "battery_at_least" -> ctx.deserialize(obj, Constraint.BatteryAtLeast::class.java)
                "battery_at_most" -> ctx.deserialize(obj, Constraint.BatteryAtMost::class.java)
                "wifi_connected" -> Constraint.WifiConnected
                else -> Constraint.ScreenOff
            }
        }
    }

    private class ActionAdapter : JsonSerializer<Action>, JsonDeserializer<Action> {
        override fun serialize(src: Action, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
            val type = when (src) {
                is Action.Replay -> "replay"
                Action.AlwaysAsk -> "always_ask"
                is Action.HandleCall -> "handle_call"
            }
            val obj = if (src is Action.Replay || src is Action.HandleCall) ctx.serialize(src, src.javaClass).asJsonObject else JsonObject()
            obj.addProperty("type", type)
            return obj
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): Action {
            val obj = json.asJsonObject
            return when (obj.get("type")?.asString) {
                "replay" -> ctx.deserialize(obj, Action.Replay::class.java)
                "handle_call" -> ctx.deserialize(obj, Action.HandleCall::class.java)
                else -> Action.AlwaysAsk
            }
        }
    }
}
