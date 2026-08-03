package com.android.calendar.event;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Instances;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.calendar.Utils;
import com.android.calendar.settings.GeneralPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SmartScheduleAdvisor {
    private static final long DEFAULT_DURATION_MILLIS = DateUtils.HOUR_IN_MILLIS;
    private static final long STEP_MILLIS = 30L * DateUtils.MINUTE_IN_MILLIS;
    private static final long FUTURE_LOOKAHEAD_MILLIS = 14L * DateUtils.DAY_IN_MILLIS;
    private static final long HISTORY_LOOKBACK_MILLIS = 45L * DateUtils.DAY_IN_MILLIS;
    private static final int DEFAULT_RECOMMENDATION_COUNT = 3;
    private static final int WORKDAY_START_HOUR = 8;
    private static final int WORKDAY_END_HOUR = 21;

    private static final String[] INSTANCE_PROJECTION = new String[]{
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY
    };

    private static final int INDEX_EVENT_ID = 0;
    private static final int INDEX_TITLE = 1;
    private static final int INDEX_LOCATION = 2;
    private static final int INDEX_BEGIN = 3;
    private static final int INDEX_END = 4;
    private static final int INDEX_ALL_DAY = 5;

    AnalysisResult analyze(
            Context context,
            long proposedStartMillis,
            long durationMillis,
            long excludeEventId
    ) {
        long safeDuration = durationMillis > 0 ? durationMillis : DEFAULT_DURATION_MILLIS;
        long now = System.currentTimeMillis();
        long anchorStart = proposedStartMillis > 0 ? proposedStartMillis : roundUpToStep(now);
        long queryStart = Math.max(now - DateUtils.DAY_IN_MILLIS, anchorStart - DateUtils.DAY_IN_MILLIS);
        long queryEnd = anchorStart + FUTURE_LOOKAHEAD_MILLIS;

        List<BusyBlock> busyBlocks = queryBusyBlocks(context, queryStart, queryEnd, excludeEventId);
        List<ConflictInfo> conflicts = proposedStartMillis > 0
                ? findConflicts(busyBlocks, proposedStartMillis, proposedStartMillis + safeDuration)
                : Collections.emptyList();
        Map<Integer, Integer> preferredHourScores = loadPreferredHourScores(
                context,
                now - HISTORY_LOOKBACK_MILLIS,
                now,
                excludeEventId
        );
        List<TimeSuggestion> suggestions = recommendTimeSlots(
                busyBlocks,
                preferredHourScores,
                Math.max(anchorStart, roundUpToStep(now)),
                safeDuration,
                proposedStartMillis
        );

        return new AnalysisResult(conflicts, suggestions);
    }

    private List<ConflictInfo> findConflicts(List<BusyBlock> busyBlocks, long startMillis, long endMillis) {
        List<ConflictInfo> conflicts = new ArrayList<>();
        for (BusyBlock block : busyBlocks) {
            if (overlaps(block.startMillis, block.endMillis, startMillis, endMillis)) {
                conflicts.add(new ConflictInfo(
                        block.eventId,
                        block.title,
                        block.location,
                        block.startMillis,
                        block.endMillis,
                        block.allDay
                ));
            }
        }
        conflicts.sort(Comparator.comparingLong(info -> info.startMillis));
        return conflicts;
    }

    private List<TimeSuggestion> recommendTimeSlots(
            List<BusyBlock> busyBlocks,
            Map<Integer, Integer> preferredHourScores,
            long anchorMillis,
            long durationMillis,
            long proposedStartMillis
    ) {
        List<TimeSuggestion> ranked = new ArrayList<>();
        Set<Long> seenStarts = new LinkedHashSet<>();
        Calendar calendar = Calendar.getInstance();
        long searchEnd = anchorMillis + FUTURE_LOOKAHEAD_MILLIS;

        for (long candidateStart = roundUpToStep(anchorMillis);
             candidateStart < searchEnd && ranked.size() < 24;
             candidateStart += STEP_MILLIS) {
            long candidateEnd = candidateStart + durationMillis;
            if (!isWithinWorkingWindow(calendar, candidateStart, candidateEnd)) {
                continue;
            }
            if (hasConflict(busyBlocks, candidateStart, candidateEnd)) {
                continue;
            }
            if (!seenStarts.add(candidateStart)) {
                continue;
            }

            int score = scoreCandidate(
                    calendar,
                    preferredHourScores,
                    candidateStart,
                    proposedStartMillis,
                    anchorMillis
            );
            String reason = buildReason(calendar, preferredHourScores, candidateStart, proposedStartMillis);
            ranked.add(new TimeSuggestion(candidateStart, candidateEnd, reason, score));
        }

        ranked.sort((left, right) -> {
            int scoreCompare = Integer.compare(right.score, left.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return Long.compare(left.startMillis, right.startMillis);
        });

        if (ranked.size() > DEFAULT_RECOMMENDATION_COUNT) {
            return new ArrayList<>(ranked.subList(0, DEFAULT_RECOMMENDATION_COUNT));
        }
        return ranked;
    }

    private int scoreCandidate(
            Calendar calendar,
            Map<Integer, Integer> preferredHourScores,
            long candidateStartMillis,
            long proposedStartMillis,
            long anchorMillis
    ) {
        calendar.setTimeInMillis(candidateStartMillis);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int score = preferredHourScores.getOrDefault(hour, 0) * 10;

        if (proposedStartMillis > 0) {
            long distanceSteps = Math.abs(candidateStartMillis - proposedStartMillis) / STEP_MILLIS;
            score += Math.max(0, 24 - (int) distanceSteps);
            if (isSameDay(candidateStartMillis, proposedStartMillis)) {
                score += 12;
            }
        } else {
            long dayOffset = Math.max(0L, (candidateStartMillis - anchorMillis) / DateUtils.DAY_IN_MILLIS);
            score += Math.max(0, 10 - (int) dayOffset);
        }

        if (hour >= 9 && hour <= 18) {
            score += 4;
        }
        return score;
    }

    private String buildReason(
            Calendar calendar,
            Map<Integer, Integer> preferredHourScores,
            long candidateStartMillis,
            long proposedStartMillis
    ) {
        calendar.setTimeInMillis(candidateStartMillis);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (preferredHourScores.containsKey(hour)) {
            return "Matches recent usage";
        }
        if (proposedStartMillis > 0 && isSameDay(candidateStartMillis, proposedStartMillis)) {
            return "Same day, no conflict";
        }
        return "Nearest free slot";
    }

    private boolean isWithinWorkingWindow(Calendar calendar, long startMillis, long endMillis) {
        calendar.setTimeInMillis(startMillis);
        int startHour = calendar.get(Calendar.HOUR_OF_DAY);
        int startMinute = calendar.get(Calendar.MINUTE);
        if (startHour < WORKDAY_START_HOUR) {
            return false;
        }
        if (startHour > WORKDAY_END_HOUR || (startHour == WORKDAY_END_HOUR && startMinute > 0)) {
            return false;
        }

        Calendar endCalendar = (Calendar) calendar.clone();
        endCalendar.setTimeInMillis(endMillis);
        if (!isSameDay(startMillis, endMillis - 1)) {
            return false;
        }
        int endHour = endCalendar.get(Calendar.HOUR_OF_DAY);
        int endMinute = endCalendar.get(Calendar.MINUTE);
        return endHour < WORKDAY_END_HOUR || (endHour == WORKDAY_END_HOUR && endMinute == 0);
    }

    private Map<Integer, Integer> loadPreferredHourScores(
            Context context,
            long rangeStartMillis,
            long rangeEndMillis,
            long excludeEventId
    ) {
        Map<Integer, Integer> hourCounts = new HashMap<>();
        List<BusyBlock> history = queryBusyBlocks(context, rangeStartMillis, rangeEndMillis, excludeEventId);
        Calendar calendar = Calendar.getInstance();
        for (BusyBlock block : history) {
            if (block.allDay) {
                continue;
            }
            calendar.setTimeInMillis(block.startMillis);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            hourCounts.put(hour, hourCounts.getOrDefault(hour, 0) + 1);
        }

        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(hourCounts.entrySet());
        sorted.sort((left, right) -> {
            int countCompare = Integer.compare(right.getValue(), left.getValue());
            if (countCompare != 0) {
                return countCompare;
            }
            return Integer.compare(left.getKey(), right.getKey());
        });

        Map<Integer, Integer> ranked = new HashMap<>();
        int score = sorted.size();
        for (Map.Entry<Integer, Integer> entry : sorted) {
            ranked.put(entry.getKey(), score--);
        }
        return ranked;
    }

    private List<BusyBlock> queryBusyBlocks(
            Context context,
            long rangeStartMillis,
            long rangeEndMillis,
            long excludeEventId
    ) {
        List<BusyBlock> blocks = new ArrayList<>();
        if (rangeEndMillis <= rangeStartMillis) {
            return blocks;
        }

        Uri.Builder builder = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, rangeStartMillis);
        ContentUris.appendId(builder, rangeEndMillis);

        SharedPreferences preferences = GeneralPreferences.Companion.getSharedPreferences(context);
        boolean hideDeclined = preferences.getBoolean(GeneralPreferences.KEY_HIDE_DECLINED, false);

        StringBuilder selection = new StringBuilder();
        selection.append(Calendars.VISIBLE).append("=?")
                .append(" AND ").append(Instances.BEGIN).append("<?")
                .append(" AND ").append(Instances.END).append(">?");
        List<String> args = new ArrayList<>();
        args.add("1");
        args.add(String.valueOf(rangeEndMillis));
        args.add(String.valueOf(rangeStartMillis));

        if (hideDeclined) {
            selection.append(" AND ").append(Instances.SELF_ATTENDEE_STATUS)
                    .append("!=").append(Attendees.ATTENDEE_STATUS_DECLINED);
        }
        if (excludeEventId > 0) {
            selection.append(" AND ").append(Instances.EVENT_ID).append("!=?");
            args.add(String.valueOf(excludeEventId));
        }

        ContentResolver resolver = context.getContentResolver();
        try (android.database.Cursor cursor = resolver.query(
                builder.build(),
                INSTANCE_PROJECTION,
                selection.toString(),
                args.toArray(new String[0]),
                Instances.BEGIN + " ASC"
        )) {
            if (cursor == null) {
                return blocks;
            }

            while (cursor.moveToNext()) {
                blocks.add(new BusyBlock(
                        cursor.getLong(INDEX_EVENT_ID),
                        cursor.getString(INDEX_TITLE),
                        cursor.getString(INDEX_LOCATION),
                        cursor.getLong(INDEX_BEGIN),
                        cursor.getLong(INDEX_END),
                        cursor.getInt(INDEX_ALL_DAY) != 0
                ));
            }
        }
        return blocks;
    }

    private boolean hasConflict(List<BusyBlock> busyBlocks, long startMillis, long endMillis) {
        for (BusyBlock block : busyBlocks) {
            if (overlaps(block.startMillis, block.endMillis, startMillis, endMillis)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(long leftStart, long leftEnd, long rightStart, long rightEnd) {
        return leftStart < rightEnd && leftEnd > rightStart;
    }

    private long roundUpToStep(long millis) {
        long remainder = millis % STEP_MILLIS;
        if (remainder == 0) {
            return millis;
        }
        return millis + (STEP_MILLIS - remainder);
    }

    private long roundDownToDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private boolean isSameDay(long leftMillis, long rightMillis) {
        Calendar left = Calendar.getInstance();
        left.setTimeInMillis(leftMillis);
        Calendar right = Calendar.getInstance();
        right.setTimeInMillis(rightMillis);
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }

    static final class AnalysisResult {
        final List<ConflictInfo> conflicts;
        final List<TimeSuggestion> suggestions;

        AnalysisResult(List<ConflictInfo> conflicts, List<TimeSuggestion> suggestions) {
            this.conflicts = conflicts;
            this.suggestions = suggestions;
        }
    }

    static final class ConflictInfo {
        final long eventId;
        final String title;
        final String location;
        final long startMillis;
        final long endMillis;
        final boolean allDay;

        ConflictInfo(
                long eventId,
                String title,
                String location,
                long startMillis,
                long endMillis,
                boolean allDay
        ) {
            this.eventId = eventId;
            this.title = title;
            this.location = location;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.allDay = allDay;
        }

        String toDisplayText(Context context) {
            int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME;
            if (allDay) {
                flags = DateUtils.FORMAT_SHOW_DATE;
            }
            String when = Utils.formatDateRange(context, startMillis, endMillis, flags);
            String cleanTitle = TextUtils.isEmpty(title) ? "Untitled event" : title;
            if (TextUtils.isEmpty(location)) {
                return cleanTitle + " (" + when + ")";
            }
            return cleanTitle + " (" + when + ", " + location + ")";
        }
    }

    static final class TimeSuggestion {
        final long startMillis;
        final long endMillis;
        final String reason;
        final int score;

        TimeSuggestion(long startMillis, long endMillis, String reason, int score) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.reason = reason;
            this.score = score;
        }

        String toDisplayText(Context context) {
            int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME;
            String when = Utils.formatDateRange(context, startMillis, endMillis, flags);
            return String.format(Locale.getDefault(), "%s  %s", when, reason);
        }
    }

    private static final class BusyBlock {
        final long eventId;
        final String title;
        final String location;
        final long startMillis;
        final long endMillis;
        final boolean allDay;

        BusyBlock(
                long eventId,
                String title,
                String location,
                long startMillis,
                long endMillis,
                boolean allDay
        ) {
            this.eventId = eventId;
            this.title = title;
            this.location = location;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.allDay = allDay;
        }
    }
}
