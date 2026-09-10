package com.android.calendar.tags;

import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract.ExtendedProperties;

import com.android.calendar.event.ExtendedProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the current "filter events by tag" selection and a cached map of
 * event id -> assigned tag ids (loaded from the calendar extended properties).
 * When the selection is empty, no filtering is applied.
 * <p>
 * A special sentinel id {@link #TAG_ID_UNCATEGORIZED} represents the
 * "Uncategorized" pseudo-tag that matches events with no assigned tags.
 */
public class TagFilter {

    public static final long TAG_ID_UNCATEGORIZED = Long.MIN_VALUE;

    private static final TagFilter INSTANCE = new TagFilter();

    private final Set<Long> mSelected = new HashSet<>();
    private volatile Map<Long, Set<Long>> mEventTags = new HashMap<>();

    private TagFilter() {
    }

    public static TagFilter get() {
        return INSTANCE;
    }

    public synchronized Set<Long> getSelected() {
        return new HashSet<>(mSelected);
    }

    public synchronized void setSelected(List<Long> ids) {
        mSelected.clear();
        if (ids != null) {
            mSelected.addAll(ids);
        }
    }

    public synchronized boolean isActive() {
        return !mSelected.isEmpty();
    }

    public synchronized boolean isUncategorizedSelected() {
        return mSelected.contains(TAG_ID_UNCATEGORIZED);
    }

    /**
     * Loads the event id -> tag ids map from the calendar provider. Runs a
     * single query; call from a background thread.
     */
    public void refresh(Context context) {
        Map<Long, Set<Long>> map = new HashMap<>();
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    ExtendedProperties.CONTENT_URI,
                    new String[] { ExtendedProperties.EVENT_ID, ExtendedProperties.VALUE },
                    ExtendedProperties.NAME + "=?",
                    new String[] { ExtendedProperty.EVENT_TAGS_NAME },
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    long eventId = c.getLong(0);
                    List<Long> ids = ExtendedProperty.decodeTagIds(c.getString(1));
                    if (!ids.isEmpty()) {
                        map.put(eventId, new HashSet<>(ids));
                    }
                }
            }
        } catch (Exception ignored) {
            // Missing permission or provider quirk: fall back to no mapping.
        } finally {
            if (c != null) {
                c.close();
            }
        }
        mEventTags = map;
    }

    /**
     * @return true if the event should be shown under the current filter.
     */
    public boolean matches(long eventId) {
        Set<Long> selected;
        boolean uncategorizedSelected;
        synchronized (this) {
            if (mSelected.isEmpty()) {
                return true;
            }
            selected = new HashSet<>(mSelected);
            uncategorizedSelected = selected.contains(TAG_ID_UNCATEGORIZED);
        }
        Set<Long> tags = mEventTags.get(eventId);
        if (tags == null || tags.isEmpty()) {
            // Event has no tags: shown only if "uncategorized" is selected
            return uncategorizedSelected;
        }
        for (Long t : tags) {
            if (selected.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
