package com.android.calendar.tags;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.calendar.event.ExtendedProperty;
import com.android.calendar.theme.DynamicThemeKt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ws.xsoh.etar.R;

/**
 * Displays events filtered by one or more selected tags, grouped by tag
 * and ordered by start time (nearest first). Each tag group shows a
 * colored header, followed by its events, separated by horizontal lines.
 */
public class TagFilteredEventsActivity extends AppCompatActivity {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_EVENT = 1;
    private static final int VIEW_TYPE_DIVIDER = 2;

    private static final int QUERY_DAYS = 365;

    private RecyclerView mRecyclerView;
    private TextView mEmptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicThemeKt.applyTheme(this);
        setContentView(R.layout.tag_filtered_events);

        mRecyclerView = findViewById(R.id.filtered_events_list);
        mEmptyView = findViewById(R.id.empty_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.tags_filter_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        long[] tagIds = getIntent().getLongArrayExtra("tag_ids");
        if (tagIds == null || tagIds.length == 0) {
            mEmptyView.setVisibility(View.VISIBLE);
            return;
        }

        loadEvents(tagIds);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadEvents(final long[] tagIds) {
        new Thread(() -> {
            Map<Long, Set<Long>> eventTagMap = loadEventTagMap();
            Map<Long, EventInfo> allEvents = loadAllEvents();

            Set<Long> selectedSet = new HashSet<>();
            for (long id : tagIds) selectedSet.add(id);
            boolean includeUncategorized = selectedSet.contains(TagFilter.TAG_ID_UNCATEGORIZED);

            // Group events by tag
            Map<Long, List<EventInfo>> grouped = new HashMap<>();
            List<EventInfo> uncategorized = new ArrayList<>();

            for (Map.Entry<Long, Set<Long>> entry : eventTagMap.entrySet()) {
                EventInfo event = allEvents.get(entry.getKey());
                if (event == null) continue;
                Set<Long> eventTags = entry.getValue();
                boolean matched = false;
                for (Long tid : eventTags) {
                    if (selectedSet.contains(tid)) {
                        grouped.computeIfAbsent(tid, k -> new ArrayList<>()).add(event);
                        matched = true;
                    }
                }
                if (!matched && includeUncategorized && eventTags.isEmpty()) {
                    uncategorized.add(event);
                }
            }

            // Also include untagged events not in eventTagMap
            if (includeUncategorized) {
                for (Map.Entry<Long, EventInfo> entry : allEvents.entrySet()) {
                    if (!eventTagMap.containsKey(entry.getKey())) {
                        uncategorized.add(entry.getValue());
                    }
                }
            }

            // Build display items in tag order
            TagRepository repo = TagRepository.get(this);
            List<Tag> orderedTags = repo.getAllWithUncategorized(this);
            List<DisplayItem> items = new ArrayList<>();

            for (Tag tag : orderedTags) {
                List<EventInfo> events;
                if (tag.id == TagFilter.TAG_ID_UNCATEGORIZED) {
                    events = uncategorized;
                } else {
                    events = grouped.get(tag.id);
                }
                if (events == null || events.isEmpty()) continue;

                Collections.sort(events, Comparator.comparingLong(e -> e.startMillis));

                items.add(new DisplayItem(VIEW_TYPE_HEADER, tag, null));
                for (EventInfo ev : events) {
                    items.add(new DisplayItem(VIEW_TYPE_EVENT, tag, ev));
                }
                // Add divider after group (will be filtered for last group in adapter)
                items.add(new DisplayItem(VIEW_TYPE_DIVIDER, tag, null));
            }

            // Remove trailing divider
            if (!items.isEmpty() && items.get(items.size() - 1).type == VIEW_TYPE_DIVIDER) {
                items.remove(items.size() - 1);
            }

            runOnUiThread(() -> {
                if (items.isEmpty()) {
                    mEmptyView.setVisibility(View.VISIBLE);
                } else {
                    mRecyclerView.setAdapter(new FilteredEventsAdapter(items));
                }
            });
        }).start();
    }

    private Map<Long, Set<Long>> loadEventTagMap() {
        Map<Long, Set<Long>> map = new HashMap<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    CalendarContract.ExtendedProperties.CONTENT_URI,
                    new String[]{CalendarContract.ExtendedProperties.EVENT_ID,
                            CalendarContract.ExtendedProperties.VALUE},
                    CalendarContract.ExtendedProperties.NAME + "=?",
                    new String[]{ExtendedProperty.EVENT_TAGS_NAME},
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
        } finally {
            if (c != null) c.close();
        }
        return map;
    }

    private Map<Long, EventInfo> loadAllEvents() {
        Map<Long, EventInfo> map = new HashMap<>();
        Cursor c = null;
        try {
            com.android.calendar.calendarcommon2.Time time = new com.android.calendar.calendarcommon2.Time();
            long now = System.currentTimeMillis();
            time.set(now);
            int startDay = com.android.calendar.calendarcommon2.Time.getJulianDay(now, time.getGmtOffset());
            int endDay = com.android.calendar.calendarcommon2.Time.getJulianDay(
                    now + QUERY_DAYS * DateUtils.DAY_IN_MILLIS, time.getGmtOffset());

            Uri.Builder builder = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon();
            ContentUris.appendId(builder, startDay);
            ContentUris.appendId(builder, endDay);

            c = getContentResolver().query(
                    builder.build(),
                    new String[]{
                            CalendarContract.Instances.EVENT_ID,
                            CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.END,
                            CalendarContract.Instances.ALL_DAY,
                            CalendarContract.Instances.EVENT_LOCATION,
                    },
                    CalendarContract.Instances.VISIBLE + "=?",
                    new String[]{"1"},
                    CalendarContract.Instances.BEGIN + " ASC");

            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String title = c.getString(1);
                    long start = c.getLong(2);
                    long end = c.getLong(3);
                    boolean allDay = c.getInt(4) != 0;
                    String location = c.getString(5);
                    map.put(id, new EventInfo(id, title, start, end, allDay, location));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return map;
    }

    // --- Display model ---

    static class EventInfo {
        final long id;
        final String title;
        final long startMillis;
        final long endMillis;
        final boolean allDay;
        final String location;

        EventInfo(long id, String title, long start, long end, boolean allDay, String location) {
            this.id = id;
            this.title = title != null ? title : "";
            this.startMillis = start;
            this.endMillis = end;
            this.allDay = allDay;
            this.location = location;
        }
    }

    static class DisplayItem {
        final int type;
        final Tag tag;
        final EventInfo event;

        DisplayItem(int type, Tag tag, EventInfo event) {
            this.type = type;
            this.tag = tag;
            this.event = event;
        }
    }

    // --- Adapter ---

    class FilteredEventsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<DisplayItem> mItems;

        FilteredEventsAdapter(List<DisplayItem> items) {
            mItems = items;
        }

        @Override
        public int getItemViewType(int position) {
            return mItems.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setPadding(32, 24, 32, 8);
                tv.setTextSize(20);
                tv.setTypeface(null, Typeface.BOLD);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tv.setLayoutParams(lp);
                return new HeaderHolder(tv);
            } else if (viewType == VIEW_TYPE_DIVIDER) {
                View v = new View(parent.getContext());
                v.setBackgroundColor(0xFFE0E0E0);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.leftMargin = 32;
                lp.rightMargin = 32;
                v.setLayoutParams(lp);
                return new DividerHolder(v);
            } else {
                View itemView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
                return new EventHolder(itemView);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DisplayItem item = mItems.get(position);
            if (holder instanceof HeaderHolder) {
                HeaderHolder h = (HeaderHolder) holder;
                h.textView.setText(item.tag.name);
                h.textView.setTextColor(item.tag.color);
            } else if (holder instanceof EventHolder) {
                EventHolder h = (EventHolder) holder;
                EventInfo ev = item.event;
                java.text.DateFormat df;
                if (ev.allDay) {
                    df = DateFormat.getDateFormat(TagFilteredEventsActivity.this);
                } else {
                    df = DateFormat.getTimeFormat(TagFilteredEventsActivity.this);
                }
                String timeStr = df.format(new java.util.Date(ev.startMillis));
                h.titleView.setText(ev.title.isEmpty() ? getString(R.string.no_title_label) : ev.title);
                String detail = timeStr;
                if (ev.location != null && !ev.location.isEmpty()) {
                    detail = timeStr + "  " + ev.location;
                }
                h.detailView.setText(detail);
                h.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(ContentUris.withAppendedId(
                            CalendarContract.Events.CONTENT_URI, ev.id));
                    intent.putExtra("beginTime", ev.startMillis);
                    intent.putExtra("endTime", ev.endMillis);
                    intent.setClass(TagFilteredEventsActivity.this,
                            com.android.calendar.EventInfoActivity.class);
                    startActivity(intent);
                });
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        HeaderHolder(TextView textView) {
            super(textView);
            this.textView = textView;
        }
    }

    static class EventHolder extends RecyclerView.ViewHolder {
        final TextView titleView;
        final TextView detailView;

        EventHolder(View itemView) {
            super(itemView);
            titleView = itemView.findViewById(android.R.id.text1);
            detailView = itemView.findViewById(android.R.id.text2);
        }
    }

    static class DividerHolder extends RecyclerView.ViewHolder {
        DividerHolder(View itemView) {
            super(itemView);
        }
    }
}
