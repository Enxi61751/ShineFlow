/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *     Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.calendar;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class LunarUtils {
    private static final String TAG = "LunarUtils";

    // The flags used for get the lunar info.
    public static final int FORMAT_LUNAR_LONG = 0x00001;
    public static final int FORMAT_LUNAR_SHORT = 0x00002;
    public static final int FORMAT_ONE_FESTIVAL = 0x00004;
    public static final int FORMAT_MULTI_FESTIVAL = 0x00008;
    public static final int FORMAT_ANIMAL = 0x00010;

    private static final String INFO_SEPARATE = " ";
    private static final String MORE_FESTIVAL_SUFFIX = "*";

    private static HashMap<String, LunarInfo> sLunarInfos = new HashMap<String, LunarInfo>();

    /**
     * If need show the lunar info now. As default, it will need shown if the current
     * language is zh-cn.
     */
    // Preference key controlling whether the lunar (阴历) calendar is shown
    // alongside the Gregorian (阳历) calendar. ShineFlow only supports these
    // two calendar types.
    public static final String KEY_SHOW_LUNAR = "preferences_show_lunar";

    public static boolean showLunar(Context context) {
        // Lunar (阴历) is shown by default and can be toggled off in Settings.
        return Utils.getSharedPreference(context, KEY_SHOW_LUNAR, true);
    }

    /**
     * Used to clear the saved info.
     */
    public static void clearInfo() {
        Log.i(TAG, "Clear all the saved info.");
        sLunarInfos.clear();
    }

    /**
     * Used to get the lunar, festival and animal info of the date. Before you call this
     * function to get the info, you need make sure already load the info by calling
     * {@link LunarInfoLoader#load} to pre-load them.
     * @param format Format which info need append to the result.
     *     The format {@link #FORMAT_LUNAR_LONG} and {@link #FORMAT_LUNAR_SHORT},
     *     {@link #FORMAT_ONE_FESTIVAL} and {@link #FORMAT_MULTI_FESTIVAL} could not
     *     selected at once.
     * @param showLunarBeforeFestival If the festival is exist for the date, if need append the
     *     lunar info before the festival info.
     * @param result [out] The result will be saved in this list as your given format.
     * @return The result as string for your given format.
     */
    public static String get(Context context, int year, int month, int day, int format,
            boolean showLunarBeforeFestival, ArrayList<String> result) {
        if (context == null || format < FORMAT_LUNAR_LONG) return null;

        String res = null;

        // Try to find the matched lunar info from the hash map.
        String key = getKey(year, month, day);
        LunarInfo info = sLunarInfos.get(key);
        if (info == null) {
            // ShineFlow: compute locally instead of relying on the (usually
            // absent) Qualcomm lunar content provider.
            info = computeLunarInfo(year, month, day);
            if (info != null) {
                sLunarInfos.put(key, info);
            }
        }
        if (info != null) {
            res = buildInfo(info, format, showLunarBeforeFestival, result);
        } else {
            Log.d(TAG, "Couldn't get the lunar info for " + key);
        }

        return res;
    }

    // ==================== ShineFlow local lunar calendar ====================
    // Self-contained Chinese lunar calendar for years 1900-2100. Each entry of
    // LUNAR_INFO packs: bits 15..4 = big(30)/small(29) flag per month 1..12,
    // bit 16 = leap-month big/small, bits 3..0 = which month is leap (0 = none).

    private static final int[] LUNAR_INFO = {
        0x04bd8,0x04ae0,0x0a570,0x054d5,0x0d260,0x0d950,0x16554,0x056a0,0x09ad0,0x055d2,
        0x04ae0,0x0a5b6,0x0a4d0,0x0d250,0x1d255,0x0b540,0x0d6a0,0x0ada2,0x095b0,0x14977,
        0x04970,0x0a4b0,0x0b4b5,0x06a50,0x06d40,0x1ab54,0x02b60,0x09570,0x052f2,0x04970,
        0x06566,0x0d4a0,0x0ea50,0x06e95,0x05ad0,0x02b60,0x186e3,0x092e0,0x1c8d7,0x0c950,
        0x0d4a0,0x1d8a6,0x0b550,0x056a0,0x1a5b4,0x025d0,0x092d0,0x0d2b2,0x0a950,0x0b557,
        0x06ca0,0x0b550,0x15355,0x04da0,0x0a5b0,0x14573,0x052b0,0x0a9a8,0x0e950,0x06aa0,
        0x0aea6,0x0ab50,0x04b60,0x0aae4,0x0a570,0x05260,0x0f263,0x0d950,0x05b57,0x056a0,
        0x096d0,0x04dd5,0x04ad0,0x0a4d0,0x0d4d4,0x0d250,0x0d558,0x0b540,0x0b6a0,0x195a6,
        0x095b0,0x049b0,0x0a974,0x0a4b0,0x0b27a,0x06a50,0x06d40,0x0af46,0x0ab60,0x09570,
        0x04af5,0x04970,0x064b0,0x074a3,0x0ea50,0x06b58,0x055c0,0x0ab60,0x096d5,0x092e0,
        0x0c960,0x0d954,0x0d4a0,0x0da50,0x07552,0x056a0,0x0abb7,0x025d0,0x092d0,0x0cab5,
        0x0a950,0x0b4a0,0x0baa4,0x0ad50,0x055d9,0x04ba0,0x0a5b0,0x15176,0x052b0,0x0a930,
        0x07954,0x06aa0,0x0ad50,0x05b52,0x04b60,0x0a6e6,0x0a4e0,0x0d260,0x0ea65,0x0d530,
        0x05aa0,0x076a3,0x096d0,0x04afb,0x04ad0,0x0a4d0,0x1d0b6,0x0d250,0x0d520,0x0dd45,
        0x0b5a0,0x056d0,0x055b2,0x049b0,0x0a577,0x0a4b0,0x0aa50,0x1b255,0x06d20,0x0ada0,
        0x14b63,0x09370,0x049f8,0x04970,0x064b0,0x168a6,0x0ea50,0x06b20,0x1a6c4,0x0aae0,
        0x0a2e0,0x0d2e3,0x0c960,0x0d557,0x0d4a0,0x0da50,0x05d55,0x056a0,0x0a6d0,0x055d4,
        0x052d0,0x0a9b8,0x0a950,0x0b4a0,0x0b6a6,0x0ad50,0x055a0,0x0aba4,0x0a5b0,0x052b0,
        0x0b273,0x06930,0x07337,0x06aa0,0x0ad50,0x14b55,0x04b60,0x0a570,0x054e4,0x0d160,
        0x0e968,0x0d520,0x0daa0,0x16aa6,0x056d0,0x04ae0,0x0a9d4,0x0a2d0,0x0d150,0x0f252,
        0x0d520
    };

    private static final String[] LUNAR_MONTHS = {
        "正","二","三","四","五","六",
        "七","八","九","十","冬","腊"};
    private static final String[] LUNAR_DAY_TENS = {"初","十","廿","卅"};
    private static final String[] LUNAR_DAY_DIGITS = {
        "","一","二","三","四","五","六","七","八","九","十"};
    private static final String[] ZODIAC = {
        "鼠","牛","虎","兔","龙","蛇",
        "马","羊","猴","鸡","狗","猪"};

    private static int leapMonth(int y) {
        return LUNAR_INFO[y - 1900] & 0xf;
    }

    private static int leapDays(int y) {
        if (leapMonth(y) != 0) {
            return ((LUNAR_INFO[y - 1900] & 0x10000) != 0) ? 30 : 29;
        }
        return 0;
    }

    private static int monthDays(int y, int m) {
        return ((LUNAR_INFO[y - 1900] & (0x10000 >> m)) != 0) ? 30 : 29;
    }

    private static int lunarYearDays(int y) {
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            sum += ((LUNAR_INFO[y - 1900] & i) != 0) ? 1 : 0;
        }
        return sum + leapDays(y);
    }

    private static String lunarDayName(int d) {
        switch (d) {
            case 10: return "初十"; // 初十
            case 20: return "二十"; // 二十
            case 30: return "三十"; // 三十
            default: return LUNAR_DAY_TENS[d / 10] + LUNAR_DAY_DIGITS[d % 10];
        }
    }

    // month here is 0-based (0-11), matching Time.getMonth()/callers.
    private static LunarInfo computeLunarInfo(int year, int month, int day) {
        if (year < 1900 || year > 2100) {
            return null;
        }
        java.util.Calendar base = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        base.clear();
        base.set(1900, 0, 31);
        java.util.Calendar obj = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        obj.clear();
        obj.set(year, month, day);

        long offset = Math.round((obj.getTimeInMillis() - base.getTimeInMillis()) / 86400000.0);

        int temp = 0;
        int lunarYear = 1900;
        for (; lunarYear < 2101 && offset > 0; lunarYear++) {
            temp = lunarYearDays(lunarYear);
            offset -= temp;
        }
        if (offset < 0) {
            offset += temp;
            lunarYear--;
        }

        int leap = leapMonth(lunarYear);
        boolean isLeap = false;
        int lunarMonth = 1;
        for (; lunarMonth < 13 && offset > 0; lunarMonth++) {
            if (leap > 0 && lunarMonth == (leap + 1) && !isLeap) {
                --lunarMonth;
                isLeap = true;
                temp = leapDays(lunarYear);
            } else {
                temp = monthDays(lunarYear, lunarMonth);
            }
            if (isLeap && lunarMonth == (leap + 1)) {
                isLeap = false;
            }
            offset -= temp;
        }
        if (offset == 0 && leap > 0 && lunarMonth == leap + 1) {
            if (isLeap) {
                isLeap = false;
            } else {
                isLeap = true;
                --lunarMonth;
            }
        }
        if (offset < 0) {
            offset += temp;
            --lunarMonth;
        }
        int lunarDay = (int) offset + 1;

        LunarInfo info = new LunarInfo();
        String monthName = (isLeap ? "闰" : "") + LUNAR_MONTHS[lunarMonth - 1] + "月"; // 闰..月
        String dayName = lunarDayName(lunarDay);
        info._label_short = (lunarDay == 1) ? monthName : dayName;
        info._label_long = monthName + dayName;
        info._animal = ZODIAC[((lunarYear - 4) % 12 + 12) % 12];

        // Festivals (up to 4).
        java.util.ArrayList<String> fest = new java.util.ArrayList<>();
        String lunarFestival = lunarFestival(lunarYear, lunarMonth, lunarDay, isLeap);
        if (lunarFestival != null) {
            fest.add(lunarFestival);
        }
        String solarFestival = solarFestival(month + 1, day);
        if (solarFestival != null) {
            fest.add(solarFestival);
        }
        if (fest.size() > 0) info._festival1 = fest.get(0);
        if (fest.size() > 1) info._festival2 = fest.get(1);
        if (fest.size() > 2) info._festival3 = fest.get(2);
        if (fest.size() > 3) info._festival4 = fest.get(3);

        return info;
    }

    private static String lunarFestival(int lYear, int lMonth, int lDay, boolean isLeap) {
        if (isLeap) {
            return null;
        }
        // 除夕 = last day of the 12th lunar month
        if (lMonth == 12 && lDay == monthDays(lYear, 12)) {
            return "除夕"; // 除夕
        }
        switch (lMonth + "-" + lDay) {
            case "1-1":   return "春节";       // 春节
            case "1-15":  return "元宵节"; // 元宵节
            case "2-2":   return "龙抬头"; // 龙抬头
            case "5-5":   return "端午节"; // 端午节
            case "7-7":   return "七夕";       // 七夕
            case "7-15":  return "中元节"; // 中元节
            case "8-15":  return "中秋节"; // 中秋节
            case "9-9":   return "重阳节"; // 重阳节
            case "12-8":  return "腊八节"; // 腊八节
            case "12-23": return "小年";       // 小年
            default:      return null;
        }
    }

    private static String solarFestival(int sMonth, int sDay) {
        switch (sMonth + "-" + sDay) {
            case "1-1":   return "元旦";           // 元旦
            case "2-14":  return "情人节";     // 情人节
            case "3-8":   return "妇女节";     // 妇女节
            case "3-12":  return "植树节";     // 植树节
            case "4-1":   return "愚人节";     // 愚人节
            case "5-1":   return "劳动节";     // 劳动节
            case "5-4":   return "青年节";     // 青年节
            case "6-1":   return "儿童节";     // 儿童节
            case "7-1":   return "建党节";     // 建党节
            case "8-1":   return "建军节";     // 建军节
            case "9-10":  return "教师节";     // 教师节
            case "10-1":  return "国庆节";     // 国庆节
            case "12-24": return "平安夜";     // 平安夜
            case "12-25": return "圣诞节";     // 圣诞节
            default:      return null;
        }
    }

    private static String getKey(int year, int month, int day) {
        return year + "-" + month + "-" + day;
    }

    private static String buildInfo(LunarInfo info, int format, boolean showLunarBeforeFestival,
            ArrayList<String> list) {
        if (info == null || format < FORMAT_LUNAR_LONG) return null;

        StringBuilder result = new StringBuilder();

        if (showLunarBeforeFestival || TextUtils.isEmpty(info._festival1)) {
            // The format should not support long and short at one time.
            if ((format & FORMAT_LUNAR_LONG) == FORMAT_LUNAR_LONG) {
                appendInfo(result, info._label_long, list);
            } else if ((format & FORMAT_LUNAR_SHORT) == FORMAT_LUNAR_SHORT) {
                appendInfo(result, info._label_short, list);
            }
        }

        // The format should not support only one festival and multiple festivals.
        if ((format & FORMAT_ONE_FESTIVAL) == FORMAT_ONE_FESTIVAL) {
            String festival = info._festival1;
            if (!TextUtils.isEmpty(info._festival2)) {
                festival = festival + MORE_FESTIVAL_SUFFIX;
            }
            appendInfo(result, festival, list);
        } else if ((format & FORMAT_MULTI_FESTIVAL) == FORMAT_MULTI_FESTIVAL) {
            appendInfo(result, info._festival1, list);
            appendInfo(result, info._festival2, list);
            appendInfo(result, info._festival3, list);
            appendInfo(result, info._festival4, list);
        }

        if ((format & FORMAT_ANIMAL) == FORMAT_ANIMAL) {
            appendInfo(result, info._animal, list);
        }

        return result.toString();
    }

    private static void appendInfo(StringBuilder builder, String info, ArrayList<String> list) {
        if (builder == null || TextUtils.isEmpty(info)) return;

        String prefix = builder.length() > 0 ? INFO_SEPARATE : "";
        builder.append(prefix).append(info);

        if (list != null) list.add(info);
    }

    public static class LunarInfoLoader extends AsyncTaskLoader<Void> {
        private static final Uri CONTENT_URI_GET_ONE_DAY =
                Uri.parse("content://com.qualcomm.qti.lunarinfo/one_day");
        private static final Uri CONTENT_URI_GET_ONE_MONTH =
                Uri.parse("content://com.qualcomm.qti.lunarinfo/one_month");
        private static final Uri CONTENT_URI_GET_FROM_TO =
                Uri.parse("content://com.qualcomm.qti.lunarinfo/from_to");

        // The query parameters used to get lunar info.
        private static final String PARAM_YEAR = "year";
        private static final String PARAM_MONTH = "month";
        private static final String PARAM_DAY = "day";
        private static final String PARAM_FROM_YEAR = "from_year";
        private static final String PARAM_FROM_MONTH = "from_month";
        private static final String PARAM_FROM_DAY = "from_day";
        private static final String PARAM_TO_YEAR = "to_year";
        private static final String PARAM_TO_MONTH = "to_month";
        private static final String PARAM_TO_DAY = "to_day";

        // The columns for result.
        private static final String COL_ID = "_id";
        private static final String COL_YEAR = "year";
        private static final String COL_MONTH = "month";
        private static final String COL_DAY = "day";
        private static final String COL_LUNAR_LABEL_LONG = "lunar_label_long";
        private static final String COL_LUNAR_LABEL_SHORT = "lunar_label_short";
        private static final String COL_ANIMAL = "animal";
        private static final String COL_FESTIVAL_1 = "festival_1";
        private static final String COL_FESTIVAL_2 = "festival_2";
        private static final String COL_FESTIVAL_3 = "festival_3";
        private static final String COL_FESTIVAL_4 = "festival_4";

        private static int sIndexId = -1;
        private static int sIndexYear = -1;
        private static int sIndexMonth = -1;
        private static int sIndexDay = -1;
        private static int sIndexLunarLabelLong = -1;
        private static int sIndexLunarLabelShort = -1;
        private static int sIndexAnimal = -1;
        private static int sIndexFestival1 = -1;
        private static int sIndexFestival2 = -1;
        private static int sIndexFestival3 = -1;
        private static int sIndexFestival4 = -1;

        private Uri mUri;

        public LunarInfoLoader(Context context) {
            super(context);
        }

        public void load(int year, int month, int day) {
            reset();
            // Build the query uri.
            mUri = CONTENT_URI_GET_ONE_DAY.buildUpon()
                    .appendQueryParameter(PARAM_YEAR, String.valueOf(year))
                    .appendQueryParameter(PARAM_MONTH, String.valueOf(month))
                    .appendQueryParameter(PARAM_DAY, String.valueOf(day))
                    .build();
            startLoading();
            forceLoad();
        }

        public void load(int year, int month) {
            reset();
            // Build the query uri.
            mUri = CONTENT_URI_GET_ONE_MONTH.buildUpon()
                    .appendQueryParameter(PARAM_YEAR, String.valueOf(year))
                    .appendQueryParameter(PARAM_MONTH, String.valueOf(month))
                    .build();
            startLoading();
            forceLoad();
        }

        public void load(int from_year, int from_month, int from_day,
                int to_year, int to_month, int to_day) {
            reset();
            // Build the query uri.
            mUri = CONTENT_URI_GET_FROM_TO.buildUpon()
                    .appendQueryParameter(PARAM_FROM_YEAR, String.valueOf(from_year))
                    .appendQueryParameter(PARAM_FROM_MONTH, String.valueOf(from_month))
                    .appendQueryParameter(PARAM_FROM_DAY, String.valueOf(from_day))
                    .appendQueryParameter(PARAM_TO_YEAR, String.valueOf(to_year))
                    .appendQueryParameter(PARAM_TO_MONTH, String.valueOf(to_month))
                    .appendQueryParameter(PARAM_TO_DAY, String.valueOf(to_day))
                    .build();
            startLoading();
            forceLoad();
        }

        @Override
        public Void loadInBackground() {
            Cursor cursor = getContext().getContentResolver().query(mUri, null, null, null, null);
            try {
                if (cursor == null || cursor.getCount() < 1) return null;

                if (sIndexId < 0) getIndexValue(cursor);
                while (cursor.moveToNext()) {
                    int year = cursor.getInt(sIndexYear);
                    int month = cursor.getInt(sIndexMonth);
                    int day = cursor.getInt(sIndexDay);

                    LunarInfo info = new LunarInfo();
                    info._label_long = cursor.getString(sIndexLunarLabelLong);
                    info._label_short = cursor.getString(sIndexLunarLabelShort);
                    info._animal = cursor.getString(sIndexAnimal);
                    info._festival1 = cursor.getString(sIndexFestival1);
                    info._festival2 = cursor.getString(sIndexFestival2);
                    info._festival3 = cursor.getString(sIndexFestival3);
                    info._festival4 = cursor.getString(sIndexFestival4);

                    sLunarInfos.put(getKey(year, month, day), info);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            return null;
        }

        private void getIndexValue(Cursor cursor) {
            if (cursor == null) return;

            sIndexId = cursor.getColumnIndexOrThrow(COL_ID);
            sIndexYear = cursor.getColumnIndexOrThrow(COL_YEAR);
            sIndexMonth = cursor.getColumnIndexOrThrow(COL_MONTH);
            sIndexDay = cursor.getColumnIndexOrThrow(COL_DAY);
            sIndexLunarLabelLong = cursor.getColumnIndexOrThrow(COL_LUNAR_LABEL_LONG);
            sIndexLunarLabelShort = cursor.getColumnIndexOrThrow(COL_LUNAR_LABEL_SHORT);
            sIndexAnimal = cursor.getColumnIndexOrThrow(COL_ANIMAL);
            sIndexFestival1 = cursor.getColumnIndexOrThrow(COL_FESTIVAL_1);
            sIndexFestival2 = cursor.getColumnIndexOrThrow(COL_FESTIVAL_2);
            sIndexFestival3 = cursor.getColumnIndexOrThrow(COL_FESTIVAL_3);
            sIndexFestival4 = cursor.getColumnIndexOrThrow(COL_FESTIVAL_4);
        }

    }

    private static class LunarInfo {
        public String _label_long;
        public String _label_short;
        public String _animal;
        public String _festival1;
        public String _festival2;
        public String _festival3;
        public String _festival4;
    }
}
