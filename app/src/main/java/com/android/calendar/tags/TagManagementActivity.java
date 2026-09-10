package com.android.calendar.tags;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.calendar.theme.DynamicThemeKt;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

import ws.xsoh.etar.R;

/**
 * Lets the user create, reorder, edit and delete event tags (name + colour),
 * plus manage the position of the "Uncategorized" pseudo-tag.
 */
public class TagManagementActivity extends AppCompatActivity implements TagAdapter.Callbacks {

    private static final int[] PALETTE = {
            0xFFFF5A5F, 0xFFF5820A, 0xFFFFC300, 0xFF0FBFA5,
            0xFF3D8BFF, 0xFF8E5BF0, 0xFFFF4F9A, 0xFF00B894,
            0xFF636E72, 0xFF2D3436,
    };

    private TagRepository mRepo;
    private RecyclerView mRecycler;
    private TagAdapter mAdapter;
    private ItemTouchHelper mItemTouchHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicThemeKt.applyTheme(this);
        setContentView(R.layout.tag_management);

        mRepo = TagRepository.get(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.tags_manage_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mRecycler = findViewById(R.id.tag_recycler);
        mRecycler.setLayoutManager(new LinearLayoutManager(this));

        mAdapter = new TagAdapter(this);
        mRecycler.setAdapter(mAdapter);

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                mAdapter.onItemMove(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // No swipe to delete
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // drag via handle only
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                persistCurrentOrder();
            }
        };
        mItemTouchHelper = new ItemTouchHelper(callback);
        mItemTouchHelper.attachToRecyclerView(mRecycler);

        FloatingActionButton fab = findViewById(R.id.fab_add_tag);
        fab.setOnClickListener(v -> showEditDialog(null));

        rebuild();
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
    public void onDragStarted(TagAdapter.ViewHolder holder) {
        mItemTouchHelper.startDrag(holder);
    }

    @Override
    public void onTagClicked(Tag tag) {
        showEditDialog(tag);
    }

    @Override
    public void onTagDeleteClicked(Tag tag) {
        confirmDelete(tag);
    }

    private void rebuild() {
        List<Tag> items = mRepo.getAllWithUncategorized(this);
        mAdapter.setItems(items);
    }

    private void persistCurrentOrder() {
        List<Tag> items = mAdapter.getItems();
        List<Long> tagIds = new ArrayList<>();
        int uncategorizedPos = items.size(); // default: end

        for (int i = 0; i < items.size(); i++) {
            Tag t = items.get(i);
            if (t.id == TagFilter.TAG_ID_UNCATEGORIZED) {
                uncategorizedPos = i;
            } else {
                tagIds.add(t.id);
            }
        }
        mRepo.saveOrder(tagIds);
        mRepo.setUncategorizedPosition(uncategorizedPos);
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
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        if (selected) {
            d.setStroke(3 * density, android.graphics.Color.DKGRAY);
        }
        sw.setBackground(d);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }
}
