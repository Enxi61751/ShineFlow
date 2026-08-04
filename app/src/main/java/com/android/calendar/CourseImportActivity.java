package com.android.calendar;

import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import com.android.calendar.icalendar.IcalendarUtils;
import com.android.calendar.icalendar.VCalendar;
import com.android.calendar.icalendar.VEvent;

/**
 * Imports a timetable image into a dedicated local calendar. OCR is deliberately only used to
 * create an editable draft: a recognition mistake must never write directly to CalendarProvider.
 */
public class CourseImportActivity extends Activity {
    private static final int REQUEST_PICK_IMAGE = 4101;
    private static final int REQUEST_PICK_FILE = 4102;
    private static final String TIMETABLE_CALENDAR = "Timetable";
    private static final String LOCAL_ACCOUNT = "Etar";
    private static final Pattern PERIOD_PATTERN = Pattern.compile("(?:第)?\\s*(\\d{1,2})(?:\\s*[-~至]\\s*(\\d{1,2}))?\\s*节?");
    private static final Pattern WEEK_RANGE_PATTERN = Pattern.compile("(\\d{1,2})\\s*[-~至]\\s*(\\d{1,2})\\s*周?");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("(?:星期|周)([一二三四五六日天])");
    private static final OkHttpClient HTTP = new OkHttpClient();

    private EditText drafts;
    private EditText firstMonday;
    private EditText startTime;
    private EditText periodLength;
    private EditText periodGap;
    private TextView imageStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course_import);
        setTitle(R.string.course_import_title);

        drafts = findViewById(R.id.course_drafts);
        firstMonday = findViewById(R.id.course_first_monday);
        startTime = findViewById(R.id.course_start_time);
        periodLength = findViewById(R.id.course_period_length);
        periodGap = findViewById(R.id.course_period_gap);
        imageStatus = findViewById(R.id.course_image_status);
        firstMonday.setText(defaultFirstMonday());
        startTime.setText("08:00");
        periodLength.setText("45");
        periodGap.setText("10");

        ((Button) findViewById(R.id.course_pick_image)).setOnClickListener(v -> pickImage());
        ((Button) findViewById(R.id.course_pick_file)).setOnClickListener(v -> pickFile());
        ((Button) findViewById(R.id.course_apply)).setOnClickListener(v -> importDrafts());
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_PICK_IMAGE && requestCode != REQUEST_PICK_FILE) || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_FILE) {
            importStructuredFile(data.getData());
            return;
        }
        imageStatus.setText(R.string.course_import_recognize);
        recognizeImage(data.getData());
    }

    private void importStructuredFile(Uri uri) {
        imageStatus.setText(R.string.course_import_recognize);
        new Thread(() -> {
            try {
                String name = displayName(uri).toLowerCase(Locale.US);
                String raw = readUri(uri);
                String parsed = name.contains(".ics") ? parseIcs(raw) : name.contains(".xlsx") ? parseXlsx(readUriBytes(uri)) : parseCsv(raw);
                runOnUiThread(() -> { drafts.setText(parsed); imageStatus.setText("OK"); if (TextUtils.isEmpty(parsed)) Toast.makeText(this, R.string.course_import_file_failed, Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.course_import_file_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("empty file");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private byte[] readUriBytes(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("empty file");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return uri.toString();
    }

    private String parseIcs(String content) {
        File file = new File(getCacheDir(), "course_import.ics");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes("UTF-8"));
            VCalendar calendar = IcalendarUtils.readCalendarFromFile(this, Uri.fromFile(file));
            if (calendar == null) return "";
            StringBuilder result = new StringBuilder();
            for (VEvent event : calendar.getAllEvents()) {
                String title = IcalendarUtils.uncleanseString(event.getProperty(VEvent.SUMMARY));
                String room = IcalendarUtils.uncleanseString(event.getProperty(VEvent.LOCATION));
                String description = IcalendarUtils.uncleanseString(event.getProperty(VEvent.DESCRIPTION));
                String[] dayPeriod = inferDayAndPeriod(event.getProperty(VEvent.DTSTART));
                result.append(cleanCourseName(title)).append(" |  | ").append(csvEscape(description)).append(" | ")
                        .append(csvEscape(room)).append(" | ").append(dayPeriod[0]).append(" | ").append(dayPeriod[1]).append(" | 1-16 | 全部\n");
            }
            return result.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private String[] inferDayAndPeriod(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            Date date = format.parse(value == null ? "" : value.replace("Z", ""));
            Calendar calendar = Calendar.getInstance(); calendar.setTime(date);
            int weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1; if (weekday == 0) weekday = 7;
            int period = Math.max(1, calendar.get(Calendar.HOUR_OF_DAY) - 7);
            return new String[]{weekdayName(weekday), String.valueOf(period)};
        } catch (Exception e) { return new String[]{"周一", "1"}; }
    }

    private String parseCsv(String content) {
        StringBuilder result = new StringBuilder();
        for (String line : content.split("\\r?\\n")) {
            if (TextUtils.isEmpty(line.trim())) continue;
            List<String> columns = splitCsvLine(line);
            if (columns.size() < 5 || columns.get(0).trim().equalsIgnoreCase("course")) continue;
            while (columns.size() < 9) columns.add("");
            if (columns.get(4).trim().isEmpty()) columns.set(4, "周一");
            if (columns.get(5).trim().isEmpty()) columns.set(5, "1");
            if (columns.get(6).trim().isEmpty()) columns.set(6, "1-16");
            if (columns.get(7).trim().isEmpty()) columns.set(7, "全部");
            for (int i = 0; i < 9; i++) result.append(columns.get(i).trim()).append(i == 8 ? '\n' : '|');
        }
        return result.toString().trim();
    }

    private List<String> splitCsvLine(String line) {
        List<String> result = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c == '"') quoted = !quoted; else if (c == ',' && !quoted) { result.add(value.toString()); value.setLength(0); } else value.append(c); }
        result.add(value.toString()); return result;
    }

    private String parseXlsx(byte[] bytes) {
        try {
            ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes)); String shared = "", sheet = ""; ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                String value = output.toString("UTF-8");
                if (entry.getName().equals("xl/sharedStrings.xml")) shared = value;
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) sheet = value;
            }
            List<String> sharedValues = new ArrayList<>(); Matcher text = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(shared);
            while (text.find()) sharedValues.add(text.group(1).replace("&amp;", "&"));
            StringBuilder result = new StringBuilder(); Matcher rows = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(sheet);
            while (rows.find()) {
                Matcher cells = Pattern.compile("<c[^>]*>.*?<v>(.*?)</v>.*?</c>", Pattern.DOTALL).matcher(rows.group(1)); List<String> values = new ArrayList<>();
                while (cells.find()) { String value = cells.group(1); try { values.add(sharedValues.get(Integer.parseInt(value))); } catch (Exception e) { values.add(value); } }
                if (!values.isEmpty()) result.append(parseCsv(joinValues(values))).append('\n');
            }
            return result.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private String csvEscape(String value) { return value == null ? "" : value.replace("|", "/").replace("\n", " "); }
    private String joinValues(List<String> values) { StringBuilder result = new StringBuilder(); for (String value : values) result.append(value.replace(",", " ")).append(','); if (result.length() > 0) result.setLength(result.length() - 1); return result.toString(); }

    private void recognizeImage(Uri uri) {
        new Thread(() -> {
            File image = copyToCache(uri);
            if (image == null) {
                showOcrFailure();
                return;
            }
            String baseUrl = BuildConfig.OCR_BASE_URL;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            RequestBody imageBody = RequestBody.create(MediaType.parse("image/*"), image);
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", image.getName(), imageBody).build();
            Request request = new Request.Builder().url(baseUrl + "ocr").post(body).build();
            HTTP.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { showOcrFailure(); }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    String result = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) { showOcrFailure(); return; }
                    final String parsed = buildDraftsFromOcr(result);
                    runOnUiThread(() -> {
                        if (TextUtils.isEmpty(parsed)) {
                            Toast.makeText(CourseImportActivity.this, R.string.course_import_ocr_failed, Toast.LENGTH_LONG).show();
                        } else {
                            drafts.setText(parsed);
                            imageStatus.setText(getString(R.string.course_import_recognize) + " ✓");
                        }
                    });
                }
            });
        }).start();
    }

    private File copyToCache(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            File target = new File(getCacheDir(), "timetable_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private void showOcrFailure() {
        runOnUiThread(() -> Toast.makeText(this, R.string.course_import_ocr_failed, Toast.LENGTH_LONG).show());
    }

    /** Uses OCR boxes to assign text to weekday columns and period rows. */
    private String buildDraftsFromOcr(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject data = root.optJSONObject("data");
            JSONArray lines = data == null ? null : data.optJSONArray("lines");
            if (lines == null) return "";
            List<OcrLine> all = new ArrayList<>();
            for (int i = 0; i < lines.length(); i++) {
                JSONObject item = lines.optJSONObject(i);
                if (item == null) continue;
                String text = item.optString("text").trim();
                JSONArray box = item.optJSONArray("box");
                if (TextUtils.isEmpty(text) || box == null || box.length() < 8) continue;
                float x = 0, y = 0;
                for (int p = 0; p < 8; p += 2) { x += (float) box.getDouble(p); y += (float) box.getDouble(p + 1); }
                all.add(new OcrLine(text, x / 4f, y / 4f));
            }
            Map<Integer, Float> weekdayXs = new HashMap<>();
            for (OcrLine line : all) {
                int weekday = weekdayFromText(line.text);
                if (weekday > 0) weekdayXs.put(weekday, line.x);
            }
            if (weekdayXs.isEmpty()) return plainTextAsDraft(all);

            Map<Integer, Float> periodYs = new HashMap<>();
            float leftEdge = Collections.min(weekdayXs.values());
            for (OcrLine line : all) {
                Matcher period = PERIOD_PATTERN.matcher(line.text);
                if (line.x < leftEdge && period.find()) periodYs.put(Integer.parseInt(period.group(1)), line.y);
            }
            Map<String, List<OcrLine>> cells = new HashMap<>();
            for (OcrLine line : all) {
                if (weekdayFromText(line.text) > 0 || line.x < leftEdge) continue;
                int weekday = closestKey(weekdayXs, line.x);
                int period = periodYs.isEmpty() ? 1 : closestKey(periodYs, line.y);
                String key = weekday + ":" + period;
                List<OcrLine> cell = cells.get(key);
                if (cell == null) { cell = new ArrayList<>(); cells.put(key, cell); }
                cell.add(line);
            }
            List<String> keys = new ArrayList<>(cells.keySet());
            Collections.sort(keys);
            StringBuilder output = new StringBuilder();
            for (String key : keys) {
                List<OcrLine> cell = cells.get(key);
                Collections.sort(cell, Comparator.comparingDouble(item -> item.y));
                StringBuilder text = new StringBuilder();
                for (OcrLine line : cell) text.append(line.text).append(' ');
                String[] keyParts = key.split(":");
                int weekday = Integer.parseInt(keyParts[0]);
                int period = Integer.parseInt(keyParts[1]);
                String course = text.toString().trim();
                if (course.length() < 2) continue;
                output.append(cleanCourseName(course)).append(" |  |  |  | ")
                        .append(weekdayName(weekday)).append(" | ").append(period).append(" | ")
                        .append(weekRange(course)).append(" | ").append(weekPattern(course)).append('\n');
            }
            return output.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String plainTextAsDraft(List<OcrLine> lines) {
        Collections.sort(lines, Comparator.comparingDouble(item -> item.y));
        StringBuilder result = new StringBuilder();
        for (OcrLine line : lines) {
            if (line.text.length() > 1) result.append(line.text).append(" |  |  |  | 周一 | 1 | 1-16 | 全部\n");
        }
        return result.toString().trim();
    }

    private int closestKey(Map<Integer, Float> points, float value) {
        int result = 1;
        float distance = Float.MAX_VALUE;
        for (Map.Entry<Integer, Float> entry : points.entrySet()) {
            float current = Math.abs(entry.getValue() - value);
            if (current < distance) { distance = current; result = entry.getKey(); }
        }
        return result;
    }

    private int weekdayFromText(String text) {
        Matcher matcher = WEEKDAY_PATTERN.matcher(text);
        if (!matcher.find()) return 0;
        return "一二三四五六日天".indexOf(matcher.group(1)) + 1;
    }

    private String weekdayName(int weekday) { return "周" + "一二三四五六日".charAt(weekday - 1); }
    private String weekRange(String value) {
        Matcher matcher = WEEK_RANGE_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) + "-" + matcher.group(2) : "1-16";
    }
    private String weekPattern(String value) { return value.contains("单周") ? "单周" : value.contains("双周") ? "双周" : "全部"; }
    private String cleanCourseName(String value) {
        return value.replaceAll("(?:第)?\\s*\\d{1,2}(?:\\s*[-~至]\\s*\\d{1,2})?\\s*节?", "")
                .replaceAll("\\d{1,2}\\s*[-~至]\\s*\\d{1,2}\\s*周?", "").trim();
    }

    private void importDrafts() {
        final List<CourseDraft> courses = parseDrafts(drafts.getText().toString());
        if (courses.isEmpty()) { Toast.makeText(this, R.string.course_import_no_courses, Toast.LENGTH_SHORT).show(); return; }
        final Date termStart = parseDate(firstMonday.getText().toString());
        if (termStart == null) {
            Toast.makeText(this, getString(R.string.course_import_error, "invalid term start date"), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                long calendarId = getOrCreateTimetableCalendar();
                ArrayList<ContentProviderOperation> operations = new ArrayList<>();
                for (CourseDraft course : courses) addCourseOperations(operations, calendarId, termStart, course);
                getContentResolver().applyBatch(CalendarContract.AUTHORITY, operations);
                runOnUiThread(() -> { Toast.makeText(this, getString(R.string.course_import_success, operations.size()), Toast.LENGTH_LONG).show(); finish(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.course_import_error, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private List<CourseDraft> parseDrafts(String source) {
        List<CourseDraft> result = new ArrayList<>();
        for (String line : source.split("\\r?\\n")) {
            String[] values = line.split("\\|", -1);
            if (values.length < 7 || TextUtils.isEmpty(values[0].trim())) continue;
            int weekday = weekdayFromText(values[4]);
            Matcher periods = PERIOD_PATTERN.matcher(values[5]);
            if (weekday == 0 || !periods.find()) continue;
            int startPeriod = Integer.parseInt(periods.group(1));
            int endPeriod = periods.group(2) == null ? startPeriod : Integer.parseInt(periods.group(2));
            Set<Integer> weeks = parseWeeks(values[6]);
            if (weeks.isEmpty()) continue;
            String mode = values.length > 7 ? values[7].trim() : "全部";
            Set<String> exclusions = values.length > 8 ? parseExclusions(values[8]) : new HashSet<>();
            result.add(new CourseDraft(values[0].trim(), values[1].trim(), values[2].trim(), values[3].trim(), weekday,
                    startPeriod, endPeriod, weeks, mode, exclusions));
        }
        return result;
    }

    private Set<Integer> parseWeeks(String source) {
        Set<Integer> result = new HashSet<>();
        for (String part : source.split("[,，]")) {
            Matcher range = WEEK_RANGE_PATTERN.matcher(part);
            if (range.find()) {
                int start = Integer.parseInt(range.group(1)), end = Integer.parseInt(range.group(2));
                for (int i = start; i <= end; i++) result.add(i);
            } else {
                try { result.add(Integer.parseInt(part.trim().replace("周", ""))); } catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }

    private Set<String> parseExclusions(String source) {
        Set<String> result = new HashSet<>();
        for (String value : source.split("[,，]")) if (!TextUtils.isEmpty(value.trim())) result.add(value.trim());
        return result;
    }

    private void addCourseOperations(ArrayList<ContentProviderOperation> operations, long calendarId,
                                     Date termStart, CourseDraft course) {
        Set<Integer> selected = new HashSet<>();
        for (Integer week : course.weeks) {
            if (("单周".equals(course.mode) && week % 2 == 0) || ("双周".equals(course.mode) && week % 2 != 0)) continue;
            selected.add(week);
        }
        // Simple continuous sequences can use RRULE. Gaps/exclusions become individual events.
        if (course.exclusions.isEmpty() && isArithmeticSequence(selected, "单周".equals(course.mode) || "双周".equals(course.mode) ? 2 : 1)) {
            int first = Collections.min(selected), last = Collections.max(selected);
            Date start = eventTime(termStart, course.weekday, first, course.startPeriod);
            Date end = eventEndTime(termStart, course.weekday, first, course.endPeriod);
            ContentValues values = eventValues(calendarId, course, start, end);
            int interval = "单周".equals(course.mode) || "双周".equals(course.mode) ? 2 : 1;
            values.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;INTERVAL=" + interval + ";COUNT=" + selected.size());
            operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build());
        } else {
            for (Integer week : selected) {
                Date start = eventTime(termStart, course.weekday, week, course.startPeriod);
                if (course.exclusions.contains(formatDate(start))) continue;
                Date end = eventEndTime(termStart, course.weekday, week, course.endPeriod);
                operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                        .withValues(eventValues(calendarId, course, start, end)).build());
            }
        }
    }

    private boolean isArithmeticSequence(Set<Integer> values, int interval) {
        if (values.isEmpty()) return false;
        List<Integer> sorted = new ArrayList<>(values); Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) if (sorted.get(i) - sorted.get(i - 1) != interval) return false;
        return true;
    }

    private ContentValues eventValues(long calendarId, CourseDraft course, Date start, Date end) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, course.name);
        values.put(CalendarContract.Events.EVENT_LOCATION, course.room);
        values.put(CalendarContract.Events.DESCRIPTION, "Course code: " + course.code + "\nTeacher: " + course.teacher + "\nWeeks: " + course.weeks);
        values.put(CalendarContract.Events.DTSTART, start.getTime());
        values.put(CalendarContract.Events.DTEND, end.getTime());
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        values.put(CalendarContract.Events.HAS_ALARM, 0);
        return values;
    }

    private Date eventTime(Date termStart, int weekday, int week, int period) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(termStart);
        calendar.add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + weekday - 1);
        String[] time = startTime.getText().toString().trim().split(":");
        int hour = time.length == 2 ? Integer.parseInt(time[0]) : 8;
        int minute = time.length == 2 ? Integer.parseInt(time[1]) : 0;
        int duration = safeInt(periodLength, 45), gap = safeInt(periodGap, 10);
        int offset = (period - 1) * (duration + gap);
        calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.MINUTE, offset);
        return calendar.getTime();
    }

    private Date eventEndTime(Date termStart, int weekday, int week, int period) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(eventTime(termStart, weekday, week, period));
        calendar.add(Calendar.MINUTE, safeInt(periodLength, 45));
        return calendar.getTime();
    }

    private int safeInt(EditText view, int fallback) { try { return Integer.parseInt(view.getText().toString()); } catch (NumberFormatException e) { return fallback; } }
    private Date parseDate(String value) { try { SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US); format.setLenient(false); return format.parse(value); } catch (Exception e) { return null; } }
    private String formatDate(Date value) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(value); }
    private String defaultFirstMonday() { Calendar c = Calendar.getInstance(); while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_YEAR, -1); return formatDate(c.getTime()); }

    private long getOrCreateTimetableCalendar() {
        ContentResolver resolver = getContentResolver();
        String selection = CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=?";
        try (Cursor cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI, new String[]{CalendarContract.Calendars._ID}, selection,
                new String[]{TIMETABLE_CALENDAR, CalendarContract.ACCOUNT_TYPE_LOCAL}, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT);
        values.put(CalendarContract.Calendars.NAME, "shineflow_timetable");
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, TIMETABLE_CALENDAR);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(63, 81, 181));
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        Uri syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        Uri created = resolver.insert(syncUri, values);
        if (created == null) throw new IllegalStateException("Unable to create timetable calendar");
        return Long.parseLong(created.getLastPathSegment());
    }

    private static final class OcrLine { final String text; final float x, y; OcrLine(String text, float x, float y) { this.text = text; this.x = x; this.y = y; } }
    private static final class CourseDraft {
        final String name, code, teacher, room, mode; final int weekday, startPeriod, endPeriod; final Set<Integer> weeks; final Set<String> exclusions;
        CourseDraft(String name, String code, String teacher, String room, int weekday, int startPeriod, int endPeriod, Set<Integer> weeks, String mode, Set<String> exclusions) {
            this.name = name; this.code = code; this.teacher = teacher; this.room = room; this.weekday = weekday; this.startPeriod = startPeriod; this.endPeriod = endPeriod; this.weeks = weeks; this.mode = mode; this.exclusions = exclusions;
        }
    }
}
