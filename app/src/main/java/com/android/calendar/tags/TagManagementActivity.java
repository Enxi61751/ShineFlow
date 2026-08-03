package com.android.calendar.tags;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.android.calendar.theme.DynamicThemeKt;

import java.util.List;

import ws.xsoh.etar.R;

/**
 * Lets the user create, edit and delete event tags (name + colour).
 */
public class TagManagementActivity extends AppCompatActivity {

    private static final int[] PALETTE = {
            0xFFFF5A5F, 0xFFF5820A, 0xFFFFC300, 0xFF0FBFA5,
            0xFF3D8BFF, 0xFF8E5BF0, 0xFFFF4F9A, 0xFF00B894,
            0xFF636E72, 0xFF2D3436,
    };

    private TagRepository mRepo;
    private LinearLayout mListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicThemeKt.applyTheme(this);
        mRepo = TagRepository.get(this);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        mListContainer = new LinearLayout(this);
        mListContainer.setOrientation(LinearLayout.VERTICAL);
        mListContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(mListContainer);
        setContentView(scroll);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.tags_manage_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        rebuild();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.tags_add)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == 1) {
            showEditDialog(null);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void rebuild() {
        mListContainer.removeAllViews();
        List<Tag> tags = mRepo.getAll();
        if (tags.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.tags_empty);
            empty.setPadding(0, 32, 0, 0);
            mListContainer.addView(empty);
            return;
        }
        int density = (int) getResources().getDisplayMetrics().density;
        for (final Tag tag : tags) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12 * density, 0, 12 * density);
            row.setClickable(true);
            row.setOnClickListener(v -> showEditDialog(tag));

            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(tag.color);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(20 * density, 20 * density);
            dotLp.rightMargin = 16 * density;
            row.addView(dot, dotLp);

            TextView name = new TextView(this);
            name.setText(tag.name);
            name.setTextSize(16);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nameLp);

            TextView delete = new TextView(this);
            delete.setText(R.string.tags_delete);
            delete.setTextColor(0xFFD32F2F);
            delete.setPadding(16 * density, 0, 0, 0);
            delete.setOnClickListener(v -> confirmDelete(tag));
            row.addView(delete);

            mListContainer.addView(row);
        }
    }

    private void confirmDelete(final Tag tag) {
        new AlertDialog.Builder(this)
                .setTitle(tag.name)
                .setMessage(R.string.tags_delete_confirm)
                .setPositiveButton(R.string.tags_delete, (d, w) -> {
                    mRepo.delete(tag.id);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDialog(final Tag existing) {
        int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24 * density, 8 * density, 24 * density, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.tags_name_hint);
        nameInput.setSingleLine(true);
        if (existing != null) {
            nameInput.setText(existing.name);
        }
        content.addView(nameInput);

        // Colour swatches
        final int[] selected = {existing != null ? existing.color : PALETTE[0]};
        final LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setPadding(0, 16 * density, 0, 0);
        final View[] swatches = new View[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            final View sw = new View(this);
            swatches[i] = sw;
            updateSwatch(sw, color, color == selected[0], density);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(30 * density, 30 * density);
            lp.rightMargin = 8 * density;
            sw.setOnClickListener(v -> {
                selected[0] = color;
                for (int j = 0; j < PALETTE.length; j++) {
                    updateSwatch(swatches[j], PALETTE[j], PALETTE[j] == color, density);
                }
            });
            swatchRow.addView(sw, lp);
        }
        // Allow horizontal scrolling of swatches
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(swatchRow);
        content.addView(hs);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.tags_add : R.string.tags_edit)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    if (existing == null) {
                        mRepo.add(name, selected[0]);
                    } else {
                        mRepo.update(existing.id, name, selected[0]);
                    }
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateSwatch(View sw, int color, boolean selected, int density) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (selected) {
            d.setStroke(3 * density, Color.DKGRAY);
        }
        sw.setBackground(d);
    }
}
