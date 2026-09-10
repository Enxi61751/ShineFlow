package com.android.calendar.cycle;

import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.calendar.theme.DynamicThemeKt;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;
import ws.xsoh.etar.R;

/**
 * Dedicated cycle-tracking screen: summary overview, quick start/end logging,
 * BBT temperature chart, per-record editor (flow / symptoms / mood / notes),
 * and period history. All data is local.
 */
public class PeriodActivity extends AppCompatActivity {

    private PeriodRepository mRepo;
    private int mDensity;

    // Views
    private Toolbar mToolbar;
    private TextView mCycleDayInfo;
    private TextView mNextPeriodInfo;
    private TextView mOvulationInfo;
    private TextView mAveragesInfo;
    private MaterialButton mBtnLogStart;
    private MaterialButton mBtnLogCustom;
    private BbtChartView mBbtChart;
    private TextView mBbtEmptyText;
    private MaterialButton mBtnAddTemp;
    private LinearLayout mHistoryContainer;

    private final DateTimeFormatter mDateFmt =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
    private final DateTimeFormatter mShortDateFmt =
            DateTimeFormatter.ofPattern("MMM d");

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicThemeKt.applyTheme(this);
        setContentView(R.layout.period_activity);

        mRepo = PeriodRepository.get(this);
        mDensity = (int) getResources().getDisplayMetrics().density;

        findViews();
        setupToolbar();
        setupListeners();
        refresh();
    }

    // =================================================================== view wiring

    private void findViews() {
        mToolbar = findViewById(R.id.toolbar);
        mCycleDayInfo = findViewById(R.id.cycle_day_info);
        mNextPeriodInfo = findViewById(R.id.next_period_info);
        mOvulationInfo = findViewById(R.id.ovulation_info);
        mAveragesInfo = findViewById(R.id.averages_info);
        mBtnLogStart = findViewById(R.id.btn_log_start);
        mBtnLogCustom = findViewById(R.id.btn_log_custom);
        mBbtChart = findViewById(R.id.bbt_chart);
        mBbtEmptyText = findViewById(R.id.bbt_empty_text);
        mBtnAddTemp = findViewById(R.id.btn_add_temp);
        mHistoryContainer = findViewById(R.id.history_container);
    }

    private void setupToolbar() {
        mToolbar.setTitle(R.string.period_cycle_tracking);
        setSupportActionBar(mToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setupListeners() {
        mBtnLogStart.setOnClickListener(v -> onPrimaryAction());
        mBtnLogCustom.setOnClickListener(v -> pickCustomDate());
        mBtnAddTemp.setOnClickListener(v -> showAddTemperatureDialog());
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =================================================================== refresh

    private void refresh() {
        renderSummary();
        renderQuickActions();
        renderBBTChart();
        renderHistory();
        PeriodReminderReceiver.schedule(this);
    }

    // ---------------------------------------------------- summary

    private void renderSummary() {
        long today = PeriodRepository.todayEpochDay();

        if (!mRepo.hasData()) {
            mCycleDayInfo.setText(R.string.period_no_data);
            mNextPeriodInfo.setText("");
            mOvulationInfo.setText("");
            mAveragesInfo.setText("");
            return;
        }

        PeriodRecord last = mRepo.lastRecord();
        boolean ongoing = last != null && last.endEpochDay == null;
        int status = mRepo.statusFor(today);

        // Cycle day / period day
        if (status == PeriodRepository.STATUS_PERIOD && ongoing) {
            mCycleDayInfo.setText(getString(R.string.period_in_period,
                    (int) (today - last.startEpochDay + 1)));
        } else {
            mCycleDayInfo.setText(getString(R.string.period_cycle_day,
                    (int) (today - last.startEpochDay + 1)));
        }

        // Next period prediction with range
        long nextStart = mRepo.predictedNextStart();
        long daysLeft = nextStart - today;
        String nextLine;
        if (daysLeft > 0) {
            nextLine = getString(R.string.period_next_in, (int) daysLeft);
        } else if (daysLeft == 0) {
            nextLine = getString(R.string.period_next_today);
        } else {
            nextLine = getString(R.string.period_next_overdue, (int) -daysLeft);
        }
        long rangeMin = mRepo.predictedNextStartMin();
        long rangeMax = mRepo.predictedNextStartMax();
        if (rangeMin > 0 && rangeMax > 0) {
            String minStr = LocalDate.ofEpochDay(rangeMin).format(mShortDateFmt);
            String maxStr = LocalDate.ofEpochDay(rangeMax).format(mShortDateFmt);
            nextLine += " " + getString(R.string.period_expected_range, minStr, maxStr);
        }
        mNextPeriodInfo.setText(nextLine);

        // Ovulation
        long ovulation = mRepo.predictedOvulation();
        if (ovulation > 0) {
            String ovStr = getString(R.string.period_ovulation) + " — "
                    + LocalDate.ofEpochDay(ovulation).format(mShortDateFmt);
            if (status == PeriodRepository.STATUS_OVULATION) {
                ovStr += " ✓";
            } else if (status == PeriodRepository.STATUS_FERTILE) {
                ovStr += " (" + getString(R.string.period_fertile) + ")";
            }
            mOvulationInfo.setText(ovStr);
        } else {
            mOvulationInfo.setText("");
        }

        // Averages
        mAveragesInfo.setText(getString(R.string.period_avg_cycle, mRepo.averageCycleLength())
                + "  ·  " + getString(R.string.period_avg_length, mRepo.averagePeriodLength()));
    }

    // ---------------------------------------------------- quick actions

    private void renderQuickActions() {
        long today = PeriodRepository.todayEpochDay();
        PeriodRecord last = mRepo.lastRecord();
        boolean ongoing = last != null && last.endEpochDay == null && last.startEpochDay <= today;

        mBtnLogStart.setText(ongoing
                ? R.string.period_end_period
                : R.string.period_start_period);
    }

    // ---------------------------------------------------- BBT chart

    private void renderBBTChart() {
        List<PeriodRepository.TemperatureRecord> temps = mRepo.getTemperatures();
        if (temps == null || temps.isEmpty()) {
            mBbtChart.setVisibility(View.GONE);
            mBbtEmptyText.setVisibility(View.VISIBLE);
            return;
        }

        mBbtChart.setVisibility(View.VISIBLE);
        mBbtEmptyText.setVisibility(View.GONE);

        List<Pair<Long, Double>> chartData = new ArrayList<>();
        for (PeriodRepository.TemperatureRecord t : temps) {
            chartData.add(new Pair<>(t.dateEpochDay, t.temperature));
        }
        mBbtChart.setData(chartData);

        // Period ranges for highlighting
        List<Pair<Long, Long>> ranges = new ArrayList<>();
        for (PeriodRecord r : mRepo.getAll()) {
            long end = r.endEpochDay != null ? r.endEpochDay : r.startEpochDay + mRepo.averagePeriodLength() - 1;
            ranges.add(new Pair<>(r.startEpochDay, end));
        }
        mBbtChart.setPeriodRanges(ranges);
    }

    // ---------------------------------------------------- history

    private void renderHistory() {
        mHistoryContainer.removeAllViews();
        List<PeriodRecord> all = mRepo.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            final PeriodRecord r = all.get(i);
            mHistoryContainer.addView(buildHistoryRow(r));
        }
    }

    private View buildHistoryRow(final PeriodRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = 10 * mDensity;
        row.setPadding(0, vPad, 0, vPad);

        // Color dot
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable c =
                new android.graphics.drawable.GradientDrawable();
        c.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int color = 0xFFFF4F9A;
        if (r.flow == 1) color = 0xFFFFB6C1;   // light
        else if (r.flow == 2) color = 0xFFFF4F9A; // medium
        else if (r.flow == 3) color = 0xFFC2185B; // heavy
        c.setColor(color);
        dot.setBackground(c);
        LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(12 * mDensity, 12 * mDensity);
        dotLp.rightMargin = 12 * mDensity;
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(dot, dotLp);

        // Date range text
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, tcLp);

        String start = LocalDate.ofEpochDay(r.startEpochDay).format(mDateFmt);
        String range = r.endEpochDay != null
                ? start + " – " + LocalDate.ofEpochDay(r.endEpochDay).format(mDateFmt)
                : start + " (" + getString(R.string.period_ongoing) + ")";
        TextView dateTv = new TextView(this);
        dateTv.setText(range);
        dateTv.setTextSize(15);
        dateTv.setTextColor(0xFF000000);
        textCol.addView(dateTv);

        // Subtitle with flow info
        if (r.flow > 0) {
            TextView flowTv = new TextView(this);
            int flowRes;
            switch (r.flow) {
                case 1: flowRes = R.string.period_flow_light; break;
                case 2: flowRes = R.string.period_flow_medium; break;
                case 3: flowRes = R.string.period_flow_heavy; break;
                default: flowRes = 0;
            }
            if (flowRes != 0) {
                flowTv.setText(getString(flowRes));
            }
            flowTv.setTextSize(12);
            flowTv.setTextColor(0xFF888888);
            textCol.addView(flowTv);
        }

        // Delete button
        MaterialButton delBtn = new MaterialButton(this);
        delBtn.setText("✕");
        delBtn.setTextSize(16);
        delBtn.setPadding(8 * mDensity, 4 * mDensity, 8 * mDensity, 4 * mDensity);
        delBtn.setOnClickListener(v -> {
            mRepo.delete(r.startEpochDay);
            refresh();
        });
        row.addView(delBtn);

        // Tap row to edit
        row.setClickable(true);
        row.setOnClickListener(v -> showRecordEditor(r));
        // Use resolveAttribute for selectableItemBackground
        android.util.TypedValue outVal = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outVal, true);
        row.setBackgroundResource(outVal.resourceId);

        return row;
    }

    // =================================================================== actions

    private void onPrimaryAction() {
        long today = PeriodRepository.todayEpochDay();
        PeriodRecord last = mRepo.lastRecord();
        if (last != null && last.endEpochDay == null && last.startEpochDay <= today) {
            // Close the ongoing period
            last.endEpochDay = today;
            mRepo.addOrUpdate(last);
        } else {
            PeriodRecord r = new PeriodRecord();
            r.startEpochDay = today;
            mRepo.addOrUpdate(r);
        }
        refresh();
    }

    private void pickCustomDate() {
        LocalDate now = LocalDate.now();
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            PeriodRecord r = new PeriodRecord();
            r.startEpochDay = LocalDate.of(year, month + 1, day).toEpochDay();
            showRecordEditor(r);
        }, now.getYear(), now.getMonthValue() - 1, now.getDayOfMonth());
        dialog.show();
    }

    // =================================================================== temperature dialog

    private void showAddTemperatureDialog() {
        int pad = 20 * mDensity;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, 8 * mDensity, pad, 16 * mDensity);

        // Date picker
        final long[] selectedDay = {PeriodRepository.todayEpochDay()};
        final MaterialButton dateBtn = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        dateBtn.setText(getString(R.string.period_temperature_date) + ": "
                + LocalDate.ofEpochDay(selectedDay[0]).format(mDateFmt));
        dateBtn.setOnClickListener(v -> {
            LocalDate d = LocalDate.ofEpochDay(selectedDay[0]);
            new DatePickerDialog(PeriodActivity.this, (view, y, m, day) -> {
                selectedDay[0] = LocalDate.of(y, m + 1, day).toEpochDay();
                dateBtn.setText(getString(R.string.period_temperature_date) + ": "
                        + LocalDate.ofEpochDay(selectedDay[0]).format(mDateFmt));
            }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
        });
        content.addView(dateBtn);

        // Temperature input
        TextView tempLabel = new TextView(this);
        tempLabel.setText(R.string.period_temperature_hint);
        tempLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tempLabel.setPadding(0, 16 * mDensity, 0, 4 * mDensity);
        content.addView(tempLabel);

        final EditText tempInput = new EditText(this);
        tempInput.setHint("36.5");
        tempInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tempInput.setText("36.5");
        content.addView(tempInput);

        // Time of day chips
        addSectionLabel(content, R.string.period_time_morning);
        final ChipGroup timeGroup = new ChipGroup(this);
        timeGroup.setSingleSelection(true);
        String[] timeLabels = {getString(R.string.period_time_morning), getString(R.string.period_time_evening)};
        for (String label : timeLabels) {
            Chip chip = new Chip(this);
            chip.setText(label);
            chip.setCheckable(true);
            chip.setTag(label);
            timeGroup.addView(chip);
        }
        // Default: morning selected
        if (timeGroup.getChildCount() > 0) {
            ((Chip) timeGroup.getChildAt(0)).setChecked(true);
        }
        content.addView(timeGroup);

        // Notes
        addSectionLabel(content, R.string.period_notes);
        final EditText notesInput = new EditText(this);
        notesInput.setHint(R.string.period_temperature_notes_hint);
        content.addView(notesInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.period_log_temperature)
                .setView(content)
                .setPositiveButton(R.string.period_save, (d, w) -> {
                    String tempStr = tempInput.getText().toString().trim();
                    double temp;
                    try {
                        temp = Double.parseDouble(tempStr);
                    } catch (NumberFormatException e) {
                        temp = 36.5;
                    }
                    // Clamp to valid range
                    temp = Math.max(35.5, Math.min(38.5, temp));

                    String timeOfDay = null;
                    for (int i = 0; i < timeGroup.getChildCount(); i++) {
                        Chip chip = (Chip) timeGroup.getChildAt(i);
                        if (chip.isChecked()) {
                            timeOfDay = (String) chip.getTag();
                        }
                    }
                    String notes = notesInput.getText().toString().trim();
                    if (notes.isEmpty()) notes = null;

                    mRepo.addTemperature(selectedDay[0], temp, timeOfDay, notes);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    // =================================================================== record editor dialog

    private void showRecordEditor(final PeriodRecord original) {
        final PeriodRecord r = copy(original);
        final boolean isNew = mRepo.getByStart(original.startEpochDay) == null;

        int pad = 20 * mDensity;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, 8 * mDensity, pad, 0);

        // --- Start date ---
        final MaterialButton startBtn = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        startBtn.setText(getString(R.string.period_start_date) + ": "
                + LocalDate.ofEpochDay(r.startEpochDay).format(mDateFmt));
        startBtn.setOnClickListener(v -> {
            LocalDate d = LocalDate.ofEpochDay(r.startEpochDay);
            new DatePickerDialog(this, (view, y, m, day) -> {
                r.startEpochDay = LocalDate.of(y, m + 1, day).toEpochDay();
                startBtn.setText(getString(R.string.period_start_date) + ": "
                        + LocalDate.ofEpochDay(r.startEpochDay).format(mDateFmt));
            }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
        });
        content.addView(startBtn);

        // --- Ongoing checkbox + end date ---
        final MaterialButton endBtn = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        final CheckBox ongoing = new CheckBox(this);
        ongoing.setText(R.string.period_ongoing);
        ongoing.setChecked(r.endEpochDay == null);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = 8 * mDensity;
        content.addView(ongoing, cbLp);

        Runnable updateEnd = () -> endBtn.setText(getString(R.string.period_end_date) + ": "
                + (r.endEpochDay != null
                ? LocalDate.ofEpochDay(r.endEpochDay).format(mDateFmt) : "—"));
        updateEnd.run();
        endBtn.setEnabled(r.endEpochDay != null);
        endBtn.setOnClickListener(v -> {
            LocalDate d = r.endEpochDay != null
                    ? LocalDate.ofEpochDay(r.endEpochDay) : LocalDate.ofEpochDay(r.startEpochDay);
            new DatePickerDialog(this, (view, y, m, day) -> {
                r.endEpochDay = LocalDate.of(y, m + 1, day).toEpochDay();
                updateEnd.run();
            }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
        });
        ongoing.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                r.endEpochDay = null;
            } else if (r.endEpochDay == null) {
                r.endEpochDay = r.startEpochDay;
            }
            endBtn.setEnabled(!isChecked);
            updateEnd.run();
        });
        content.addView(endBtn);

        // --- Flow (single choice) ---
        addSectionLabel(content, R.string.period_flow);
        final ChipGroup flowGroup = new ChipGroup(this);
        flowGroup.setSingleSelection(true);
        final int[] flowResIds = new int[4];
        int[] flowLabels = {R.string.period_flow_none, R.string.period_flow_light,
                R.string.period_flow_medium, R.string.period_flow_heavy};
        for (int i = 0; i < flowLabels.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(flowLabels[i]);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            flowResIds[i] = chip.getId();
            if (r.flow == i) {
                chip.setChecked(true);
            }
            flowGroup.addView(chip);
        }
        content.addView(flowGroup);

        // --- Symptoms (multi) ---
        addSectionLabel(content, R.string.period_symptoms);
        final int[] symptomLabels = {R.string.period_symptom_cramps, R.string.period_symptom_headache,
                R.string.period_symptom_fatigue, R.string.period_symptom_bloating,
                R.string.period_symptom_acne, R.string.period_symptom_backache};
        final ChipGroup symptomGroup = buildMultiChips(symptomLabels, r.symptoms);
        content.addView(symptomGroup);

        // --- Mood (single) ---
        addSectionLabel(content, R.string.period_mood);
        final int[] moodLabels = {R.string.period_mood_happy, R.string.period_mood_calm,
                R.string.period_mood_sad, R.string.period_mood_irritable, R.string.period_mood_anxious};
        final ChipGroup moodGroup = new ChipGroup(this);
        moodGroup.setSingleSelection(true);
        for (int labelRes : moodLabels) {
            Chip chip = new Chip(this);
            chip.setText(labelRes);
            chip.setCheckable(true);
            chip.setTag(getString(labelRes));
            if (getString(labelRes).equals(r.mood)) {
                chip.setChecked(true);
            }
            moodGroup.addView(chip);
        }
        content.addView(moodGroup);

        // --- Notes ---
        addSectionLabel(content, R.string.period_notes);
        final EditText notes = new EditText(this);
        notes.setHint(R.string.period_notes);
        if (r.notes != null) {
            notes.setText(r.notes);
        }
        content.addView(notes);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.period_edit_record)
                .setView(sv)
                .setPositiveButton(R.string.period_save, (d, w) -> {
                    // flow
                    r.flow = 0;
                    for (int i = 0; i < flowResIds.length; i++) {
                        if (flowGroup.getCheckedChipId() == flowResIds[i]) {
                            r.flow = i;
                        }
                    }
                    // symptoms
                    r.symptoms = collectChecked(symptomGroup);
                    // mood
                    r.mood = null;
                    for (int i = 0; i < moodGroup.getChildCount(); i++) {
                        Chip chip = (Chip) moodGroup.getChildAt(i);
                        if (chip.isChecked()) {
                            r.mood = (String) chip.getTag();
                        }
                    }
                    r.notes = notes.getText().toString();
                    // If start day changed, remove the old record first
                    if (!isNew && original.startEpochDay != r.startEpochDay) {
                        mRepo.delete(original.startEpochDay);
                    }
                    mRepo.addOrUpdate(r);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!isNew) {
            b.setNeutralButton(R.string.period_delete, (d, w) -> {
                mRepo.delete(original.startEpochDay);
                refresh();
            });
        }
        b.show();
    }

    // =================================================================== utility helpers

    private ChipGroup buildMultiChips(int[] labelRes, List<String> selected) {
        ChipGroup group = new ChipGroup(this);
        group.setSingleSelection(false);
        for (int res : labelRes) {
            Chip chip = new Chip(this);
            String label = getString(res);
            chip.setText(label);
            chip.setCheckable(true);
            chip.setTag(label);
            if (selected != null && selected.contains(label)) {
                chip.setChecked(true);
            }
            group.addView(chip);
        }
        return group;
    }

    private List<String> collectChecked(ChipGroup group) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip chip = (Chip) group.getChildAt(i);
            if (chip.isChecked()) {
                out.add((String) chip.getTag());
            }
        }
        return out;
    }

    private void addSectionLabel(LinearLayout parent, int res) {
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 16 * mDensity, 0, 4 * mDensity);
        parent.addView(tv);
    }

    private PeriodRecord copy(PeriodRecord src) {
        PeriodRecord r = new PeriodRecord();
        r.id = src.id;
        r.startEpochDay = src.startEpochDay;
        r.endEpochDay = src.endEpochDay;
        r.flow = src.flow;
        r.symptoms = new ArrayList<>(src.symptoms);
        r.mood = src.mood;
        r.notes = src.notes;
        return r;
    }

    private int getColorAttr(int attrRes, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        try {
            getTheme().resolveAttribute(attrRes, tv, true);
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
