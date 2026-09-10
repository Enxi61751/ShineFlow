package com.android.calendar.cycle;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local store for menstrual cycle records plus the learning / prediction logic.
 * Data is persisted via SharedPreferences + Gson serialization.
 * Basal body temperature records are stored separately.
 */
public class PeriodRepository {

    public static final String KEY_ENABLED = "preferences_period_enabled";
    public static final String KEY_REMINDER_DAYS = "preferences_period_reminder_days";

    private static final int DEFAULT_CYCLE = 28;
    private static final int DEFAULT_PERIOD_LEN = 5;
    private static final int FERTILE_BEFORE_OVULATION = 5;
    private static final int FERTILE_AFTER_OVULATION = 1;

    private static final int MAX_RECENT_CYCLES = 6;
    private static final double WEIGHT_DECAY = 0.7;

    private static final String PREFS_NAME = "shineflow_period";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_TEMPERATURES = "temperatures";

    private static PeriodRepository sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Gson mGson = new Gson();
    private List<PeriodRecord> mRecords;
    private List<TemperatureRecord> mTemperatures;

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
        // Load period records
        String json = mPrefs.getString(KEY_RECORDS, null);
        if (json == null) {
            mRecords = new ArrayList<>();
        } else {
            Type type = new TypeToken<ArrayList<PeriodRecord>>() {}.getType();
            List<PeriodRecord> parsed = mGson.fromJson(json, type);
            mRecords = parsed != null ? parsed : new ArrayList<>();
            Collections.sort(mRecords, (a, b) -> Long.compare(a.startEpochDay, b.startEpochDay));
        }
        // Load temperature records
        String tempJson = mPrefs.getString(KEY_TEMPERATURES, null);
        if (tempJson == null) {
            mTemperatures = new ArrayList<>();
        } else {
            Type type = new TypeToken<ArrayList<TemperatureRecord>>() {}.getType();
            List<TemperatureRecord> parsed = mGson.fromJson(tempJson, type);
            mTemperatures = parsed != null ? parsed : new ArrayList<>();
        }
    }

    private void persistRecords() {
        mPrefs.edit().putString(KEY_RECORDS, mGson.toJson(mRecords)).apply();
    }

    private void persistTemperatures() {
        mPrefs.edit().putString(KEY_TEMPERATURES, mGson.toJson(mTemperatures)).apply();
    }

    // ---- CRUD ----

    public synchronized List<PeriodRecord> getAll() {
        return new ArrayList<>(mRecords);
    }

    public synchronized void addOrUpdate(PeriodRecord record) {
        for (int i = 0; i < mRecords.size(); i++) {
            if (mRecords.get(i).startEpochDay == record.startEpochDay) {
                record.id = mRecords.get(i).id;
                mRecords.set(i, record);
                Collections.sort(mRecords, (a, b) -> Long.compare(a.startEpochDay, b.startEpochDay));
                persistRecords();
                return;
            }
        }
        record.id = System.currentTimeMillis(); // simple unique id
        mRecords.add(record);
        Collections.sort(mRecords, (a, b) -> Long.compare(a.startEpochDay, b.startEpochDay));
        persistRecords();
    }

    public synchronized void delete(long startEpochDay) {
        for (int i = mRecords.size() - 1; i >= 0; i--) {
            if (mRecords.get(i).startEpochDay == startEpochDay) {
                mRecords.remove(i);
                break;
            }
        }
        persistRecords();
    }

    public synchronized PeriodRecord getByStart(long startEpochDay) {
        for (PeriodRecord r : mRecords) {
            if (r.startEpochDay == startEpochDay) return r;
        }
        return null;
    }

    // ---- Temperature ----

    public void addTemperature(long dateEpochDay, double temperature, String timeOfDay, String notes) {
        for (TemperatureRecord t : mTemperatures) {
            if (t.dateEpochDay == dateEpochDay) {
                t.temperature = temperature;
                t.timeOfDay = timeOfDay;
                t.notes = notes;
                persistTemperatures();
                return;
            }
        }
        mTemperatures.add(new TemperatureRecord(dateEpochDay, temperature, timeOfDay, notes));
        Collections.sort(mTemperatures, (a, b) -> Long.compare(a.dateEpochDay, b.dateEpochDay));
        persistTemperatures();
    }

    public List<TemperatureRecord> getTemperatures() {
        return new ArrayList<>(mTemperatures);
    }

    public List<TemperatureRecord> getTemperaturesInRange(long startDay, long endDay) {
        List<TemperatureRecord> result = new ArrayList<>();
        for (TemperatureRecord t : mTemperatures) {
            if (t.dateEpochDay >= startDay && t.dateEpochDay <= endDay) {
                result.add(t);
            }
        }
        return result;
    }

    public TemperatureRecord getTemperature(long dateEpochDay) {
        for (TemperatureRecord t : mTemperatures) {
            if (t.dateEpochDay == dateEpochDay) return t;
        }
        return null;
    }

    public void deleteTemperature(long dateEpochDay) {
        for (int i = mTemperatures.size() - 1; i >= 0; i--) {
            if (mTemperatures.get(i).dateEpochDay == dateEpochDay) {
                mTemperatures.remove(i);
                break;
            }
        }
        persistTemperatures();
    }

    // ---- Settings ----

    public int getReminderDays() {
        String value = com.android.calendar.Utils.getSharedPreference(mContext, KEY_REMINDER_DAYS, "2");
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 2; }
    }

    public static boolean isEnabled(Context context) {
        return com.android.calendar.Utils.getSharedPreference(context, KEY_ENABLED, false);
    }

    // ---- Prediction (weighted moving average + stddev) ----

    public synchronized int averageCycleLength() {
        if (mRecords.size() < 2) return DEFAULT_CYCLE;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < mRecords.size(); i++) {
            long gap = mRecords.get(i).startEpochDay - mRecords.get(i - 1).startEpochDay;
            if (gap > 10 && gap < 90) gaps.add(gap);
        }
        if (gaps.isEmpty()) return DEFAULT_CYCLE;

        int recent = Math.min(gaps.size(), MAX_RECENT_CYCLES);
        double totalWeight = 0;
        double weightedSum = 0;
        for (int i = 0; i < recent; i++) {
            int idx = gaps.size() - 1 - i;
            double weight = Math.pow(WEIGHT_DECAY, i);
            weightedSum += gaps.get(idx) * weight;
            totalWeight += weight;
        }
        return (int) Math.round(weightedSum / totalWeight);
    }

    public synchronized int cycleLengthStdDev() {
        if (mRecords.size() < 3) return 2;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < mRecords.size(); i++) {
            long gap = mRecords.get(i).startEpochDay - mRecords.get(i - 1).startEpochDay;
            if (gap > 10 && gap < 90) gaps.add(gap);
        }
        if (gaps.size() < 2) return 2;
        double mean = averageCycleLength();
        double sumSq = 0;
        int count = 0;
        int recent = Math.min(gaps.size(), MAX_RECENT_CYCLES);
        for (int i = gaps.size() - recent; i < gaps.size(); i++) {
            sumSq += Math.pow(gaps.get(i) - mean, 2);
            count++;
        }
        return (int) Math.round(Math.sqrt(sumSq / count));
    }

    public synchronized int averagePeriodLength() {
        long total = 0;
        int count = 0;
        for (PeriodRecord r : mRecords) {
            if (r.endEpochDay != null && r.endEpochDay >= r.startEpochDay) {
                total += (r.endEpochDay - r.startEpochDay + 1);
                count++;
            }
        }
        return count == 0 ? DEFAULT_PERIOD_LEN : (int) Math.round(total / (double) count);
    }

    public synchronized PeriodRecord lastRecord() {
        return mRecords.isEmpty() ? null : mRecords.get(mRecords.size() - 1);
    }

    public boolean hasData() { return lastRecord() != null; }

    public synchronized long predictedNextStart() {
        PeriodRecord last = lastRecord();
        return last == null ? -1 : last.startEpochDay + averageCycleLength();
    }

    public long predictedNextStartMin() {
        long p = predictedNextStart();
        return p < 0 ? -1 : p - cycleLengthStdDev();
    }

    public long predictedNextStartMax() {
        long p = predictedNextStart();
        return p < 0 ? -1 : p + cycleLengthStdDev();
    }

    public long predictedOvulation() {
        long next = predictedNextStart();
        return next < 0 ? -1 : next - 14;
    }

    // ---- Day status ----

    public static final int STATUS_NONE = 0;
    public static final int STATUS_PERIOD = 1;
    public static final int STATUS_PREDICTED_PERIOD = 2;
    public static final int STATUS_FERTILE = 3;
    public static final int STATUS_OVULATION = 4;

    public synchronized int statusFor(long epochDay) {
        int periodLen = averagePeriodLength();
        for (PeriodRecord r : mRecords) {
            long end = r.endEpochDay != null ? r.endEpochDay : r.startEpochDay + periodLen - 1;
            if (epochDay >= r.startEpochDay && epochDay <= end) return STATUS_PERIOD;
        }
        long nextStart = predictedNextStart();
        if (nextStart < 0) return STATUS_NONE;
        if (epochDay >= nextStart && epochDay < nextStart + periodLen) return STATUS_PREDICTED_PERIOD;
        long ovulation = nextStart - 14;
        if (epochDay == ovulation) return STATUS_OVULATION;
        if (epochDay >= ovulation - FERTILE_BEFORE_OVULATION
                && epochDay <= ovulation + FERTILE_AFTER_OVULATION) return STATUS_FERTILE;
        return STATUS_NONE;
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_PERIOD: return "經期";
            case STATUS_PREDICTED_PERIOD: return "預測經期";
            case STATUS_FERTILE: return "易孕期";
            case STATUS_OVULATION: return "排卵日";
            default: return "";
        }
    }

    public static long todayEpochDay() {
        return LocalDate.now().toEpochDay();
    }

    // ---- Temperature record model ----

    public static class TemperatureRecord {
        public long dateEpochDay;
        public double temperature;
        public String timeOfDay;
        public String notes;

        public TemperatureRecord() {}

        public TemperatureRecord(long dateEpochDay, double temperature,
                                  String timeOfDay, String notes) {
            this.dateEpochDay = dateEpochDay;
            this.temperature = temperature;
            this.timeOfDay = timeOfDay;
            this.notes = notes;
        }
    }
}
