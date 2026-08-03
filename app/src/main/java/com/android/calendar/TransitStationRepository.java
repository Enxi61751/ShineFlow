package com.android.calendar;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Persistent application-local storage for schedules whose time is undecided. */
public final class TransitStationRepository {
    private static final String PREFS = "transit_station";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences preferences;

    public TransitStationRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<TransitSchedule> getAll() {
        ArrayList<TransitSchedule> schedules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                schedules.add(TransitSchedule.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_ITEMS).apply();
        }
        return schedules;
    }

    public synchronized void add(TransitSchedule schedule) {
        List<TransitSchedule> schedules = getAll();
        schedules.add(schedule);
        save(schedules);
    }

    public synchronized void remove(String id) {
        List<TransitSchedule> schedules = getAll();
        for (int i = schedules.size() - 1; i >= 0; i--) {
            if (schedules.get(i).id.equals(id)) schedules.remove(i);
        }
        save(schedules);
    }

    public synchronized TransitSchedule get(String id) {
        for (TransitSchedule schedule : getAll()) {
            if (schedule.id.equals(id)) return schedule;
        }
        return null;
    }

    private void save(List<TransitSchedule> schedules) {
        JSONArray array = new JSONArray();
        for (TransitSchedule schedule : schedules) {
            try {
                array.put(schedule.toJson());
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
