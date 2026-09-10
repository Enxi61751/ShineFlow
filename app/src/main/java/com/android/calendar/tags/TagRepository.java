package com.android.calendar.tags;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Local store for the user's tag definitions, persisted as JSON in a dedicated
 * SharedPreferences file. Kept intentionally simple (the number of tags is
 * small), everything is loaded into memory.
 */
public class TagRepository {

    private static final String PREFS_NAME = "shineflow_tags";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_UNCATEGORIZED_POS = "uncategorized_pos";

    private static TagRepository sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Gson mGson = new Gson();
    private List<Tag> mTags;

    private TagRepository(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
        seedDefaultsIfNeeded();
    }

    public static synchronized TagRepository get(Context context) {
        if (sInstance == null) {
            sInstance = new TagRepository(context);
        }
        return sInstance;
    }

    private void load() {
        String json = mPrefs.getString(KEY_TAGS, null);
        if (json == null) {
            mTags = new ArrayList<>();
            return;
        }
        Type type = new TypeToken<ArrayList<Tag>>() {}.getType();
        List<Tag> parsed = mGson.fromJson(json, type);
        mTags = parsed != null ? parsed : new ArrayList<>();
    }

    private void persist() {
        mPrefs.edit().putString(KEY_TAGS, mGson.toJson(mTags)).apply();
    }

    private void seedDefaultsIfNeeded() {
        if (mPrefs.getBoolean(KEY_SEEDED, false)) {
            return;
        }
        // A handful of vibrant default tags so the feature is usable immediately.
        addInternal(mContext.getString(ws.xsoh.etar.R.string.tag_default_work),
                Color.parseColor("#FF5A5F"));
        addInternal(mContext.getString(ws.xsoh.etar.R.string.tag_default_study),
                Color.parseColor("#3D8BFF"));
        addInternal(mContext.getString(ws.xsoh.etar.R.string.tag_default_life),
                Color.parseColor("#0FBFA5"));
        addInternal(mContext.getString(ws.xsoh.etar.R.string.tag_default_date),
                Color.parseColor("#FF4F9A"));
        addInternal(mContext.getString(ws.xsoh.etar.R.string.tag_default_important),
                Color.parseColor("#F5820A"));
        mPrefs.edit().putBoolean(KEY_SEEDED, true).apply();
        persist();
    }

    private long nextId() {
        long id = mPrefs.getLong(KEY_NEXT_ID, 1L);
        mPrefs.edit().putLong(KEY_NEXT_ID, id + 1).apply();
        return id;
    }

    private Tag addInternal(String name, int color) {
        Tag tag = new Tag(nextId(), name, color);
        mTags.add(tag);
        return tag;
    }

    public synchronized List<Tag> getAll() {
        return new ArrayList<>(mTags);
    }

    public synchronized Tag getById(long id) {
        for (Tag t : mTags) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    public synchronized Tag add(String name, int color) {
        Tag tag = addInternal(name, color);
        persist();
        return tag;
    }

    public synchronized void update(long id, String name, int color) {
        for (Tag t : mTags) {
            if (t.id == id) {
                t.name = name;
                t.color = color;
                break;
            }
        }
        persist();
    }

    public synchronized void delete(long id) {
        for (int i = mTags.size() - 1; i >= 0; i--) {
            if (mTags.get(i).id == id) {
                mTags.remove(i);
                break;
            }
        }
        persist();
    }

    /**
     * Reorders all tags to match the given list of IDs. Tags not in the list
     * remain in their current relative order at the end.
     */
    public synchronized void saveOrder(List<Long> tagIdsInOrder) {
        List<Tag> reordered = new ArrayList<>();
        for (Long id : tagIdsInOrder) {
            for (Tag t : mTags) {
                if (t.id == id) {
                    reordered.add(t);
                    break;
                }
            }
        }
        // Append any tags not in the order list
        for (Tag t : mTags) {
            if (!tagIdsInOrder.contains(t.id)) {
                reordered.add(t);
            }
        }
        mTags.clear();
        mTags.addAll(reordered);
        persist();
    }

    /**
     * Moves the tag at {@code fromIndex} to {@code toIndex} in the list,
     * shifting other elements as needed.
     */
    public synchronized void reorder(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= mTags.size()
                || toIndex < 0 || toIndex >= mTags.size()
                || fromIndex == toIndex) {
            return;
        }
        Tag moved = mTags.remove(fromIndex);
        mTags.add(toIndex, moved);
        persist();
    }

    /**
     * Returns the stored display position of the "Uncategorized" pseudo-tag
     * in the tag management list. Defaults to the end (tag count).
     */
    public int getUncategorizedPosition() {
        return mPrefs.getInt(KEY_UNCATEGORIZED_POS, mTags.size());
    }

    /**
     * Persists the display position of the "Uncategorized" pseudo-tag.
     */
    public void setUncategorizedPosition(int pos) {
        mPrefs.edit().putInt(KEY_UNCATEGORIZED_POS, pos).apply();
    }

    /**
     * Builds a display list that includes both real tags and the "Uncategorized"
     * pseudo-entry at its stored position. Returns a list of Tag objects where
     * the uncategorized entry has id == TagFilter.TAG_ID_UNCATEGORIZED.
     */
    public synchronized List<Tag> getAllWithUncategorized(Context context) {
        List<Tag> result = new ArrayList<>(mTags);
        int pos = getUncategorizedPosition();
        // Clamp to valid range
        if (pos < 0) pos = 0;
        if (pos > result.size()) pos = result.size();
        Tag uncategorized = new Tag(TagFilter.TAG_ID_UNCATEGORIZED,
                context.getString(ws.xsoh.etar.R.string.tag_uncategorized),
                0xFF757575);
        result.add(pos, uncategorized);
        return result;
    }
}
