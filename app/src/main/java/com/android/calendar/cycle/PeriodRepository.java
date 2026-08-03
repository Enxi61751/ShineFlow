package com.android.calendar.cycle;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Local store for menstrual cycle records plus the learning / prediction logic.
 * Everything is kept on-device (a dedicated SharedPreferences file), nothing is
 * ever sent to a server.
 */
public class PeriodRepository {

    private static final String PREFS_NAME = "shineflow_period";
    private static final String KEY_RECORDS = "records";
    // These two live in the app's main preferences so the Settings screen can
    // drive them directly.
    public static final String KEY_ENABLED = "preferences_period_enabled";
    public static final String KEY_REMINDER_DAYS = "preferences_period_reminder_days";

    private static final int DEFAULT_CYCLE = 28;
    private static final int DEFAULT_PERIOD_LEN = 5;
    private static final int FERTILE_BEFORE_OVULATION = 5;
    private static final int FERTILE_AFTER_OVULATION = 1;

    private static PeriodRepository sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Gson mGson = new Gson();
    private List<PeriodRecord> mRecords;

    private PeriodRepository(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized PeriodRepository get(Context context) {
        if (sInstance == null) {
            sInstance = new PeriodRepository(context);
        }
        return sInstance;
    }

    private void load() {
        String json = mPrefs.getString(KEY_RECORDS, null);
        if (json == null) {
            mRecords = new ArrayList<>();
            return;
        }
        Type type = new TypeToken<ArrayList<PeriodRecord>>() {}.getType();
        List<PeriodRecord> parsed = mGson.fromJson(json, type);
        mRecords = parsed != null ? parsed : new ArrayList<>();
        sort();
    }

    private void persist() {
        mPrefs.edit().putString(KEY_RECORDS, mGson.toJson(mRecords)).apply();
    }

    private void sort() {
        Collections.sort(mRecords, new Comparator<PeriodRecord>() {
            @Override
            public int compare(PeriodRecord a, PeriodRecord b) {
                return Long.compare(a.startEpochDay, b.startEpochDay);
            }
        });
    }

    // ------------------------------------------------------------------ CRUD

    public synchronized List<PeriodRecord> getAll() {
        return new ArrayList<>(mRecords);
    }

    public synchronized void addOrUpdate(PeriodRecord record) {
        // Replace an existing record with the same start day, else add.
        for (int i = 0; i < mRecords.size(); i++) {
            if (mRecords.get(i).startEpochDay == record.startEpochDay) {
                mRecords.set(i, record);
                sort();
                persist();
                return;
            }
        }
        mRecords.add(record);
        sort();
        persist();
    }

    public synchronized void delete(long startEpochDay) {
        for (int i = mRecords.size() - 1; i >= 0; i--) {
            if (mRecords.get(i).startEpochDay == startEpochDay) {
                mRecords.remove(i);
                break;
            }
        }
        persist();
    }

    public synchronized PeriodRecord getByStart(long startEpochDay) {
        for (PeriodRecord r : mRecords) {
            if (r.startEpochDay == startEpochDay) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ Settings

    public int getReminderDays() {
        String value = com.android.calendar.Utils.getSharedPreference(mContext, KEY_REMINDER_DAYS, "2");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    public static boolean isEnabled(Context context) {
        return com.android.calendar.Utils.getSharedPreference(context, KEY_ENABLED, false);
    }

    // --------------------------------------------------------- Learning

    /** Average cycle length learned from the gaps between period starts. */
    public synchronized int averageCycleLength() {
        if (mRecords.size() < 2) {
            return DEFAULT_CYCLE;
        }
        long total = 0;
        int count = 0;
        for (int i = 1; i < mRecords.size(); i++) {
            long gap = mRecords.get(i).startEpochDay - mRecords.get(i - 1).startEpochDay;
            if (gap > 10 && gap < 90) { // ignore obviously wrong gaps
                total += gap;
                count++;
            }
        }
        if (count == 0) {
            return DEFAULT_CYCLE;
        }
        return (int) Math.round(total / (double) count);
    }

    /** Average period length learned from records that have an end date. */
    public synchronized int averagePeriodLength() {
        long total = 0;
        int count = 0;
        for (PeriodRecord r : mRecords) {
            if (r.endEpochDay != null && r.endEpochDay >= r.startEpochDay) {
                total += (r.endEpochDay - r.startEpochDay + 1);
                count++;
            }
        }
        if (count == 0) {
            return DEFAULT_PERIOD_LEN;
        }
        return (int) Math.round(total / (double) count);
    }

    public synchronized PeriodRecord lastRecord() {
        if (mRecords.isEmpty()) {
            return null;
        }
        return mRecords.get(mRecords.size() - 1);
    }

    public boolean hasData() {
        return lastRecord() != null;
    }

    /** Predicted start (epoch day) of the next period, or -1 if unknown. */
    public synchronized long predictedNextStart() {
        PeriodRecord last = lastRecord();
        if (last == null) {
            return -1;
        }
        return last.startEpochDay + averageCycleLength();
    }

    public long predictedOvulation() {
        long next = predictedNextStart();
        if (next < 0) {
            return -1;
        }
        return next - 14;
    }

    // --------------------------------------------------------- Day status

    public static final int STATUS_NONE = 0;
    public static final int STATUS_PERIOD = 1;         // logged period day
    public static final int STATUS_PREDICTED_PERIOD = 2;
    public static final int STATUS_FERTILE = 3;
    public static final int STATUS_OVULATION = 4;

    /**
     * Classify a given day (epoch day) for calendar marking. Logged period days
     * win over predictions.
     */
    public synchronized int statusFor(long epochDay) {
        // Logged period days.
        int periodLen = averagePeriodLength();
        for (PeriodRecord r : mRecords) {
            long end = r.endEpochDay != null ? r.endEpochDay
                    : r.startEpochDay + periodLen - 1;
            if (epochDay >= r.startEpochDay && epochDay <= end) {
                return STATUS_PERIOD;
            }
        }
        long nextStart = predictedNextStart();
        if (nextStart < 0) {
            return STATUS_NONE;
        }
        // Predicted next period days.
        if (epochDay >= nextStart && epochDay < nextStart + periodLen) {
            return STATUS_PREDICTED_PERIOD;
        }
        long ovulation = nextStart - 14;
        if (epochDay == ovulation) {
            return STATUS_OVULATION;
        }
        if (epochDay >= ovulation - FERTILE_BEFORE_OVULATION
                && epochDay <= ovulation + FERTILE_AFTER_OVULATION) {
            return STATUS_FERTILE;
        }
        return STATUS_NONE;
    }

    public static long todayEpochDay() {
        return LocalDate.now().toEpochDay();
    }
}
