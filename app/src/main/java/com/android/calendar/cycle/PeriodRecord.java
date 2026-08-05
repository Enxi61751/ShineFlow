package com.android.calendar.cycle;

import java.util.ArrayList;
import java.util.List;

/**
 * One logged menstrual period. Dates are stored as epoch days
 * ({@link java.time.LocalDate#toEpochDay()}). {@link #endEpochDay} is null while
 * the period is still ongoing.
 */
public class PeriodRecord {
    public long id;
    public long startEpochDay;
    public Long endEpochDay;
    /** 0 = unset, 1 = light, 2 = medium, 3 = heavy */
    public int flow;
    public List<String> symptoms = new ArrayList<>();
    public String mood;
    public String notes;
}
