package com.android.calendar;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A local, unscheduled draft. It is deliberately not a Calendar Provider event. */
public final class TransitSchedule {
    public final String id;
    public final String title;
    public final String location;
    public final String description;
    public final long calendarId;
    public final long durationMillis;
    public final boolean allDay;

    public TransitSchedule(String id, String title, String location, String description,
            long calendarId, long durationMillis, boolean allDay) {
        this.id = id;
        this.title = title;
        this.location = location;
        this.description = description;
        this.calendarId = calendarId;
        this.durationMillis = durationMillis;
        this.allDay = allDay;
    }

    public static TransitSchedule create(String title, String location, String description,
            long calendarId, long durationMillis, boolean allDay) {
        return new TransitSchedule(UUID.randomUUID().toString(), title, location, description,
                calendarId, Math.max(durationMillis, 15 * 60 * 1000L), allDay);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id).put("title", title).put("location", location)
                .put("description", description).put("calendarId", calendarId)
                .put("duration", durationMillis).put("allDay", allDay);
    }

    static TransitSchedule fromJson(JSONObject object) {
        return new TransitSchedule(object.optString("id"), object.optString("title"),
                object.optString("location"), object.optString("description"),
                object.optLong("calendarId", -1), object.optLong("duration", 60 * 60 * 1000L),
                object.optBoolean("allDay"));
    }
}
