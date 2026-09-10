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

package com.android.calendar.event;

import static com.android.calendar.event.EditEventHelper.EXTENDED_INDEX_NAME;
import static com.android.calendar.event.EditEventHelper.EXTENDED_INDEX_VALUE;

import android.Manifest;
import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Colors;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.android.calendar.AsyncQueryService;
import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventHandler;
import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarEventModel;
import com.android.calendar.CalendarEventModel.Attendee;
import com.android.calendar.CalendarEventModel.ReminderEntry;
import com.android.calendar.DeleteEventHelper;
import com.android.calendar.Utils;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.colorpicker.ColorPickerSwatch.OnColorSelectedListener;
import com.android.calendar.colorpicker.HsvColorComparator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.Serializable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ws.xsoh.etar.BuildConfig;
import ws.xsoh.etar.R;

public class EditEventFragment extends Fragment implements EventHandler, OnColorSelectedListener {
    private static final String TAG = "EditEventActivity";
    private static final String COLOR_PICKER_DIALOG_TAG = "ColorPickerDialog";

    private static final int REQUEST_CODE_COLOR_PICKER = 0;
    private static final int REQUEST_CODE_PICK_IMAGE = 1001;
    private static final int REQUEST_CODE_PICK_AUDIO = 1002;
    private static final int REQUEST_CODE_RECORD_AUDIO_PERMISSION = 2001; // 录音权限请求码
    private static final String SMART_INPUT_FOLDER = "smart_input_data";

    private android.media.MediaRecorder mRecorder = null;
    private String mCurrentRecordPath = null;

    private File mSmartTextFile = null;
    private File mSmartImageFile = null;
    private File mSmartAudioFile = null;
    private String mSmartTextContent = "";

    private final SmartScheduleAdvisor mSmartScheduleAdvisor = new SmartScheduleAdvisor();

    private final OkHttpClient mSmartInputClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private static final String BUNDLE_KEY_MODEL = "key_model";
    private static final String BUNDLE_KEY_EDIT_STATE = "key_edit_state";
    private static final String BUNDLE_KEY_EVENT = "key_event";
    private static final String BUNDLE_KEY_READ_ONLY = "key_read_only";
    private static final String BUNDLE_KEY_EDIT_ON_LAUNCH = "key_edit_on_launch";
    private static final String BUNDLE_KEY_SHOW_COLOR_PALETTE = "show_color_palette";

    private static final String BUNDLE_KEY_DATE_BUTTON_CLICKED = "date_button_clicked";

    private static final boolean DEBUG = false;

    private static final int TOKEN_EVENT = 1;
    private static final int TOKEN_ATTENDEES = 1 << 1;
    private static final int TOKEN_REMINDERS = 1 << 2;
    private static final int TOKEN_CALENDARS = 1 << 3;
    private static final int TOKEN_COLORS = 1 << 4;
    private static final int TOKEN_EXTENDED = 1 << 5;

    private static final int TOKEN_ALL = TOKEN_EVENT | TOKEN_ATTENDEES | TOKEN_REMINDERS
            | TOKEN_CALENDARS | TOKEN_COLORS | TOKEN_EXTENDED;
    private static final int TOKEN_UNITIALIZED = 1 << 31;
    private final EventInfo mEvent;
    private final Done mOnDone = new Done();
    private final Intent mIntent;
    public boolean mShowModifyDialogOnLaunch = false;
    EditEventHelper mHelper;
    CalendarEventModel mModel;
    CalendarEventModel mOriginalModel;
    CalendarEventModel mRestoreModel;
    EditEventView mView;
    QueryHandler mHandler;
    int mModification = Utils.MODIFY_UNINITIALIZED;
    /**
     * A bitfield of TOKEN_* to keep track which query hasn't been completed
     * yet. Once all queries have returned, the model can be applied to the
     * view.
     */
    private int mOutstandingQueries = TOKEN_UNITIALIZED;
    private AlertDialog mModifyDialog;
    private EventBundle mEventBundle;
    private ArrayList<ReminderEntry> mReminders;
    private int mEventColor;
    private boolean mEventColorInitialized = false;
    private Uri mUri;
    private long mBegin;
    private long mEnd;
    private long mCalendarId = -1;
    private EventColorPickerDialog mColorPickerDialog;
    private AppCompatActivity mActivity;
    private boolean mSaveOnDetach = true;
    private boolean mIsReadOnly = false;
    private boolean mShowColorPalette = false;
    private InputMethodManager mInputMethodManager;
    private final View.OnClickListener mActionBarListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onActionBarItemSelected(v.getId());
        }
    };
    private boolean mUseCustomActionBar;
    private View.OnClickListener mOnColorPickerClicked = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            int[] colors = mModel.getCalendarEventColors();
            if (mColorPickerDialog == null) {
                mColorPickerDialog = EventColorPickerDialog.newInstance(colors,
                        mModel.getEventColor(), mModel.getCalendarColor(), mView.mIsMultipane);
                mColorPickerDialog.setOnColorSelectedListener(EditEventFragment.this);
            } else {
                mColorPickerDialog.setCalendarColor(mModel.getCalendarColor());
                mColorPickerDialog.setColors(colors, mModel.getEventColor());
            }
            final FragmentManager fragmentManager = getParentFragmentManager();
            fragmentManager.executePendingTransactions();
            if (!mColorPickerDialog.isAdded()) {
                mColorPickerDialog.show(fragmentManager, COLOR_PICKER_DIALOG_TAG);
            }
        }
    };

    public EditEventFragment() {
        this(null, null, false, -1, false, null);
    }

    public EditEventFragment(EventInfo event, ArrayList<ReminderEntry> reminders,
                             boolean eventColorInitialized, int eventColor, boolean readOnly, Intent intent) {
        mEvent = event;
        mIsReadOnly = readOnly;
        mIntent = intent;

        mReminders = reminders;
        mEventColorInitialized = eventColorInitialized;
        if (eventColorInitialized) {
            mEventColor = eventColor;
        }
        setHasOptionsMenu(true);
    }

    private void setModelIfDone(int queryType) {
        synchronized (this) {
            mOutstandingQueries &= ~queryType;
            if (mOutstandingQueries == 0) {
                if (mRestoreModel != null) {
                    mModel = mRestoreModel;
                }
                if (mShowModifyDialogOnLaunch && mModification == Utils.MODIFY_UNINITIALIZED) {
                    if (!TextUtils.isEmpty(mModel.mRrule)) {
                        displayEditWhichDialog();
                    } else {
                        mModification = Utils.MODIFY_ALL;
                    }

                }
                mView.setModel(mModel);
                mView.setModification(mModification);
            }
        }
    }

    private void showMissingFieldDialog(final SmartParsedSchedule parsedSchedule) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        LayoutInflater inflater = activity.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_missing_fields, null);

        final android.widget.EditText etTitle = dialogView.findViewById(R.id.et_title);
        final android.widget.EditText etLocation = dialogView.findViewById(R.id.et_location);
        final android.widget.EditText etTime = dialogView.findViewById(R.id.et_time);
        final android.widget.EditText etDescription = dialogView.findViewById(R.id.et_description);

        if (!TextUtils.isEmpty(parsedSchedule.title)) {
            etTitle.setText(parsedSchedule.title);
        } else {
            etTitle.setHint("Enter title");
            etTitle.setHintTextColor(activity.getResources().getColor(R.color.design_default_color_error));
        }

        if (!TextUtils.isEmpty(parsedSchedule.location)) {
            etLocation.setText(parsedSchedule.location);
        } else {
            etLocation.setHint("Enter location");
            etLocation.setHintTextColor(activity.getResources().getColor(R.color.design_default_color_error));
        }

        if (parsedSchedule.startMillis > 0) {
            etTime.setText(formatSmartTime(parsedSchedule.startMillis));
        } else {
            etTime.setHint("yyyy-MM-dd HH:mm:ss");
            etTime.setHintTextColor(activity.getResources().getColor(R.color.design_default_color_error));
        }

        if (!TextUtils.isEmpty(parsedSchedule.description)) {
            etDescription.setText(parsedSchedule.description);
        } else {
            etDescription.setHint("Description (optional)");
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.smart_input_complete_title)
                .setMessage("Some fields were missing from the AI result. Please complete them.")
                .setView(dialogView)
                .setPositiveButton(R.string.smart_input_apply, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String title = etTitle.getText().toString().trim();
                        String location = etLocation.getText().toString().trim();
                        String timeStr = etTime.getText().toString().trim();
                        String description = etDescription.getText().toString().trim();

                        if (TextUtils.isEmpty(title)) {
                            Toast.makeText(getActivity(), "Title is required.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (TextUtils.isEmpty(location)) {
                            Toast.makeText(getActivity(), "Location is required.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (TextUtils.isEmpty(timeStr)) {
                            Toast.makeText(getActivity(), "Time is required.", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        long startMillis = parseSmartTimeMillis(timeStr);
                        if (startMillis <= 0) {
                            Toast.makeText(getActivity(), "Use time format yyyy-MM-dd HH:mm:ss.", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        SmartParsedSchedule completedSchedule = new SmartParsedSchedule(
                                title,
                                location,
                                startMillis,
                                parsedSchedule.durationMillis,
                                description
                        );

                        if (mView != null) {
                            mView.fillSmartParsedData(
                                    completedSchedule.title,
                                    completedSchedule.location,
                                    completedSchedule.startMillis,
                                    completedSchedule.durationMillis,
                                    completedSchedule.description
                            );
                            Toast.makeText(getActivity(), "Schedule details applied.", Toast.LENGTH_SHORT).show();
                            analyzeParsedScheduleAsync(completedSchedule);
                        }
                    }
                })
                .setNegativeButton(R.string.smart_input_cancel, null)
                .show();
    }

    private String formatSmartTime(long millis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
        );
        String timezoneId = mView != null ? mView.getTimezone() : null;
        if (TextUtils.isEmpty(timezoneId)) {
            timezoneId = java.util.TimeZone.getDefault().getID();
        }
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(timezoneId));
        return sdf.format(new java.util.Date(millis));
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mColorPickerDialog = (EventColorPickerDialog) getActivity().getSupportFragmentManager()
                .findFragmentByTag(COLOR_PICKER_DIALOG_TAG);
        if (mColorPickerDialog != null) {
            mColorPickerDialog.setOnColorSelectedListener(this);
        }
    }

    private void startQuery() {
        mUri = null;
        mBegin = -1;
        mEnd = -1;
        if (mEvent != null) {
            if (mEvent.id != -1) {
                mModel.mId = mEvent.id;
                mUri = ContentUris.withAppendedId(Events.CONTENT_URI, mEvent.id);
            } else {
                // New event. All day?
                mModel.mAllDay = mEvent.extraLong == CalendarController.EXTRA_CREATE_ALL_DAY;
            }
            if (mEvent.startTime != null) {
                mBegin = mEvent.startTime.toMillis();
            }
            if (mEvent.endTime != null) {
                mEnd = mEvent.endTime.toMillis();
            }
            if (mEvent.calendarId != -1) {
                mCalendarId = mEvent.calendarId;
            }
        } else if (mEventBundle != null) {
            if (mEventBundle.id != -1) {
                mModel.mId = mEventBundle.id;
                mUri = ContentUris.withAppendedId(Events.CONTENT_URI, mEventBundle.id);
            }
            mBegin = mEventBundle.start;
            mEnd = mEventBundle.end;
        }

        if (mReminders != null) {
            mModel.mReminders = mReminders;
        }

        if (mEventColorInitialized) {
            mModel.setEventColor(mEventColor);
        }

        if (mBegin <= 0) {
            // use a default value instead
            mBegin = mHelper.constructDefaultStartTime(System.currentTimeMillis());
        }
        if (mEnd < mBegin) {
            // use a default value instead
            mEnd = mHelper.constructDefaultEndTime(mBegin, mActivity);
        }

        // Kick off the query for the event
        boolean newEvent = mUri == null;
        if (!newEvent) {
            mModel.mCalendarAccessLevel = Calendars.CAL_ACCESS_NONE;
            mOutstandingQueries = TOKEN_ALL;
            if (DEBUG) {
                Log.d(TAG, "startQuery: uri for event is " + mUri);
            }
            mHandler.startQuery(TOKEN_EVENT, null, mUri, EditEventHelper.EVENT_PROJECTION,
                    null /* selection */, null /* selection args */, null /* sort order */);
        } else {
            mOutstandingQueries = TOKEN_CALENDARS | TOKEN_COLORS;
            if (DEBUG) {
                Log.d(TAG, "startQuery: Editing a new event.");
            }
            mModel.mOriginalStart = mBegin;
            mModel.mOriginalEnd = mEnd;
            mModel.mStart = mBegin;
            mModel.mEnd = mEnd;
            mModel.mCalendarId = mCalendarId;
            mModel.mSelfAttendeeStatus = Attendees.ATTENDEE_STATUS_ACCEPTED;

            // Start a query in the background to read the list of calendars and colors
            mHandler.startQuery(TOKEN_CALENDARS, null, Calendars.CONTENT_URI,
                    EditEventHelper.CALENDARS_PROJECTION,
                    EditEventHelper.CALENDARS_WHERE_WRITEABLE_VISIBLE, null /* selection args */,
                    null /* sort order */);

            mHandler.startQuery(TOKEN_COLORS, null, Colors.CONTENT_URI,
                    EditEventHelper.COLORS_PROJECTION,
                    Colors.COLOR_TYPE + "=" + Colors.TYPE_EVENT, null, null);

            mModification = Utils.MODIFY_ALL;
            mView.setModification(mModification);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = (AppCompatActivity) activity;

        mHelper = new EditEventHelper(activity);
        mHandler = new QueryHandler(activity.getContentResolver());
        mModel = new CalendarEventModel(activity, mIntent);
        mInputMethodManager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);

        mUseCustomActionBar = !Utils.getConfigBool(mActivity, R.bool.multiple_pane_config);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
//        mActivity.requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        View view;
        if (mIsReadOnly) {
            view = inflater.inflate(R.layout.edit_event_single_column, null);
        } else {
            view = inflater.inflate(R.layout.edit_event, null);
        }
        mView = new EditEventView(mActivity, view, mOnDone);

        View btnSmartInput = view.findViewById(R.id.btn_smart_input);
        if (btnSmartInput != null) {
            btnSmartInput.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSmartInputDialog();
                }
            });
        }

        if (!Utils.isCalendarPermissionGranted(mActivity, true)) {
            //If permission is not granted
            ((TextView)view.findViewById(R.id.loading_message)).setText(R.string.calendar_permission_not_granted);
        } else {
            startQuery();
        }

        if (mUseCustomActionBar) {
            View actionBarButtons = inflater.inflate(R.layout.edit_event_custom_actionbar,
                    new LinearLayout(mActivity), false);
            View cancelActionView = actionBarButtons.findViewById(R.id.action_cancel);
            cancelActionView.setOnClickListener(mActionBarListener);
            View doneActionView = actionBarButtons.findViewById(R.id.action_done);
            doneActionView.setOnClickListener(mActionBarListener);
            ActionBar.LayoutParams layout = new ActionBar.LayoutParams(ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT);
            mActivity.getSupportActionBar().setCustomView(actionBarButtons, layout);
        }

        return view;
    }

    // 1. 显示操作菜单
    private void showSmartInputDialog() {
        // ==== 修改了这里：增加了一个选项 ====
        String[] options = {
                getString(R.string.smart_input_option_text),
                getString(R.string.smart_input_option_image),
                getString(R.string.smart_input_option_audio),
                getString(R.string.smart_input_option_voice),
                getString(R.string.smart_input_option_submit)};
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.smart_input_title)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: showTextInputDialog(); break;
                            case 1:
                                Intent imageIntent = new Intent(Intent.ACTION_GET_CONTENT);
                                imageIntent.setType("image/*");
                                imageIntent.addCategory(Intent.CATEGORY_OPENABLE);
                                startActivityForResult(Intent.createChooser(imageIntent, getString(R.string.smart_input_pick_image)), REQUEST_CODE_PICK_IMAGE);
                                break;
                            case 2:
                                Intent audioIntent = new Intent(Intent.ACTION_GET_CONTENT);
                                audioIntent.setType("audio/*");
                                audioIntent.addCategory(Intent.CATEGORY_OPENABLE);
                                startActivityForResult(Intent.createChooser(audioIntent, getString(R.string.smart_input_pick_audio)), REQUEST_CODE_PICK_AUDIO);
                                break;
                            case 3:
                                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_RECORD_AUDIO_PERMISSION);
                                } else {
                                    startRecording();
                                }
                                break;
                            case 4:
                                submitSmartInputToServer();
                                break;
                        }
                    }
                }).show();
    }

    // ==== 新增：开始录音 ====
    private void startRecording() {
        try {
            // 语音录入是一次新的智能输入，先清掉上一次的文字/图片，避免提交旧内容。
            mSmartTextFile = null;
            mSmartTextContent = "";
            mSmartImageFile = null;
            mSmartAudioFile = null;

            java.io.File folder = new java.io.File(getActivity().getFilesDir(), SMART_INPUT_FOLDER);
            if (!folder.exists()) folder.mkdirs();

            // 生成内部录音文件路径，格式为 m4a。
            mCurrentRecordPath = new java.io.File(folder, "voice_" + System.currentTimeMillis() + ".m4a").getAbsolutePath();

            mRecorder = new android.media.MediaRecorder();

            // VOICE_RECOGNITION 更适合 ASR；如果部分模拟器/设备不支持，会在 catch 中提示。
            mRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION);
            mRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setOutputFile(mCurrentRecordPath);
            mRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);

            // 关键：提高录音质量。目标是让后端看到 16kHz / mono / 较高码率，而不是 8kHz / 12kbps。
            mRecorder.setAudioSamplingRate(16000);
            mRecorder.setAudioEncodingBitRate(64000);
            mRecorder.setAudioChannels(1);

            mRecorder.prepare();
            mRecorder.start();

            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.smart_input_recording_title)
                    .setMessage(R.string.smart_input_recording_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.smart_input_stop, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            stopRecording();
                        }
                    })
                    .show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), R.string.smart_input_record_failed, Toast.LENGTH_SHORT).show();
            if (mRecorder != null) {
                mRecorder.release();
                mRecorder = null;
            }
        }
    }

    // ==== 新增：停止录音并释放资源 ====
    private void stopRecording() {
        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (RuntimeException stopException) {
                // 如果录音时间过短（刚点开始就点结束），stop() 可能会抛出异常，这里捕获掉防止崩溃
            }
            mRecorder.release();
            mRecorder = null;
            if (mCurrentRecordPath != null) {
                mSmartAudioFile = new File(mCurrentRecordPath);
                Log.d(TAG, "Recorded audio file: " + mSmartAudioFile.getAbsolutePath()
                        + ", exists=" + mSmartAudioFile.exists()
                        + ", size=" + mSmartAudioFile.length());
                Toast.makeText(getActivity(), R.string.smart_input_record_saved, Toast.LENGTH_SHORT).show();
                submitSmartInputToServer();
            }
        }
    }

    // 2. 处理纯文本输入的弹窗
    private void showTextInputDialog() {
        final android.widget.EditText input = new android.widget.EditText(getActivity());
        input.setHint(R.string.smart_input_text_hint);
        input.setMinLines(3);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.smart_input_paste_title)
                .setView(input)
                .setPositiveButton(R.string.smart_input_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString();
                        if (!text.isEmpty()) {
                            saveTextToInternalFolder(text);
                        }
                    }
                })
                .setNegativeButton(R.string.smart_input_cancel, null)
                .show();
    }

    // ==== 新增：读取 JSON、清洗数据并自动填表 ====
    private void loadParsedJsonData() {
        try {
            java.io.File folder = new java.io.File(getActivity().getFilesDir(), SMART_INPUT_FOLDER);
            // ⚠️ 注意：这里假定后端生成的文件名为 result.json，如果不同请修改这里
            java.io.File jsonFile = new java.io.File(folder, "result.json");

            if (!jsonFile.exists()) {
                Toast.makeText(getActivity(), R.string.smart_input_no_result, Toast.LENGTH_SHORT).show();
                return;
            }

            // 1. 读取文件内容到字符串
            java.io.FileInputStream fis = new java.io.FileInputStream(jsonFile);
            byte[] data = new byte[(int) jsonFile.length()];
            fis.read(data);
            fis.close();
            String jsonString = new String(data, "UTF-8");

            // 2. 解析外层 JSON，获取 reply 字段
            org.json.JSONObject rootObj = new org.json.JSONObject(jsonString);
            String replyStr = rootObj.getString("reply");

            // 3. 提取真正的内部 JSON (剔除大模型生成的冗余文本)
            int startIndex = replyStr.indexOf("{");
            int endIndex = replyStr.lastIndexOf("}");
            if (startIndex == -1 || endIndex == -1) {
                Toast.makeText(getActivity(), R.string.smart_input_result_format_error, Toast.LENGTH_SHORT).show();
                return;
            }
            String cleanJsonStr = replyStr.substring(startIndex, endIndex + 1);

            // 4. 解析内部 JSON
            org.json.JSONObject innerObj = new org.json.JSONObject(cleanJsonStr);
            org.json.JSONArray list = innerObj.getJSONArray("日程列表");

            if (list.length() > 0) {
                long durationMillis = mView != null
                        ? mView.getCurrentDurationMillis()
                        : DateUtils.HOUR_IN_MILLIS;
                org.json.JSONObject eventObj = list.getJSONObject(0); // 取出第一个日程

                String title = eventObj.optString("事件", "");
                String location = eventObj.optString("地点", "");
                String timeStr = eventObj.optString("时间", ""); // 格式: 2024-06-01 15:00:00

                // 把人物拼接进描述(Description)里
                String description = "";
                if (eventObj.has("人物")) {
                    org.json.JSONArray people = eventObj.getJSONArray("人物");
                    StringBuilder sb = new StringBuilder(getString(R.string.smart_input_people_prefix));
                    for (int i = 0; i < people.length(); i++) {
                        sb.append(people.getString(i)).append(" ");
                    }
                    description = sb.toString();
                }

                // 5. 转换时间字符串为毫秒时间戳
                long startMillis = parseSmartTimeMillis(timeStr);

                // 6. 调用 View 层更新界面
                if (mView != null) {
                    SmartParsedSchedule parsedSchedule = new SmartParsedSchedule(
                            title,
                            location,
                            startMillis,
                            durationMillis,
                            description
                    );
                    mView.fillSmartParsedData(
                            parsedSchedule.title,
                            parsedSchedule.location,
                            parsedSchedule.startMillis,
                            parsedSchedule.durationMillis,
                            parsedSchedule.description
                    );
                    Toast.makeText(getActivity(), R.string.smart_input_filled, Toast.LENGTH_SHORT).show();
                    analyzeParsedScheduleAsync(parsedSchedule);

                    // 阅后即焚：填完之后删除这个 json，防止下次重复读取
                    jsonFile.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), getString(R.string.smart_input_read_failed) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 3. 将文字保存为 txt 文件到内部文件夹
    private void saveTextToInternalFolder(String content) {
        try {
            java.io.File folder = new java.io.File(getActivity().getFilesDir(), SMART_INPUT_FOLDER);
            if (!folder.exists()) folder.mkdirs();

            String fileName = "text_" + System.currentTimeMillis() + ".txt";
            java.io.File file = new java.io.File(folder, fileName);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();

            // 文字输入是一次新的智能输入，清掉上一次的图片/音频，避免提交旧内容。
            mSmartTextFile = file;
            mSmartTextContent = content;
            mSmartImageFile = null;
            mSmartAudioFile = null;
            Toast.makeText(getActivity(), R.string.smart_input_text_saved, Toast.LENGTH_SHORT).show();
            submitSmartInputToServer();
        } catch (java.io.IOException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), R.string.smart_input_input_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // 4. 将选中的图片或音频复制到内部文件夹
    private File copyUriContentToInternalFolder(Uri sourceUri, String prefix, String extension) {
        try {
            java.io.File folder = new java.io.File(getActivity().getFilesDir(), SMART_INPUT_FOLDER);
            if (!folder.exists()) folder.mkdirs();

            String fileName = prefix + "_" + System.currentTimeMillis() + extension;
            java.io.File destinationFile = new java.io.File(folder, fileName);

            java.io.InputStream is = getActivity().getContentResolver().openInputStream(sourceUri);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(destinationFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            is.close();
            fos.close();

            Toast.makeText(getActivity(), R.string.smart_input_file_saved, Toast.LENGTH_SHORT).show();
            return destinationFile;
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), R.string.smart_input_upload_failed, Toast.LENGTH_SHORT).show();
            return null;
        }
    }


    private void submitSmartInputToServer() {
        // 只提交本次明确选择/录入的内容，不再自动查找历史文件，避免旧文本/旧图片污染本次语音解析。
        File textFile = mSmartTextFile;
        File imageFile = mSmartImageFile;
        File audioFile = mSmartAudioFile;

        String text = mSmartTextContent;
        if ((text == null || text.isEmpty()) && textFile != null) {
            text = readTextFileQuietly(textFile);
        }

        if ((text == null || text.trim().isEmpty()) && imageFile == null && audioFile == null) {
            Toast.makeText(getActivity(), R.string.smart_input_need_content, Toast.LENGTH_SHORT).show();
            return;
        }

        String baseUrl = BuildConfig.OCR_BASE_URL;
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        String url = baseUrl + "parse_schedule";

        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("text", text == null ? "" : text);

        if (imageFile != null && imageFile.exists()) {
            builder.addFormDataPart(
                    "image",
                    imageFile.getName(),
                    RequestBody.create(MediaType.parse("image/*"), imageFile)
            );
        }

        if (audioFile != null && audioFile.exists()) {
            builder.addFormDataPart(
                    "audio",
                    audioFile.getName(),
                    RequestBody.create(MediaType.parse("audio/*"), audioFile)
            );
        }

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        Toast.makeText(getActivity(), R.string.smart_input_submitting, Toast.LENGTH_SHORT).show();

        mSmartInputClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (mActivity != null) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getActivity(), getString(R.string.smart_input_connect_failed) + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (mActivity != null) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!response.isSuccessful()) {
                                Toast.makeText(getActivity(), getString(R.string.smart_input_server_error) + response.code() + " " + body, Toast.LENGTH_LONG).show();
                                return;
                            }
                            handleSmartParseResponse(body);
                        }
                    });
                }
            }
        });
    }

    private File findLatestSmartFile(String prefix) {
        File folder = new File(getActivity().getFilesDir(), SMART_INPUT_FOLDER);
        File[] files = folder.listFiles();
        if (files == null) return null;
        File latest = null;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(prefix)) {
                if (latest == null || f.lastModified() > latest.lastModified()) {
                    latest = f;
                }
            }
        }
        return latest;
    }

    private String readTextFileQuietly(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            fis.read(data);
            fis.close();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void handleSmartParseResponse(String body) {
        try {
            org.json.JSONObject rootObj = new org.json.JSONObject(body);
            if (!rootObj.optBoolean("success", false)) {
                Toast.makeText(getActivity(), getString(R.string.smart_input_ai_failed) + rootObj.optString("error", body), Toast.LENGTH_LONG).show();
                return;
            }

            org.json.JSONObject innerObj = rootObj.optJSONObject("data");
            if (innerObj == null) {
                String replyStr = rootObj.optString("reply", "");
                int startIndex = replyStr.indexOf("{");
                int endIndex = replyStr.lastIndexOf("}");
                if (startIndex == -1 || endIndex == -1) {
                    Toast.makeText(getActivity(), R.string.smart_input_no_json, Toast.LENGTH_SHORT).show();
                    return;
                }
                innerObj = new org.json.JSONObject(replyStr.substring(startIndex, endIndex + 1));
            }

            org.json.JSONArray list = innerObj.optJSONArray("日程列表");
            if (list == null || list.length() == 0) {
                Toast.makeText(getActivity(), R.string.smart_input_ai_no_events, Toast.LENGTH_SHORT).show();
                return;
            }

            org.json.JSONObject eventObj = list.getJSONObject(0);
            String title = eventObj.optString("事件", "");
            String location = eventObj.optString("地点", "");
            String timeStr = eventObj.optString("时间", "");

            String description = "";
            org.json.JSONArray people = eventObj.optJSONArray("人物");
            if (people != null && people.length() > 0) {
                StringBuilder sb = new StringBuilder("参与人物：");
                for (int i = 0; i < people.length(); i++) {
                    sb.append(people.optString(i)).append(" ");
                }
                description = sb.toString();
            }

            long startMillis = parseSmartTimeMillis(timeStr);

            long durationMillis = mView != null
                    ? mView.getCurrentDurationMillis()
                    : DateUtils.HOUR_IN_MILLIS;
            SmartParsedSchedule parsedSchedule = new SmartParsedSchedule(
                    title,
                    location,
                    startMillis,
                    durationMillis,
                    description
            );
            boolean hasMissingField = TextUtils.isEmpty(parsedSchedule.title)
                    || TextUtils.isEmpty(parsedSchedule.location)
                    || parsedSchedule.startMillis <= 0;

            if (hasMissingField) {
                showMissingFieldDialog(parsedSchedule);
            } else if (mView != null) {
                mView.fillSmartParsedData(
                        parsedSchedule.title,
                        parsedSchedule.location,
                        parsedSchedule.startMillis,
                        parsedSchedule.durationMillis,
                        parsedSchedule.description
                );
                Toast.makeText(getActivity(), R.string.smart_input_ai_filled, Toast.LENGTH_SHORT).show();
                analyzeParsedScheduleAsync(parsedSchedule);
            }
        } catch (Exception e) {
            Toast.makeText(getActivity(), getString(R.string.smart_input_parse_failed) + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void analyzeParsedScheduleAsync(SmartParsedSchedule parsedSchedule) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                long excludeEventId = mModel != null ? mModel.mId : -1L;
                SmartScheduleAdvisor.AnalysisResult analysisResult = mSmartScheduleAdvisor.analyze(
                        activity,
                        parsedSchedule.startMillis,
                        parsedSchedule.durationMillis,
                        excludeEventId
                );

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showSmartCompletionDialog(parsedSchedule, analysisResult);
                    }
                });
            }
        }).start();
    }

    private void showSmartCompletionDialog(
            SmartParsedSchedule parsedSchedule,
            SmartScheduleAdvisor.AnalysisResult analysisResult
    ) {
        if (!isAdded() || getActivity() == null) {
            return;
        }

        List<String> missingFields = collectMissingFields(parsedSchedule);
        boolean hasConflicts = !analysisResult.conflicts.isEmpty();
        boolean shouldOfferSuggestions = hasConflicts || !analysisResult.suggestions.isEmpty()
                || parsedSchedule.startMillis <= 0;

        if (missingFields.isEmpty() && !shouldOfferSuggestions) {
            Toast.makeText(getActivity(), "No time conflicts detected.", Toast.LENGTH_SHORT).show();
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity())
                .setTitle(hasConflicts ? "Time Conflict Detected" : "Smart Completion")
                .setMessage(buildSmartCompletionSummary(parsedSchedule, missingFields, analysisResult));

        if (shouldOfferSuggestions) {
            final List<SmartScheduleAdvisor.TimeSuggestion> suggestions = analysisResult.suggestions;
            List<String> optionLabels = new ArrayList<>();
            boolean includeKeepCurrent = parsedSchedule.startMillis > 0;
            final int[] selectedChoice = new int[]{includeKeepCurrent ? 0 : -1};

            if (includeKeepCurrent) {
                optionLabels.add("Keep current time (keep all current schedules)");
            }
            for (SmartScheduleAdvisor.TimeSuggestion suggestion : suggestions) {
                optionLabels.add("Use suggested slot: " + suggestion.toDisplayText(getActivity()));
            }

            builder.setSingleChoiceItems(optionLabels.toArray(new String[0]), selectedChoice[0], new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    selectedChoice[0] = which;
                }
            });
            builder.setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int suggestionIndex = includeKeepCurrent ? selectedChoice[0] - 1 : selectedChoice[0];
                    if (suggestionIndex >= 0 && suggestionIndex < suggestions.size() && mView != null) {
                        SmartScheduleAdvisor.TimeSuggestion suggestion = suggestions.get(suggestionIndex);
                        mView.applySuggestedTimeRange(suggestion.startMillis, suggestion.endMillis);
                        Toast.makeText(getActivity(), "Selected slot applied.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            builder.setNegativeButton("Continue Editing", null);
        } else {
            builder.setPositiveButton("Continue Editing", null);
        }

        builder.show();
    }

    private List<String> collectMissingFields(SmartParsedSchedule parsedSchedule) {
        List<String> missingFields = new ArrayList<>();
        if (TextUtils.isEmpty(parsedSchedule.title)) {
            missingFields.add("title");
        }
        if (parsedSchedule.startMillis <= 0) {
            missingFields.add("time");
        }
        if (TextUtils.isEmpty(parsedSchedule.location)) {
            missingFields.add("location");
        }
        return missingFields;
    }

    private String buildSmartCompletionSummary(
            SmartParsedSchedule parsedSchedule,
            List<String> missingFields,
            SmartScheduleAdvisor.AnalysisResult analysisResult
    ) {
        StringBuilder summary = new StringBuilder();

        if (!TextUtils.isEmpty(parsedSchedule.title)) {
            summary.append("Title: ").append(parsedSchedule.title).append("\n");
        }
        if (!TextUtils.isEmpty(parsedSchedule.location)) {
            summary.append("Location: ").append(parsedSchedule.location).append("\n");
        }
        if (parsedSchedule.startMillis > 0) {
            summary.append("Time: ").append(
                    Utils.formatDateRange(
                            getActivity(),
                            parsedSchedule.startMillis,
                            parsedSchedule.startMillis + parsedSchedule.durationMillis,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                    )
            ).append("\n");
        }
        if (!missingFields.isEmpty()) {
            summary.append("Missing: ");
            for (int i = 0; i < missingFields.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                summary.append(missingFields.get(i));
            }
            summary.append("\n");
        }
        if (!analysisResult.conflicts.isEmpty()) {
            summary.append("Conflicts:\n");
            for (int i = 0; i < analysisResult.conflicts.size(); i++) {
                if (i >= 3) {
                    summary.append("... and ")
                            .append(analysisResult.conflicts.size() - i)
                            .append(" more");
                    break;
                }
                summary.append("- ")
                        .append(analysisResult.conflicts.get(i).toDisplayText(getActivity()))
                        .append("\n");
            }
        } else if (parsedSchedule.startMillis > 0) {
            summary.append("No conflicts found.\n");
        }
        if (!analysisResult.suggestions.isEmpty()
                && (!analysisResult.conflicts.isEmpty() || parsedSchedule.startMillis <= 0)) {
            summary.append("Choose a suggested slot below if you want.");
        }

        return summary.toString().trim();
    }

    private long parseSmartTimeMillis(String timeStr) {
        if (TextUtils.isEmpty(timeStr) || "null".equalsIgnoreCase(timeStr)) {
            return 0L;
        }

        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()
            );
            sdf.setLenient(false);

            String timezoneId = mView != null ? mView.getTimezone() : null;
            if (TextUtils.isEmpty(timezoneId)) {
                timezoneId = java.util.TimeZone.getDefault().getID();
            }
            sdf.setTimeZone(java.util.TimeZone.getTimeZone(timezoneId));

            java.util.Date date = sdf.parse(timeStr);
            return date != null ? date.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static final class SmartParsedSchedule {
        final String title;
        final String location;
        final long startMillis;
        final long durationMillis;
        final String description;

        SmartParsedSchedule(
                String title,
                String location,
                long startMillis,
                long durationMillis,
                String description
        ) {
            this.title = title;
            this.location = location;
            this.startMillis = startMillis;
            this.durationMillis = durationMillis;
            this.description = description;
        }
    }

    // 5. 接收从相册/文件管理器返回的数据
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedUri = data.getData();
            if (selectedUri != null) {
                switch (requestCode) {
                    case REQUEST_CODE_PICK_IMAGE:
                        // 图片上传是一次新的智能输入，清掉上一次文字/音频。
                        mSmartTextFile = null;
                        mSmartTextContent = "";
                        mSmartAudioFile = null;
                        mSmartImageFile = copyUriContentToInternalFolder(selectedUri, "image", ".jpg");
                        if (mSmartImageFile != null) {
                            Log.d(TAG, "Selected image file: " + mSmartImageFile.getAbsolutePath()
                                    + ", exists=" + mSmartImageFile.exists()
                                    + ", size=" + mSmartImageFile.length());
                            submitSmartInputToServer();
                        }
                        break;
                    case REQUEST_CODE_PICK_AUDIO:
                        // 音频上传是一次新的智能输入，清掉上一次文字/图片。
                        mSmartTextFile = null;
                        mSmartTextContent = "";
                        mSmartImageFile = null;
                        // 不要强行保存为 mp3。安卓/录音文件常见是 m4a/aac，使用 m4a 后缀更稳。
                        mSmartAudioFile = copyUriContentToInternalFolder(selectedUri, "audio", ".m4a");
                        if (mSmartAudioFile != null) {
                            Log.d(TAG, "Selected audio file: " + mSmartAudioFile.getAbsolutePath()
                                    + ", exists=" + mSmartAudioFile.exists()
                                    + ", size=" + mSmartAudioFile.length());
                            submitSmartInputToServer();
                        }
                        break;
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户同意了麦克风权限，开始录音
                startRecording();
            } else {
                Toast.makeText(getActivity(), R.string.smart_input_need_mic_permission, Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mUseCustomActionBar) {
            mActivity.getSupportActionBar().setCustomView(null);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(EditEventFragment.this.getActivity(),
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(EditEventFragment.this.getActivity(), new String[]{Manifest.permission.READ_CONTACTS},
                    0);
        }

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(BUNDLE_KEY_MODEL)) {
                mRestoreModel = (CalendarEventModel) savedInstanceState.getSerializable(
                        BUNDLE_KEY_MODEL);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EDIT_STATE)) {
                mModification = savedInstanceState.getInt(BUNDLE_KEY_EDIT_STATE);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EDIT_ON_LAUNCH)) {
                mShowModifyDialogOnLaunch = savedInstanceState
                        .getBoolean(BUNDLE_KEY_EDIT_ON_LAUNCH);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_EVENT)) {
                mEventBundle = (EventBundle) savedInstanceState.getSerializable(BUNDLE_KEY_EVENT);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_READ_ONLY)) {
                mIsReadOnly = savedInstanceState.getBoolean(BUNDLE_KEY_READ_ONLY);
            }
            if (savedInstanceState.containsKey(BUNDLE_KEY_SHOW_COLOR_PALETTE)) {
                mShowColorPalette = savedInstanceState.getBoolean(BUNDLE_KEY_SHOW_COLOR_PALETTE);
            }

        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        if (!mUseCustomActionBar) {
            inflater.inflate(R.menu.edit_event_title_bar, menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return onActionBarItemSelected(item.getItemId());
    }

    /**
     * Handles menu item selections, whether they come from our custom action bar buttons or from
     * the standard menu items. Depends on the menu item ids matching the custom action bar button
     * ids.
     *
     * @param itemId the button or menu item id
     * @return whether the event was handled here
     */
    private boolean onActionBarItemSelected(int itemId) {
        if (itemId == R.id.action_done) {
            if (EditEventHelper.canModifyEvent(mModel) || EditEventHelper.canRespond(mModel)) {
                if (mView != null && mView.prepareForSave()) {
                    if (mModification == Utils.MODIFY_UNINITIALIZED) {
                        mModification = Utils.MODIFY_ALL;
                    }
                    mOnDone.setDoneCode(Utils.DONE_SAVE | Utils.DONE_EXIT);
                    mOnDone.run();
                } else {
                    mOnDone.setDoneCode(Utils.DONE_REVERT);
                    mOnDone.run();
                }
            } else if (EditEventHelper.canAddReminders(mModel) && mModel.mId != -1
                    && mOriginalModel != null && mView.prepareForSave()) {
                saveReminders();
                mOnDone.setDoneCode(Utils.DONE_EXIT);
                mOnDone.run();
            } else {
                mOnDone.setDoneCode(Utils.DONE_REVERT);
                mOnDone.run();
            }
        } else if (itemId == R.id.action_cancel) {
            mOnDone.setDoneCode(Utils.DONE_REVERT);
            mOnDone.run();
        }
        return true;
    }

    private void saveReminders() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>(3);
        boolean changed = EditEventHelper.saveReminders(ops, mModel.mId, mModel.mReminders,
                mOriginalModel.mReminders, false /* no force save */);

        if (!changed) {
            return;
        }

        AsyncQueryService service = new AsyncQueryService(getActivity());
        service.startBatch(0, null, Calendars.CONTENT_URI.getAuthority(), ops, 0);
        // Update the "hasAlarm" field for the event
        Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, mModel.mId);
        int len = mModel.mReminders.size();
        boolean hasAlarm = len > 0;
        if (hasAlarm != mOriginalModel.mHasAlarm) {
            ContentValues values = new ContentValues();
            values.put(Events.HAS_ALARM, hasAlarm ? 1 : 0);
            service.startUpdate(0, null, uri, values, null, null, 0);
        }

        Toast.makeText(mActivity, R.string.saving_event, Toast.LENGTH_SHORT).show();
    }

    protected void displayEditWhichDialog() {
        if (mModification == Utils.MODIFY_UNINITIALIZED) {
            final boolean notSynced = TextUtils.isEmpty(mModel.mSyncId);
            boolean isFirstEventInSeries = mModel.mIsFirstEventInSeries;
            int itemIndex = 0;
            CharSequence[] items;

            if (notSynced) {
                // If this event has not been synced, then don't allow deleting
                // or changing a single instance.
                if (isFirstEventInSeries) {
                    // Still display the option so the user knows all events are
                    // changing
                    items = new CharSequence[1];
                } else {
                    items = new CharSequence[2];
                }
            } else {
                if (isFirstEventInSeries) {
                    items = new CharSequence[2];
                } else {
                    items = new CharSequence[3];
                }
                items[itemIndex++] = mActivity.getText(R.string.modify_event);
            }
            items[itemIndex++] = mActivity.getText(R.string.modify_all);

            // Do one more check to make sure this remains at the end of the list
            if (!isFirstEventInSeries) {
                items[itemIndex++] = mActivity.getText(R.string.modify_all_following);
            }

            // Display the modification dialog.
            if (mModifyDialog != null) {
                mModifyDialog.dismiss();
                mModifyDialog = null;
            }
            mModifyDialog = new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.edit_event_label)
                    .setItems(items, new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                // Update this if we start allowing exceptions
                                // to unsynced events in the app
                                mModification = notSynced ? Utils.MODIFY_ALL
                                        : Utils.MODIFY_SELECTED;
                                if (mModification == Utils.MODIFY_SELECTED) {
                                    mModel.mOriginalSyncId = notSynced ? null : mModel.mSyncId;
                                    mModel.mOriginalId = mModel.mId;
                                }
                            } else if (which == 1) {
                                mModification = notSynced ? Utils.MODIFY_ALL_FOLLOWING
                                        : Utils.MODIFY_ALL;
                            } else if (which == 2) {
                                mModification = Utils.MODIFY_ALL_FOLLOWING;
                            }

                            mView.setModification(mModification);
                        }
                    }).show();

            mModifyDialog.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    Activity a = EditEventFragment.this.getActivity();
                    if (a != null) {
                        a.finish();
                    }
                }
            });
        }
    }

    boolean isEmptyNewEvent() {
        if (mOriginalModel != null) {
            // Not new
            return false;
        }

        if (mModel.mOriginalStart != mModel.mStart || mModel.mOriginalEnd != mModel.mEnd) {
            return false;
        }

        if (!mModel.mAttendeesList.isEmpty()) {
            return false;
        }

        return mModel.isEmpty();
    }

    public void onBackPressed() {
        if (canSave()) {
            showDiscardConfirmAlert();
            return;
        }

        Utils.returnToCalendarHome(getActivity());
    }

    private boolean canSave() {
        Activity act = getActivity();
        return mSaveOnDetach && act != null && !mIsReadOnly && !act.isChangingConfigurations()
                && mView.prepareForSave();
    }

    private void showDiscardConfirmAlert() {
        new MaterialAlertDialogBuilder(getActivity())
                .setMessage(R.string.discard_event_changes)
                .setCancelable(true)
                .setPositiveButton(R.string.discard, ((dialog, which) -> {
                    revertEventChanges();
                    Utils.returnToCalendarHome(getActivity());
                    dialog.cancel();
                }))
                .setNegativeButton(R.string.cancel, ((dialog, which) -> dialog.cancel()))
                .show();
    }

    private void revertEventChanges() {
        mOnDone.setDoneCode(Utils.DONE_REVERT);
        mOnDone.run();
    }

    @Override
    public void onDestroy() {
        if (mView != null) {
            mView.setModel(null);
        }
        if (mModifyDialog != null) {
            mModifyDialog.dismiss();
            mModifyDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void eventsChanged() {
        // TODO Requery to see if event has changed
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        mView.prepareForSave();
        outState.putSerializable(BUNDLE_KEY_MODEL, mModel);
        outState.putInt(BUNDLE_KEY_EDIT_STATE, mModification);
        if (mEventBundle == null && mEvent != null) {
            mEventBundle = new EventBundle();
            mEventBundle.id = mEvent.id;
            if (mEvent.startTime != null) {
                mEventBundle.start = mEvent.startTime.toMillis();
            }
            if (mEvent.endTime != null) {
                mEventBundle.end = mEvent.startTime.toMillis();
            }
        }
        outState.putBoolean(BUNDLE_KEY_EDIT_ON_LAUNCH, mShowModifyDialogOnLaunch);
        outState.putSerializable(BUNDLE_KEY_EVENT, mEventBundle);
        outState.putBoolean(BUNDLE_KEY_READ_ONLY, mIsReadOnly);
        outState.putBoolean(BUNDLE_KEY_SHOW_COLOR_PALETTE, mView.isColorPaletteVisible());
    }

    @Override
    public long getSupportedEventTypes() {
        return EventType.USER_HOME;
    }

    @Override
    public void handleEvent(EventInfo event) {
        // It's currently unclear if we want to save the event or not when home
        // is pressed. When creating a new event we shouldn't save since we
        // can't get the id of the new event easily.
        if ((false && event.eventType == EventType.USER_HOME) || (event.eventType == EventType.GO_TO
                && mSaveOnDetach)) {
            if (mView != null && mView.prepareForSave()) {
                mOnDone.setDoneCode(Utils.DONE_SAVE);
                mOnDone.run();
            }
        }
    }

    @Override
    public void onColorSelected(int color) {
        if (!mModel.isEventColorInitialized() || mModel.getEventColor() != color) {
            mModel.setEventColor(color);
            mView.updateHeadlineColor(color);
        }
    }

    private static class EventBundle implements Serializable {
        private static final long serialVersionUID = 1L;
        long id = -1;
        long start = -1;
        long end = -1;
    }

    // TODO turn this into a helper function in EditEventHelper for building the
    // model
    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            // If the query didn't return a cursor for some reason return
            if (cursor == null) {
                return;
            }

            // If the Activity is finishing, then close the cursor.
            // Otherwise, use the new cursor in the adapter.
            final Activity activity = EditEventFragment.this.getActivity();
            if (activity == null || activity.isFinishing()) {
                cursor.close();
                return;
            }
            long eventId;
            switch (token) {
                case TOKEN_EVENT:
                    if (!cursor.moveToFirst()) {
                        // The cursor is empty. This can happen if the event
                        // was deleted.
                        cursor.close();
                        mOnDone.setDoneCode(Utils.DONE_EXIT);
                        mSaveOnDetach = false;
                        mOnDone.run();
                        return;
                    }
                    mOriginalModel = new CalendarEventModel();
                    EditEventHelper.setModelFromCursor(mOriginalModel, cursor, activity);
                    EditEventHelper.setModelFromCursor(mModel, cursor, activity);
                    cursor.close();

                    mOriginalModel.mUri = mUri.toString();

                    mModel.mUri = mUri.toString();
                    mModel.mOriginalStart = mBegin;
                    mModel.mOriginalEnd = mEnd;
                    mModel.mIsFirstEventInSeries = mBegin == mOriginalModel.mStart;
                    mModel.mStart = mBegin;
                    mModel.mEnd = mEnd;
                    if (mEventColorInitialized) {
                        mModel.setEventColor(mEventColor);
                    }
                    eventId = mModel.mId;

                    // TOKEN_ATTENDEES
                    if (mModel.mHasAttendeeData && eventId != -1) {
                        Uri attUri = Attendees.CONTENT_URI;
                        String[] whereArgs = {
                                Long.toString(eventId)
                        };
                        mHandler.startQuery(TOKEN_ATTENDEES, null, attUri,
                                EditEventHelper.ATTENDEES_PROJECTION,
                                EditEventHelper.ATTENDEES_WHERE /* selection */,
                                whereArgs /* selection args */, null /* sort order */);
                    } else {
                        setModelIfDone(TOKEN_ATTENDEES);
                    }

                    // TOKEN_REMINDERS
                    if (mModel.mHasAlarm && mReminders == null) {
                        Uri rUri = Reminders.CONTENT_URI;
                        String[] remArgs = {
                                Long.toString(eventId)
                        };
                        mHandler.startQuery(TOKEN_REMINDERS, null, rUri,
                                EditEventHelper.REMINDERS_PROJECTION,
                                EditEventHelper.REMINDERS_WHERE /* selection */,
                                remArgs /* selection args */, null /* sort order */);
                    } else {
                        if (mReminders == null) {
                            // mReminders should not be null.
                            mReminders = new ArrayList<ReminderEntry>();
                        } else {
                            Collections.sort(mReminders);
                        }
                        mOriginalModel.mReminders = mReminders;
                        mModel.mReminders =
                                (ArrayList<ReminderEntry>) mReminders.clone();
                        setModelIfDone(TOKEN_REMINDERS);
                    }

                    final String selection;
                    final String[] selectionArgs;
                    final boolean isRecurring = !TextUtils.isEmpty(mModel.mRrule);
                    if (isRecurring && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        // recurring event AND api level < 30. disable changing calendars as moving
                        // recurrences is currently only possible for api level 30+
                        selection = EditEventHelper.CALENDARS_WHERE;
                        selectionArgs = new String[] { Long.toString(mModel.mCalendarId) };
                    } else if (isRecurring) {
                        // recurring event AND api level >= 30. enable changing calendars to synced
                        // calendars only, as we currently don't allow recurrence exceptions in local calendars
                        // and would lose them when moving the recurrence between calendars
                        selection = EditEventHelper.CALENDARS_SELECTION_FOR_MOVING_RECURRENCE;
                        selectionArgs = new String[] { Long.toString(mModel.mCalendarId) };
                    } else {
                        // non recurring event. enable changing calendars to all calendars.
                        selection = EditEventHelper.CALENDARS_WHERE_WRITEABLE_VISIBLE;
                        selectionArgs = null;
                    }

                    // TOKEN_CALENDARS
                    mHandler.startQuery(TOKEN_CALENDARS, null, Calendars.CONTENT_URI,
                            EditEventHelper.CALENDARS_PROJECTION,
                            selection,
                            selectionArgs,
                            null /* sort order */);

                    // TOKEN_COLORS
                    mHandler.startQuery(TOKEN_COLORS, null, Colors.CONTENT_URI,
                            EditEventHelper.COLORS_PROJECTION,
                            Colors.COLOR_TYPE + "=" + Colors.TYPE_EVENT, null, null);

                    // TOKEN_EXTENDED
                    Uri extendedPropUri = ExtendedProperty.contentUri(
                            mModel.mCalendarAccountName,
                            mModel.mCalendarAccountType
                    );
                    String[] selArgs = new String[]{ Long.toString(eventId) };
                    mHandler.startQuery(TOKEN_EXTENDED, null, extendedPropUri,
                            EditEventHelper.EXTENDED_PROJECTION,
                            EditEventHelper.EXTENDED_WHERE_EVENT, selArgs, null);

                    setModelIfDone(TOKEN_EVENT);
                    break;
                case TOKEN_ATTENDEES:
                    try (cursor) {
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(EditEventHelper.ATTENDEES_INDEX_NAME);
                            String email = cursor.getString(EditEventHelper.ATTENDEES_INDEX_EMAIL);
                            int status = cursor.getInt(EditEventHelper.ATTENDEES_INDEX_STATUS);
                            int relationship = cursor
                                    .getInt(EditEventHelper.ATTENDEES_INDEX_RELATIONSHIP);
                            if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
                                if (email != null) {
                                    mModel.mOrganizer = email;
                                    mModel.mIsOrganizer = mModel.mOwnerAccount
                                            .equalsIgnoreCase(email);
                                    mOriginalModel.mOrganizer = email;
                                    mOriginalModel.mIsOrganizer = mOriginalModel.mOwnerAccount
                                            .equalsIgnoreCase(email);
                                }

                                if (TextUtils.isEmpty(name)) {
                                    mModel.mOrganizerDisplayName = mModel.mOrganizer;
                                    mOriginalModel.mOrganizerDisplayName =
                                            mOriginalModel.mOrganizer;
                                } else {
                                    mModel.mOrganizerDisplayName = name;
                                    mOriginalModel.mOrganizerDisplayName = name;
                                }
                            }

                            if (email != null) {
                                if (mModel.mOwnerAccount != null &&
                                        mModel.mOwnerAccount.equalsIgnoreCase(email)) {
                                    int attendeeId =
                                            cursor.getInt(EditEventHelper.ATTENDEES_INDEX_ID);
                                    mModel.mOwnerAttendeeId = attendeeId;
                                    mModel.mSelfAttendeeStatus = status;
                                    mOriginalModel.mOwnerAttendeeId = attendeeId;
                                    mOriginalModel.mSelfAttendeeStatus = status;
                                    continue;
                                }
                            }
                            Attendee attendee = new Attendee(name, email);
                            attendee.mStatus = status;
                            mModel.addAttendee(attendee);
                            mOriginalModel.addAttendee(attendee);
                        }
                    }

                    setModelIfDone(TOKEN_ATTENDEES);
                    break;
                case TOKEN_REMINDERS:
                    try (cursor) {
                        // Add all reminders to the models
                        while (cursor.moveToNext()) {
                            int minutes = cursor.getInt(EditEventHelper.REMINDERS_INDEX_MINUTES);
                            int method = cursor.getInt(EditEventHelper.REMINDERS_INDEX_METHOD);
                            ReminderEntry re = ReminderEntry.valueOf(minutes, method);
                            mModel.mReminders.add(re);
                            mOriginalModel.mReminders.add(re);
                        }

                        // Sort appropriately for display
                        Collections.sort(mModel.mReminders);
                        Collections.sort(mOriginalModel.mReminders);
                    }

                    setModelIfDone(TOKEN_REMINDERS);
                    break;
                case TOKEN_CALENDARS:
                    try (cursor) {
                        MatrixCursor matrixCursor = Utils.matrixCursorFromCursor(cursor);
                        if (DEBUG) {
                            Log.d(TAG, "onQueryComplete: setting cursor with " + matrixCursor.getCount() + " calendars");
                        }
                        if (mModel.mId != -1) {
                            // Populate model for an existing event
                            EditEventHelper.setModelFromCalendarCursor(mModel, cursor, activity);
                            EditEventHelper.setModelFromCalendarCursor(mOriginalModel, cursor, activity);
                        }
                        mView.setCalendarsCursor(matrixCursor, isAdded() && isResumed(), mModel.mCalendarId);
                    }
                    setModelIfDone(TOKEN_CALENDARS);
                    break;
                case TOKEN_COLORS:
                    try (cursor) {
                        if (cursor.moveToFirst()) {
                            EventColorCache cache = new EventColorCache();
                            do {
                                String colorKey = cursor.getString(EditEventHelper.COLORS_INDEX_COLOR_KEY);
                                int rawColor = cursor.getInt(EditEventHelper.COLORS_INDEX_COLOR);
                                int displayColor = Utils.getDisplayColorFromColor(activity, rawColor);
                                String accountName = cursor
                                        .getString(EditEventHelper.COLORS_INDEX_ACCOUNT_NAME);
                                String accountType = cursor
                                        .getString(EditEventHelper.COLORS_INDEX_ACCOUNT_TYPE);
                                cache.insertColor(accountName, accountType,
                                        displayColor, colorKey);
                            } while (cursor.moveToNext());
                            cache.sortPalettes(new HsvColorComparator());

                            mModel.mEventColorCache = cache;
                            mView.mColorPicker.setOnClickListener(mOnColorPickerClicked);
                        }
                    }

                    // If the account name/type is null, the calendar event colors cannot be
                    // determined, so take the default/savedInstanceState value.
                    if (mModel.mCalendarAccountName == null
                            || mModel.mCalendarAccountType == null) {
                        mView.setColorPickerButtonStates(mShowColorPalette);
                    } else {
                        mView.setColorPickerButtonStates(mModel.getCalendarEventColors());
                    }

                    setModelIfDone(TOKEN_COLORS);
                    break;
                case TOKEN_EXTENDED:
                    while(cursor.moveToNext()) {
                        String name = cursor.getString(EXTENDED_INDEX_NAME);
                        String value = cursor.getString(EXTENDED_INDEX_VALUE);
                        switch (name) {
                            case ExtendedProperty.URL_NAME:
                            case ExtendedProperty.URL_NAME_PRIV:
                                mModel.mUrl = value;
                                mOriginalModel.mUrl = value;
                                if (value != null) {
                                    mView.mUrlTextView.setTextKeepState(mModel.mUrl);
                                }
                                break;
                            case ExtendedProperty.EVENT_TYPE_NAME:
                                mModel.mSpecialType = ExtendedProperty.typeFromValue(value);
                                mOriginalModel.mSpecialType = mModel.mSpecialType;
                                break;
                        }
                    }

                    if (cursor != null) {
                        cursor.close();
                    }

                    setModelIfDone(TOKEN_EXTENDED);
                    break;
                default:
                    cursor.close();
                    break;
            }
        }
    }

    class Done implements EditEventHelper.EditDoneRunnable {
        private int mCode = -1;

        @Override
        public void setDoneCode(int code) {
            mCode = code;
        }

        @Override
        public void run() {
            // We only want this to get called once, either because the user
            // pressed back/home or one of the buttons on screen
            mSaveOnDetach = false;
            if (mModification == Utils.MODIFY_UNINITIALIZED) {
                // If this is uninitialized the user hit back, the only
                // changeable item is response to default to all events.
                mModification = Utils.MODIFY_ALL;
            }

            if ((mCode & Utils.DONE_SAVE) != 0 && mModel != null
                    && (EditEventHelper.canRespond(mModel)
                    || EditEventHelper.canModifyEvent(mModel))
                    && mView.prepareForSave()
                    && !isEmptyNewEvent()
                    && mModel.normalizeReminders()
                    && mHelper.saveEvent(mModel, mOriginalModel, mModification)) {
                int stringResource;
                if (!mModel.mAttendeesList.isEmpty()) {
                    if (mModel.mUri != null) {
                        stringResource = R.string.saving_event_with_guest;
                    } else {
                        stringResource = R.string.creating_event_with_guest;
                    }
                } else {
                    if (mModel.mUri != null) {
                        stringResource = R.string.saving_event;
                    } else {
                        stringResource = R.string.creating_event;
                    }
                }
                Toast.makeText(mActivity, stringResource, Toast.LENGTH_SHORT).show();
            } else if ((mCode & Utils.DONE_SAVE) != 0 && mModel != null && isEmptyNewEvent()) {
                Toast.makeText(mActivity, R.string.empty_event, Toast.LENGTH_SHORT).show();
            }

            if ((mCode & Utils.DONE_DELETE) != 0 && mOriginalModel != null
                    && EditEventHelper.canModifyCalendar(mOriginalModel)) {
                long begin = mModel.mStart;
                long end = mModel.mEnd;
                int which = -1;
                switch (mModification) {
                    case Utils.MODIFY_SELECTED:
                        which = DeleteEventHelper.DELETE_SELECTED;
                        break;
                    case Utils.MODIFY_ALL_FOLLOWING:
                        which = DeleteEventHelper.DELETE_ALL_FOLLOWING;
                        break;
                    case Utils.MODIFY_ALL:
                        which = DeleteEventHelper.DELETE_ALL;
                        break;
                }
                DeleteEventHelper deleteHelper = new DeleteEventHelper(
                        mActivity, mActivity, !mIsReadOnly /* exitWhenDone */);
                deleteHelper.delete(begin, end, mOriginalModel, which);
            }

            if ((mCode & Utils.DONE_EXIT) != 0) {
                // This will exit the edit event screen, should be called
                // when we want to return to the main calendar views
                if ((mCode & Utils.DONE_SAVE) != 0) {
                    if (mActivity != null) {
                        long start = mModel.mStart;
                        long end = mModel.mEnd;
                        if (mModel.mAllDay) {
                            // For allday events we want to go to the day in the
                            // user's current tz
                            String tz = Utils.getTimeZone(mActivity, null);
                            Time t = new Time(Time.TIMEZONE_UTC);
                            t.set(start);
                            t.setTimezone(tz);
                            start = t.toMillis();

                            t.setTimezone(Time.TIMEZONE_UTC);
                            t.set(end);
                            t.setTimezone(tz);
                            end = t.toMillis();
                        }
                        CalendarController.getInstance(mActivity).launchViewEvent(-1, start, end,
                                Attendees.ATTENDEE_STATUS_NONE);
                    }
                }
                Activity a = EditEventFragment.this.getActivity();
                if (a != null) {
                    a.finish();
                }
            }

            // Hide a software keyboard so that user won't see it even after this Fragment's
            // disappearing.
            final View focusedView = mActivity.getCurrentFocus();
            if (focusedView != null) {
                mInputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
            }
        }
    }
}

