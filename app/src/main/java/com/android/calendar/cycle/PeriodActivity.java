package com.android.calendar.cycle;

import android.app.DatePickerDialog;
import android.graphics.Color;
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

import com.android.calendar.theme.DynamicThemeKt;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

import ws.xsoh.etar.R;

/**
 * Dedicated cycle-tracking screen: overview, quick logging, history and a
 * per-record editor (flow / symptoms / mood / notes). All data is local.
 */
public class PeriodActivity extends AppCompatActivity {

    private PeriodRepository mRepo;
    private int mDensity;

    private LinearLayout mSummaryBox;
    private MaterialButton mPrimaryAction;
    private LinearLayout mHistoryBox;

    private final DateTimeFormatter mDateFmt =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicThemeKt.applyTheme(this);
        mRepo = PeriodRepository.get(this);
        mDensity = (int) getResources().getDisplayMetrics().density;

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.period_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 16 * mDensity;
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        mSummaryBox = new LinearLayout(this);
        mSummaryBox.setOrientation(LinearLayout.VERTICAL);
        mSummaryBox.setPadding(pad, pad, pad, pad);
        root.addView(mSummaryBox);

        mPrimaryAction = new MaterialButton(this);
        mPrimaryAction.setOnClickListener(v -> onPrimaryAction());
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = 12 * mDensity;
        root.addView(mPrimaryAction, pLp);

        MaterialButton markStart = new MaterialButton(this,
                null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        markStart.setText(R.string.period_mark_start);
        markStart.setOnClickListener(v -> pickStartDate());
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = 8 * mDensity;
        root.addView(markStart, mLp);

        TextView historyHeader = new TextView(this);
        historyHeader.setText(R.string.period_history);
        historyHeader.setTypeface(Typeface.DEFAULT_BOLD);
        historyHeader.setPadding(pad, 24 * mDensity, pad, 8 * mDensity);
        root.addView(historyHeader);

        mHistoryBox = new LinearLayout(this);
        mHistoryBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(mHistoryBox);

        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------- rendering

    private void refresh() {
        renderSummary();
        renderHistory();
        PeriodReminderReceiver.schedule(this);
    }

    private void renderSummary() {
        mSummaryBox.removeAllViews();
        long today = PeriodRepository.todayEpochDay();

        if (!mRepo.hasData()) {
            addSummaryLine(mRepo == null ? "" : getString(R.string.period_no_data), 16, false);
            mPrimaryAction.setText(R.string.period_log_start_today);
            return;
        }

        PeriodRecord last = mRepo.lastRecord();
        boolean ongoing = last.endEpochDay == null;
        int status = mRepo.statusFor(today);

        String bigLine;
        if (status == PeriodRepository.STATUS_PERIOD && ongoing) {
            bigLine = getString(R.string.period_in_period,
                    (int) (today - last.startEpochDay + 1));
        } else {
            bigLine = getString(R.string.period_cycle_day,
                    (int) (today - last.startEpochDay + 1));
        }
        addSummaryLine(bigLine, 22, true);

        long next = mRepo.predictedNextStart();
        long daysLeft = next - today;
        String nextLine;
        if (daysLeft > 0) {
            nextLine = getString(R.string.period_next_in, (int) daysLeft);
        } else if (daysLeft == 0) {
            nextLine = getString(R.string.period_next_today);
        } else {
            nextLine = getString(R.string.period_next_overdue, (int) -daysLeft);
        }
        addSummaryLine(nextLine, 16, false);

        if (status == PeriodRepository.STATUS_OVULATION) {
            addSummaryLine(getString(R.string.period_ovulation), 15, false);
        } else if (status == PeriodRepository.STATUS_FERTILE) {
            addSummaryLine(getString(R.string.period_fertile), 15, false);
        }

        addSummaryLine(getString(R.string.period_avg_cycle, mRepo.averageCycleLength()), 14, false);
        addSummaryLine(getString(R.string.period_avg_length, mRepo.averagePeriodLength()), 14, false);

        mPrimaryAction.setText(ongoing
                ? R.string.period_log_end_today
                : R.string.period_log_start_today);
    }

    private void addSummaryLine(String text, int sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary));
        }
        tv.setPadding(0, 2 * mDensity, 0, 2 * mDensity);
        mSummaryBox.addView(tv);
    }

    private void renderHistory() {
        mHistoryBox.removeAllViews();
        List<PeriodRecord> all = mRepo.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            final PeriodRecord r = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12 * mDensity, 0, 12 * mDensity);
            row.setClickable(true);
            row.setOnClickListener(v -> showRecordEditor(r));

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable c =
                    new android.graphics.drawable.GradientDrawable();
            c.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            c.setColor(0xFFFF4F9A);
            dot.setBackground(c);
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(14 * mDensity, 14 * mDensity);
            dotLp.rightMargin = 12 * mDensity;
            row.addView(dot, dotLp);

            TextView tv = new TextView(this);
            String start = LocalDate.ofEpochDay(r.startEpochDay).format(mDateFmt);
            String range = r.endEpochDay != null
                    ? start + " – " + LocalDate.ofEpochDay(r.endEpochDay).format(mDateFmt)
                    : start + " (" + getString(R.string.period_ongoing) + ")";
            tv.setText(range);
            tv.setTextSize(15);
            row.addView(tv);

            mHistoryBox.addView(row);
        }
    }

    // ------------------------------------------------------------- actions

    private void onPrimaryAction() {
        long today = PeriodRepository.todayEpochDay();
        PeriodRecord last = mRepo.lastRecord();
        if (last != null && last.endEpochDay == null && last.startEpochDay <= today) {
            // Close the ongoing period.
            last.endEpochDay = today;
            mRepo.addOrUpdate(last);
        } else {
            PeriodRecord r = new PeriodRecord();
            r.startEpochDay = today;
            mRepo.addOrUpdate(r);
        }
        refresh();
    }

    private void pickStartDate() {
        LocalDate now = LocalDate.now();
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            PeriodRecord r = new PeriodRecord();
            r.startEpochDay = LocalDate.of(year, month + 1, day).toEpochDay();
            showRecordEditor(r);
        }, now.getYear(), now.getMonthValue() - 1, now.getDayOfMonth());
        dialog.show();
    }

    // ------------------------------------------------------------- editor

    private void showRecordEditor(final PeriodRecord original) {
        final PeriodRecord r = copy(original);
        final boolean isNew = mRepo.getByStart(original.startEpochDay) == null;

        int pad = 20 * mDensity;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, 8 * mDensity, pad, 0);

        // Start date
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

        // Ongoing checkbox + end date
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

        // Flow (single choice)
        addSectionLabel(content, R.string.period_flow);
        final ChipGroup flowGroup = new ChipGroup(this);
        flowGroup.setSingleSelection(true);
        final int[] flowIds = new int[4];
        int[] flowLabels = {R.string.period_flow_none, R.string.period_flow_light,
                R.string.period_flow_medium, R.string.period_flow_heavy};
        for (int i = 0; i < flowLabels.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(flowLabels[i]);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            flowIds[i] = chip.getId();
            if (r.flow == i) {
                chip.setChecked(true);
            }
            flowGroup.addView(chip);
        }
        content.addView(flowGroup);

        // Symptoms (multi)
        addSectionLabel(content, R.string.period_symptoms);
        final int[] symptomLabels = {R.string.period_symptom_cramps, R.string.period_symptom_headache,
                R.string.period_symptom_fatigue, R.string.period_symptom_bloating,
                R.string.period_symptom_acne, R.string.period_symptom_backache};
        final ChipGroup symptomGroup = buildMultiChips(symptomLabels, r.symptoms);
        content.addView(symptomGroup);

        // Mood (single)
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

        // Notes
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
                    for (int i = 0; i < flowIds.length; i++) {
                        if (flowGroup.getCheckedChipId() == flowIds[i]) {
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
                    // If start day changed, remove the old record first.
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
        r.startEpochDay = src.startEpochDay;
        r.endEpochDay = src.endEpochDay;
        r.flow = src.flow;
        r.symptoms = new ArrayList<>(src.symptoms);
        r.mood = src.mood;
        r.notes = src.notes;
        return r;
    }

    private int getThemeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data != 0 ? tv.data : Color.parseColor("#FF4F9A");
    }
}
