/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.CalendarContract.Calendars;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewSwitcher;
import android.widget.ViewSwitcher.ViewFactory;

import androidx.fragment.app.Fragment;

import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.calendarcommon2.Time;

import ws.xsoh.etar.R;

/**
 * This is the base class for Day and Week Activities.
 */
public class DayFragment extends Fragment implements CalendarController.EventHandler, ViewFactory {
    protected static final String BUNDLE_KEY_RESTORE_TIME = "key_restore_time";
    /**
     * The view id used for all the views we create. It's OK to have all child
     * views have the same ID. This ID is used to pick which view receives
     * focus when a view hierarchy is saved / restore
     */
    private static final int VIEW_ID = 1;
    protected ProgressBar mProgressBar;
    protected ViewSwitcher mViewSwitcher;
    protected Animation mInAnimationForward;
    protected Animation mOutAnimationForward;
    protected Animation mInAnimationBackward;
    protected Animation mOutAnimationBackward;
    EventLoader mEventLoader;

    Time mSelectedDay = new Time();

    private final Runnable mTZUpdater = new Runnable() {
        @Override
        public void run() {
            if (!DayFragment.this.isAdded()) {
                return;
            }
            String tz = Utils.getTimeZone(getActivity(), mTZUpdater);
            mSelectedDay.setTimezone(tz);
            mSelectedDay.normalize();
        }
    };

    private int mNumDays;
    private View mTransitStationButton;
    private TextView mTransitStationCount;
    private ViewGroup mRootView;
    private View mTransitStationPanel;

    public DayFragment() {
        mSelectedDay.set(System.currentTimeMillis());
    }

    public DayFragment(long timeMillis, int numOfDays) {
        mNumDays = numOfDays;
        if (timeMillis == 0) {
            mSelectedDay.set(System.currentTimeMillis());
        } else {
            mSelectedDay.set(timeMillis);
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Context context = getActivity();

        mInAnimationForward = AnimationUtils.loadAnimation(context, R.anim.slide_left_in);
        mOutAnimationForward = AnimationUtils.loadAnimation(context, R.anim.slide_left_out);
        mInAnimationBackward = AnimationUtils.loadAnimation(context, R.anim.slide_right_in);
        mOutAnimationBackward = AnimationUtils.loadAnimation(context, R.anim.slide_right_out);

        mEventLoader = new EventLoader(context);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.day_activity, null);
        mRootView = (ViewGroup) v;

        mViewSwitcher = (ViewSwitcher) v.findViewById(R.id.switcher);
        mViewSwitcher.setFactory(this);
        // Receive the drag at the fragment root. This remains reliable while
        // the station panel is displayed above the week view.
        mRootView.setOnDragListener((dragTarget, event) -> {
            boolean handled = ((DayView) mViewSwitcher.getCurrentView()).onDragEvent(event);
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_ENDED) {
                if (event.getResult() && mTransitStationPanel != null) {
                    mRootView.removeView(mTransitStationPanel);
                    mTransitStationPanel = null;
                }
                refreshTransitStationCount();
            }
            return handled;
        });
        mViewSwitcher.getCurrentView().requestFocus();
        ((DayView) mViewSwitcher.getCurrentView()).updateTitle();
        mTransitStationButton = v.findViewById(R.id.transit_station_button);
        mTransitStationCount = (TextView) v.findViewById(R.id.transit_station_count);
        mTransitStationButton.setVisibility(mNumDays > 1 ? View.VISIBLE : View.GONE);
        mTransitStationButton.setOnClickListener(view -> showTransitStation());
        refreshTransitStationCount();

        return v;
    }

    private void refreshTransitStationCount() {
        if (mTransitStationCount == null) return;
        int count = new TransitStationRepository(requireContext()).getAll().size();
        mTransitStationCount.setText(String.valueOf(count));
        mTransitStationCount.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private void showTransitStation() {
        if (mTransitStationPanel != null) {
            mRootView.removeView(mTransitStationPanel);
            mTransitStationPanel = null;
            return;
        }
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.WHITE);
        panelBackground.setCornerRadius(24 * getResources().getDisplayMetrics().density);
        content.setBackground(panelBackground);
        content.setClipToOutline(true);
        content.setClickable(true);
        FrameLayout overlay = new FrameLayout(requireContext());
        overlay.setOnClickListener(view -> {
            mRootView.removeView(overlay);
            mTransitStationPanel = null;
        });

        TextView title = new TextView(requireContext());
        title.setText(R.string.transit_station);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF202124);
        content.addView(title);

        TextView subtitle = new TextView(requireContext());
        subtitle.setText(R.string.transit_time_pending);
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF6B7280);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, 4 * padding / 16, 0, padding / 2);
        content.addView(subtitle, subtitleParams);

        ScrollView schedulesScroll = new ScrollView(requireContext());
        schedulesScroll.setFillViewport(true);
        schedulesScroll.setVerticalScrollBarEnabled(true);
        schedulesScroll.setScrollbarFadingEnabled(false);
        LinearLayout schedules = new LinearLayout(requireContext());
        schedules.setOrientation(LinearLayout.VERTICAL);
        schedulesScroll.addView(schedules, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        for (TransitSchedule schedule : new TransitStationRepository(requireContext()).getAll()) {
            TextView item = new TextView(requireContext());
            int cardPadding = 12 * padding / 16;
            item.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
            item.setText(schedule.title);
            item.setTextSize(16);
            item.setTextColor(0xFF202124);
            item.setMaxLines(2);
            GradientDrawable cardBackground = new GradientDrawable();
            cardBackground.setColor(0xFFF1F8F7);
            cardBackground.setCornerRadius(16 * getResources().getDisplayMetrics().density);
            item.setBackground(cardBackground);
            item.setOnLongClickListener(view -> {
                boolean started = view.startDragAndDrop(android.content.ClipData.newPlainText(
                        "transit_schedule", schedule.id),
                        new TransitDragShadow(view, getScheduleColor(schedule)), null, 0);
                if (started) {
                    // Hide the panel immediately so every time cell remains a valid drop target.
                    content.setVisibility(View.GONE);
                    mTransitStationPanel = null;
                    view.post(() -> mRootView.removeView(overlay));
                }
                return started;
            });
            item.setOnClickListener(view -> {
                ((DayView) mViewSwitcher.getCurrentView()).placeTransitScheduleAtSelection(schedule);
                popupDismissAndRefresh(view);
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, 8 * padding / 16);
            schedules.addView(item, cardParams);
        }
        if (schedules.getChildCount() == 0) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.transit_station_empty);
            empty.setTextColor(0xFF6B7280);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, padding * 2, 0, padding * 2);
            schedules.addView(empty, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        content.setElevation(16 * getResources().getDisplayMetrics().density);
        content.addView(schedulesScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                (int) (280 * getResources().getDisplayMetrics().density),
                (int) (300 * getResources().getDisplayMetrics().density),
                android.view.Gravity.CENTER);
        overlay.addView(content, params);
        mRootView.addView(overlay, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mTransitStationPanel = overlay;
    }

    private void popupDismissAndRefresh(View view) {
        refreshTransitStationCount();
    }

    private int getScheduleColor(TransitSchedule schedule) {
        long calendarId = schedule.calendarId;
        Cursor cursor = null;
        try {
            String selection = calendarId >= 0
                    ? Calendars._ID + "=?"
                    : Calendars.VISIBLE + "=1 AND " + Calendars.CALENDAR_ACCESS_LEVEL + ">="
                            + Calendars.CAL_ACCESS_CONTRIBUTOR;
            String[] args = calendarId >= 0 ? new String[] { String.valueOf(calendarId) } : null;
            cursor = requireContext().getContentResolver().query(Calendars.CONTENT_URI,
                    new String[] { Calendars.CALENDAR_COLOR }, selection, args, Calendars._ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                return Utils.getDisplayColorFromColor(requireContext(), cursor.getInt(0));
            }
        } catch (SecurityException ignored) {
            // Use the app's fallback color when calendar access is unavailable.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0xFF1DC1AB;
    }

    private static final class TransitDragShadow extends View.DragShadowBuilder {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int color;

        TransitDragShadow(View view, int color) {
            super(view);
            this.color = color;
        }

        @Override
        public void onProvideShadowMetrics(android.graphics.Point outSize,
                android.graphics.Point outTouchPoint) {
            outSize.set(180, 72);
            outTouchPoint.set(24, 36);
        }

        @Override
        public void onDrawShadow(android.graphics.Canvas canvas) {
            paint.setColor(color);
            canvas.drawRoundRect(0, 0, canvas.getWidth(), canvas.getHeight(), 18, 18, paint);
        }
    }

    public View makeView() {
        mTZUpdater.run();
        DayView view = new DayView(getActivity(), CalendarController
                .getInstance(getActivity()), mViewSwitcher, mEventLoader, mNumDays);
        view.setId(VIEW_ID);
        view.setLayoutParams(new ViewSwitcher.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        view.setSelected(mSelectedDay, false, false);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        mEventLoader.startBackgroundThread();
        mTZUpdater.run();
        eventsChanged();
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        view.handleOnResume();
        view.restartCurrentTimeUpdates();

        view = (DayView) mViewSwitcher.getNextView();
        view.handleOnResume();
        view.restartCurrentTimeUpdates();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        long time = getSelectedTimeInMillis();
        if (time != -1) {
            outState.putLong(BUNDLE_KEY_RESTORE_TIME, time);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        view.cleanup();
        view = (DayView) mViewSwitcher.getNextView();
        view.cleanup();
        mEventLoader.stopBackgroundThread();

        // Stop events cross-fade animation
        view.stopEventsAnimation();
        ((DayView) mViewSwitcher.getNextView()).stopEventsAnimation();
    }

    void startProgressSpinner() {
        // start the progress spinner
        mProgressBar.setVisibility(View.VISIBLE);
    }

    void stopProgressSpinner() {
        // stop the progress spinner
        mProgressBar.setVisibility(View.GONE);
    }

    private void goTo(Time goToTime, boolean ignoreTime, boolean animateToday) {
        if (mViewSwitcher == null) {
            // The view hasn't been set yet. Just save the time and use it later.
            mSelectedDay.set(goToTime);
            return;
        }

        DayView currentView = (DayView) mViewSwitcher.getCurrentView();

        // How does goTo time compared to what's already displaying?
        int diff = currentView.compareToVisibleTimeRange(goToTime);

        if (diff == 0) {
            // In visible range. No need to switch view
            currentView.setSelected(goToTime, ignoreTime, animateToday);
        } else {
            // Figure out which way to animate
            if (diff > 0) {
                mViewSwitcher.setInAnimation(mInAnimationForward);
                mViewSwitcher.setOutAnimation(mOutAnimationForward);
            } else {
                mViewSwitcher.setInAnimation(mInAnimationBackward);
                mViewSwitcher.setOutAnimation(mOutAnimationBackward);
            }

            DayView next = (DayView) mViewSwitcher.getNextView();
            if (ignoreTime) {
                next.setFirstVisibleHour(currentView.getFirstVisibleHour());
            }

            next.setSelected(goToTime, ignoreTime, animateToday);
            next.reloadEvents();
            mViewSwitcher.showNext();
            next.requestFocus();
            next.updateTitle();
            next.restartCurrentTimeUpdates();
        }
    }

    /**
     * Returns the selected time in milliseconds. The milliseconds are measured
     * in UTC milliseconds from the epoch and uniquely specifies any selectable
     * time.
     *
     * @return the selected time in milliseconds
     */
    public long getSelectedTimeInMillis() {
        if (mViewSwitcher == null) {
            return -1;
        }
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        if (view == null) {
            return -1;
        }
        return view.getSelectedTimeInMillis();
    }

    public void eventsChanged() {
        if (mViewSwitcher == null) {
            return;
        }
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        view.clearCachedEvents();
        view.reloadEvents();

        view = (DayView) mViewSwitcher.getNextView();
        view.clearCachedEvents();
    }

    Event getSelectedEvent() {
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        return view.getSelectedEvent();
    }

    boolean isEventSelected() {
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        return view.isEventSelected();
    }

    Event getNewEvent() {
        DayView view = (DayView) mViewSwitcher.getCurrentView();
        return view.getNewEvent();
    }

    public DayView getNextView() {
        return (DayView) mViewSwitcher.getNextView();
    }

    public long getSupportedEventTypes() {
        return EventType.GO_TO | EventType.EVENTS_CHANGED;
    }

    public void handleEvent(EventInfo msg) {
        if (msg.eventType == EventType.GO_TO) {
// TODO support a range of time
// TODO support event_id
// TODO support select message
            goTo(msg.selectedTime, (msg.extraLong & CalendarController.EXTRA_GOTO_DATE) != 0,
                    (msg.extraLong & CalendarController.EXTRA_GOTO_TODAY) != 0);
        } else if (msg.eventType == EventType.EVENTS_CHANGED) {
            eventsChanged();
        }
    }
}
