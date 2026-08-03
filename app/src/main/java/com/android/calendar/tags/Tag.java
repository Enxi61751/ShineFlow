package com.android.calendar.tags;

/**
 * A user-defined label that can be attached to events. ShineFlow stores tags
 * locally (see {@link TagRepository}); each event keeps the list of tag ids it
 * is assigned to as an extended property.
 */
public class Tag {
    public long id;
    public String name;
    public int color;

    public Tag() {
    }

    public Tag(long id, String name, int color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }
}
