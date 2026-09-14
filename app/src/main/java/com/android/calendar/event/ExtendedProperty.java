package com.android.calendar.event;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.ExtendedProperties;
import android.provider.CalendarContract.Calendars;

public class ExtendedProperty {
    /**
     * The name to be used with the extended property.
     * @see <a href="https://developer.android.com/reference/kotlin/android/provider/CalendarContract.ExtendedProperties">CalendarContact.ExtendedProperties</a>
     */
    public static final String URL_NAME = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd.ical4android.url";

    public static final String URL_NAME_PRIV = "private:" + URL_NAME;

    /**
     * A short utility identifier for the URL extended field. Works as an equivalent of
     * `CalendarContract.Events.TITLE` for the URL field.
     */
    public static final String URL = "url";

    /**
     * ShineFlow: extended property that marks a special all-day event type.
     * Value is one of {@link #TYPE_VALUE_ANNIVERSARY}, {@link #TYPE_VALUE_BIRTHDAY}
     * or {@link #TYPE_VALUE_COUNTDOWN}.
     */
    public static final String EVENT_TYPE_NAME = "vnd.shineflow.event_type";

    public static final int EVENT_TYPE_NONE = 0;
    public static final int EVENT_TYPE_ANNIVERSARY = 1;
    public static final int EVENT_TYPE_BIRTHDAY = 2;
    public static final int EVENT_TYPE_COUNTDOWN = 3;

    public static final String TYPE_VALUE_ANNIVERSARY = "anniversary";
    public static final String TYPE_VALUE_BIRTHDAY = "birthday";
    public static final String TYPE_VALUE_COUNTDOWN = "countdown";

    public static int typeFromValue(String value) {
        if (value == null) return EVENT_TYPE_NONE;
        switch (value) {
            case TYPE_VALUE_ANNIVERSARY: return EVENT_TYPE_ANNIVERSARY;
            case TYPE_VALUE_BIRTHDAY: return EVENT_TYPE_BIRTHDAY;
            case TYPE_VALUE_COUNTDOWN: return EVENT_TYPE_COUNTDOWN;
            default: return EVENT_TYPE_NONE;
        }
    }

    public static String valueFromType(int type) {
        switch (type) {
            case EVENT_TYPE_ANNIVERSARY: return TYPE_VALUE_ANNIVERSARY;
            case EVENT_TYPE_BIRTHDAY: return TYPE_VALUE_BIRTHDAY;
            case EVENT_TYPE_COUNTDOWN: return TYPE_VALUE_COUNTDOWN;
            default: return null;
        }
    }

    /**
     * ShineFlow: extended property holding the comma-separated list of tag ids
     * assigned to an event.
     */
    public static final String EVENT_TAGS_NAME = "vnd.shineflow.tags";

    public static String encodeTagIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    public static java.util.List<Long> decodeTagIds(String value) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return ids;
        }
        for (String part : value.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(p));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    /**
     * Gets the Content URI for Extended Properties after adding the account name and type, and
     * setting the `CalendarContract.CALLER_IS_SYNCADAPTER` parameter to `true`.
     * @param accountName The name of the account owner of the extended property.
     * @param accountType The type of the account owner of the extended property.
     */
    public static Uri contentUri(String accountName, String accountType) {
        Uri extendedPropUri = ExtendedProperties.CONTENT_URI;
        extendedPropUri = extendedPropUri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
                .build();
        return extendedPropUri;
    }
}
