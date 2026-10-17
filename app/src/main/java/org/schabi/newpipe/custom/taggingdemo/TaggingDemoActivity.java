/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.taggingdemo;

import android.app.AlertDialog;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Native, disposable tagging user-experience prototype. All streams and edits are synthetic.
 *
 * This screen MUST NOT read or write the canonical Library, settings, player or tag database.
 * The preview actions intentionally show dialogs rather than executing real operations.
 */
public final class TaggingDemoActivity extends AppCompatActivity {
    private final TaggingDemoModel model = new TaggingDemoModel();
    private LinearLayout filterSection;
    private LinearLayout resultRows;
    private TextView resultHeading;
    private TextView allButton;
    private TextView untaggedButton;
    private EditText searchBox;
    private BottomSheetDialog managerSheet;
    private LinearLayout managerContents;
    private BottomSheetDialog editorSheet;
    private LinearLayout editorChoices;
    private String editorSearch = "";
    private boolean requireAll = true;
    private boolean untaggedOnly = false;
    private int sortMode = 0;

    private int bgColor;
    private int textColor;
    private int mutedColor;
    private int surfaceColor;
    private int borderColor;
    private int accentColor;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));
        super.onCreate(savedInstanceState);
        bgColor = resolve(android.R.attr.colorBackground, Color.WHITE);
        textColor = resolve(android.R.attr.textColorPrimary, Color.BLACK);
        mutedColor = resolve(android.R.attr.textColorSecondary, Color.DKGRAY);
        surfaceColor = resolve(R.attr.card_item_background_color, bgColor);
        borderColor = resolve(R.attr.border_color, 0xFF555555);
        accentColor = resolve(android.R.attr.colorAccent, 0xFFCD201F);
        buildScreen();
    }

    private int resolve(final int attr, final int fallback) {
        final TypedArray array = obtainStyledAttributes(new int[]{attr});
        final int value = array.getColor(0, fallback);
        array.recycle();
        return value;
    }

    private int dp(final float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(final int color, final int radius, final boolean border) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border) {
            drawable.setStroke(dp(1), borderColor);
        }
        return drawable;
    }

    private TextView text(final String label, final int size, final boolean bold,
                          final int color) {
        final TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout vertical() {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams box(final int width, final int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
    }

    private void space(final LinearLayout parent, final int size) {
        parent.addView(new View(this), box(1, size));
    }

    private TextView action(final String label, final boolean primary) {
        final TextView view = text(label, 14, true, primary ? Color.WHITE : textColor);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setBackground(rounded(primary ? accentColor : surfaceColor, 11, !primary));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void buildScreen() {
        final LinearLayout root = vertical();
        root.setBackgroundColor(bgColor);
        setContentView(root);

        final LinearLayout toolbar = horizontal();
        toolbar.setPadding(dp(14), dp(9), dp(14), dp(7));
        root.addView(toolbar, box(-1, 58));

        final TextView back = text("‹", 32, false, textColor);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back to NewPipe");
        toolbar.addView(back, box(40, 48));
        back.setOnClickListener(v -> finish());

        final LinearLayout titleBlock = vertical();
        final LinearLayout.LayoutParams titleParams = box(0, -2);
        titleParams.weight = 1f;
        toolbar.addView(titleBlock, titleParams);
        titleBlock.addView(text("Tags", 21, true, textColor));
        titleBlock.addView(text("NATIVE UI DEMO", 10, true, accentColor));

        final TextView manage = action("Manage", false);
        toolbar.addView(manage, box(-2, 44));
        manage.setOnClickListener(v -> openManager());
        final TextView scenarios = text("⋮", 27, true, textColor);
        scenarios.setGravity(Gravity.CENTER);
        scenarios.setContentDescription("Demo scenarios and reset");
        toolbar.addView(scenarios, box(38, 48));
        scenarios.setOnClickListener(v -> showScenarios());

        final View divider = new View(this);
        divider.setBackgroundColor(borderColor);
        root.addView(divider, box(-1, 1));

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final LinearLayout content = vertical();
        content.setPadding(dp(16), dp(12), dp(16), dp(18));
        scroll.addView(content);

        final TextView banner = text("Sample videos only  ·  Changes reset when you leave",
                12, false, mutedColor);
        banner.setBackground(rounded(surfaceColor, 9, true));
        banner.setPadding(dp(11), dp(10), dp(11), dp(10));
        content.addView(banner, box(-1, -2));
        space(content, 15);

        final LinearLayout searchWrap = horizontal();
        searchWrap.setBackground(rounded(surfaceColor, 12, true));
        searchWrap.setPadding(dp(12), 0, dp(12), 0);
        content.addView(searchWrap, box(-1, 50));
        searchWrap.addView(text("⌕", 26, false, mutedColor), box(30, -2));
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBox.setHint("Search videos or uploaders");
        searchBox.setBackgroundColor(Color.TRANSPARENT);
        searchWrap.addView(searchBox, new LinearLayout.LayoutParams(0, -1, 1f));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                      final int count, final int after) { }
            @Override public void onTextChanged(final CharSequence s, final int start,
                                                 final int before, final int count) {
                renderResults();
            }
            @Override public void afterTextChanged(final Editable editable) { }
        });
        space(content, 15);

        final LinearLayout tabs = horizontal();
        content.addView(tabs, box(-1, 44));
        allButton = action("All saved videos", false);
        final LinearLayout.LayoutParams tabFirst = box(0, -1);
        tabFirst.weight = 1f;
        tabFirst.rightMargin = dp(8);
        tabs.addView(allButton, tabFirst);
        untaggedButton = action("Untagged", false);
        final LinearLayout.LayoutParams tabLast = box(0, -1);
        tabLast.weight = 1f;
        tabs.addView(untaggedButton, tabLast);
        allButton.setOnClickListener(v -> {
            untaggedOnly = false;
            renderFilters();
            renderResults();
        });
        untaggedButton.setOnClickListener(v -> {
            untaggedOnly = true;
            model.selections.clear();
            renderFilters();
            renderResults();
        });
        space(content, 15);

        final LinearLayout filterHeader = horizontal();
        content.addView(filterHeader, box(-1, 36));
        final TextView label = text("TAG FILTERS", 12, true, mutedColor);
        filterHeader.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView mode = action("AND", false);
        filterHeader.addView(mode, box(72, 36));
        mode.setOnClickListener(v -> {
            requireAll = !requireAll;
            mode.setText(requireAll ? "AND" : "OR");
            renderResults();
        });

        final TextView help = text(
                "Tap tags: include (+), exclude (−), clear. AND/OR affects included tags.",
                11, false, mutedColor);
        content.addView(help, box(-1, -2));
        space(content, 9);
        filterSection = vertical();
        content.addView(filterSection, box(-1, -2));
        space(content, 18);

        final LinearLayout resultsHeader = horizontal();
        content.addView(resultsHeader, box(-1, 49));
        resultHeading = text("12 videos", 18, true, textColor);
        resultsHeader.addView(resultHeading, new LinearLayout.LayoutParams(0, -2, 1f));
        final Spinner sorting = new Spinner(this);
        final ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Library order", "Title A–Z", "Title Z–A"});
        sorting.setAdapter(sortAdapter);
        resultsHeader.addView(sorting, box(134, 43));
        sorting.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(final AdapterView<?> parent, final View view,
                                                  final int position, final long id) {
                sortMode = position;
                renderResults();
            }
            @Override public void onNothingSelected(final AdapterView<?> parent) { }
        });
        resultRows = vertical();
        content.addView(resultRows, box(-1, -2));

        addActionFooter(root);
        renderFilters();
        renderResults();
    }

    private void addActionFooter(final LinearLayout root) {
        final View footerDivider = new View(this);
        footerDivider.setBackgroundColor(borderColor);
        root.addView(footerDivider, box(-1, 1));

        final LinearLayout actions = horizontal();
        actions.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.addView(actions, box(-1, 66));
        for (final String labelText : new String[]{"▶ Play", "+ Queue", "Save playlist"}) {
            final TextView button = action(labelText, labelText.startsWith("▶"));
            final LinearLayout.LayoutParams params = box(0, 46);
            params.weight = 1f;
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            actions.addView(button, params);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setOnClickListener(v -> previewAction(labelText));
        }
    }

    private TextView pill(final String name, final int state) {
        final int selectedBg = state == 1 ? (accentColor & 0x00FFFFFF) | 0x33000000
                : state == -1 ? 0x33D84545 : surfaceColor;
        final int foreground = state == 1 ? accentColor : state == -1
                ? 0xFFD84545 : textColor;
        final String prefix = state == 1 ? "+ " : state == -1 ? "− " : "";
        final TextView chip = text(prefix + name, 13, state != 0, foreground);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setMinHeight(dp(38));
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(rounded(selectedBg, 22, true));
        chip.setFocusable(true);
        chip.setClickable(true);
        return chip;
    }

    private void renderFilters() {
        if (filterSection == null) {
            return;
        }
        allButton.setBackground(rounded(untaggedOnly ? surfaceColor : accentColor,
                10, untaggedOnly));
        allButton.setTextColor(untaggedOnly ? textColor : Color.WHITE);
        untaggedButton.setBackground(rounded(untaggedOnly ? accentColor : surfaceColor,
                10, !untaggedOnly));
        untaggedButton.setTextColor(untaggedOnly ? Color.WHITE : textColor);

        filterSection.removeAllViews();
        if (untaggedOnly) {
            final TextView explanation = text(
                    "Showing only saved videos with zero tags. Other filters are cleared.",
                    13, false, mutedColor);
            filterSection.addView(explanation, box(-1, -2));
            return;
        }
        for (final TaggingDemoModel.Category category : model.categories) {
            addFilterGroup(category.name, category.id);
        }
        addFilterGroup("Uncategorized", null);
        if (model.tags.isEmpty()) {
            filterSection.addView(text("No tags yet. Tap Manage to create one.",
                    13, false, mutedColor));
        }
    }

    private void addFilterGroup(final String name, @Nullable final Integer categoryId) {
        final List<TaggingDemoModel.Tag> group = new ArrayList<>();
        for (final TaggingDemoModel.Tag tag : model.tags.values()) {
            if (categoryId == null ? tag.categoryId == null
                    : categoryId.equals(tag.categoryId)) {
                group.add(tag);
            }
        }
        if (group.isEmpty()) {
            return;
        }
        final TextView label = text(name, 13, true, textColor);
        filterSection.addView(label, box(-1, -2));
        space(filterSection, 7);
        final WrapTags line = new WrapTags(this);
        filterSection.addView(line, box(-1, -2));
        for (final TaggingDemoModel.Tag tag : group) {
            final TextView chip = pill(tag.name,
                    model.selections.getOrDefault(tag.id, 0));
            final ViewGroup.MarginLayoutParams params =
                    new ViewGroup.MarginLayoutParams(-2, -2);
            params.rightMargin = dp(7);
            params.bottomMargin = dp(7);
            line.addView(chip, params);
            chip.setOnClickListener(v -> {
                model.cycleFilter(tag.id);
                renderFilters();
                renderResults();
            });
            chip.setContentDescription(tag.name + ", tap to cycle include, exclude or clear");
        }
        space(filterSection, 6);
    }

    private List<TaggingDemoModel.Media> matching() {
        return model.matching(searchBox == null ? "" : searchBox.getText().toString(),
                requireAll, untaggedOnly, sortMode);
    }

    private void renderResults() {
        if (resultRows == null || resultHeading == null) {
            return;
        }
        final List<TaggingDemoModel.Media> matching = matching();
        resultHeading.setText(matching.size() + (matching.size() == 1 ? " video" : " videos"));
        resultRows.removeAllViews();
        if (matching.isEmpty()) {
            final TextView empty = text(
                    model.media.isEmpty()
                            ? "This demo Library is empty. Use ⋮ to reset sample data."
                            : untaggedOnly
                            ? "All your demo videos have tags. Nothing untagged here."
                            : "No matching videos. Try clearing search or tag filters.",
                    14, false, mutedColor);
            empty.setPadding(dp(14), dp(24), dp(14), dp(28));
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(rounded(surfaceColor, 13, true));
            resultRows.addView(empty, box(-1, -2));
            return;
        }
        for (final TaggingDemoModel.Media item : matching) {
            final LinearLayout row = horizontal();
            row.setPadding(dp(12), dp(12), dp(8), dp(12));
            row.setBackground(rounded(surfaceColor, 12, true));
            final LinearLayout.LayoutParams rowParams = box(-1, -2);
            rowParams.bottomMargin = dp(8);
            resultRows.addView(row, rowParams);

            final TextView thumb = text("♫", 24, false, accentColor);
            thumb.setGravity(Gravity.CENTER);
            thumb.setBackground(rounded(bgColor, 9, true));
            row.addView(thumb, box(48, 48));
            final LinearLayout details = vertical();
            final LinearLayout.LayoutParams detailsParams = box(0, -2);
            detailsParams.weight = 1f;
            detailsParams.leftMargin = dp(11);
            row.addView(details, detailsParams);
            details.addView(text(item.title, 14, true, textColor));
            details.addView(text(item.uploader, 12, false, mutedColor));
            details.addView(text(tagSummary(item), 11, false, accentColor));
            final TextView edit = text("Edit ›", 12, true, accentColor);
            edit.setGravity(Gravity.CENTER);
            row.addView(edit, box(46, 48));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription("Edit tags for " + item.title);
            row.setOnClickListener(v -> openEditor(item));
        }
    }

    private String tagSummary(final TaggingDemoModel.Media item) {
        if (item.tagIds.isEmpty()) {
            return "Untagged";
        }
        final List<String> names = new ArrayList<>();
        for (final Integer id : item.tagIds) {
            final TaggingDemoModel.Tag tag = model.tags.get(id);
            if (tag != null) {
                names.add(tag.name);
            }
        }
        return String.join(" · ", names);
    }

    private void previewAction(final String actionName) {
        final List<TaggingDemoModel.Media> matches = matching();
        final StringBuilder message = new StringBuilder();
        message.append(matches.size()).append(" current matching videos\n\n");
        for (int i = 0; i < Math.min(6, matches.size()); i++) {
            message.append(i + 1).append(". ").append(matches.get(i).title).append("\n");
        }
        if (matches.size() > 6) {
            message.append("… and ").append(matches.size() - 6).append(" more\n");
        }
        message.append("\nDEMO ONLY: no playback queue or playlist changes are made.");
        new AlertDialog.Builder(this)
                .setTitle(actionName + " — Preview")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private ScrollView sheetWith(final LinearLayout contents) {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        contents.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void openEditor(final TaggingDemoModel.Media item) {
        if (editorSheet != null) {
            editorSheet.dismiss();
        }
        editorSheet = new BottomSheetDialog(this);
        final LinearLayout content = vertical();
        content.addView(text("Edit tags", 21, true, textColor));
        space(content, 5);
        content.addView(text(item.title, 15, false, mutedColor));
        space(content, 6);
        content.addView(text("Demo edits aren't saved to the real Library.",
                11, false, mutedColor));
        space(content, 14);
        final EditText search = new EditText(this);
        search.setHint("Find tags");
        search.setSingleLine(true);
        content.addView(search, box(-1, 49));
        space(content, 10);
        editorChoices = vertical();
        content.addView(editorChoices, box(-1, -2));
        editorSearch = "";
        renderEditorOptions(item);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(final CharSequence s, final int start,
                                                      final int count, final int after) { }
            @Override public void onTextChanged(final CharSequence s, final int start,
                                                 final int before, final int count) {
                editorSearch = s.toString();
                renderEditorOptions(item);
            }
            @Override public void afterTextChanged(final Editable editable) { }
        });
        space(content, 12);
        final TextView create = action("+ Create a tag", false);
        content.addView(create, box(-1, 48));
        create.setOnClickListener(v -> newTag(item));
        space(content, 12);
        final TextView done = action("Done", true);
        content.addView(done, box(-1, 48));
        done.setOnClickListener(v -> editorSheet.dismiss());
        editorSheet.setContentView(sheetWith(content));
        editorSheet.show();
    }

    private void renderEditorOptions(final TaggingDemoModel.Media item) {
        if (editorChoices == null) {
            return;
        }
        editorChoices.removeAllViews();
        int shown = 0;
        for (final TaggingDemoModel.Tag tag : model.tags.values()) {
            if (!tag.name.toLowerCase(Locale.ROOT)
                    .contains(editorSearch.toLowerCase(Locale.ROOT))) {
                continue;
            }
            shown++;
            final CheckBox checkbox = new CheckBox(this);
            checkbox.setText(tag.name + "  ·  " + categoryName(tag.categoryId));
            checkbox.setTextColor(textColor);
            checkbox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            checkbox.setPadding(dp(5), dp(3), 0, dp(3));
            checkbox.setChecked(item.tagIds.contains(tag.id));
            editorChoices.addView(checkbox, box(-1, 47));
            checkbox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    item.tagIds.add(tag.id);
                } else {
                    item.tagIds.remove(tag.id);
                }
                renderResults();
            });
        }
        if (shown == 0) {
            editorChoices.addView(text("No matching tags. Create one below.",
                    13, false, mutedColor));
        }
    }

    private String categoryName(@Nullable final Integer categoryId) {
        if (categoryId != null) {
            for (final TaggingDemoModel.Category category : model.categories) {
                if (category.id == categoryId) {
                    return category.name;
                }
            }
        }
        return "Uncategorized";
    }

    private void openManager() {
        if (managerSheet != null && managerSheet.isShowing()) {
            managerSheet.dismiss();
        }
        managerSheet = new BottomSheetDialog(this);
        managerContents = vertical();
        refreshManager();
        managerSheet.setContentView(sheetWith(managerContents));
        managerSheet.show();
    }

    private void refreshManager() {
        if (managerContents == null) {
            return;
        }
        managerContents.removeAllViews();
        managerContents.addView(text("Manage tags & categories", 21, true, textColor));
        space(managerContents, 5);
        managerContents.addView(text("Only sample data changes; all edits reset on restart.",
                12, false, mutedColor));
        space(managerContents, 14);

        final LinearLayout createRow = horizontal();
        managerContents.addView(createRow, box(-1, 47));
        final TextView addCategory = action("+ Category", false);
        final LinearLayout.LayoutParams left = box(0, -1);
        left.weight = 1f;
        left.rightMargin = dp(9);
        createRow.addView(addCategory, left);
        addCategory.setOnClickListener(v -> newCategory());
        final TextView addTag = action("+ Tag", true);
        final LinearLayout.LayoutParams right = box(0, -1);
        right.weight = 1f;
        createRow.addView(addTag, right);
        addTag.setOnClickListener(v -> newTag(null));
        space(managerContents, 15);

        for (final TaggingDemoModel.Category category : model.categories) {
            final int count = model.categoryTags(category).size();
            final LinearLayout head = horizontal();
            managerContents.addView(head, box(-1, 47));
            final TextView title = text(category.name + "  (" + count + ")", 16, true, textColor);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            final TextView rename = action("Rename", false);
            head.addView(rename, box(88, 40));
            rename.setOnClickListener(v -> renameDialog("Rename category", category.name,
                    value -> model.renameCategory(category, value)));
            final TextView delete = action("Delete", false);
            final LinearLayout.LayoutParams deleteParams = box(78, 40);
            deleteParams.leftMargin = dp(7);
            head.addView(delete, deleteParams);
            delete.setOnClickListener(v -> confirmDeleteCategory(category));
            addManagedTags(category.id);
            space(managerContents, 12);
        }
        managerContents.addView(text("Uncategorized", 16, true, textColor));
        addManagedTags(null);
        space(managerContents, 15);
        final TextView close = action("Done", true);
        managerContents.addView(close, box(-1, 48));
        close.setOnClickListener(v -> managerSheet.dismiss());
    }

    private void addManagedTags(@Nullable final Integer categoryId) {
        boolean found = false;
        for (final TaggingDemoModel.Tag tag : new ArrayList<>(model.tags.values())) {
            if (categoryId == null ? tag.categoryId != null
                    : !categoryId.equals(tag.categoryId)) {
                continue;
            }
            found = true;
            final LinearLayout row = horizontal();
            row.setPadding(dp(8), dp(3), 0, dp(3));
            managerContents.addView(row, box(-1, 43));
            final TextView name = text("• " + tag.name, 14, false, textColor);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            final TextView edit = text("Edit", 13, true, accentColor);
            edit.setGravity(Gravity.CENTER);
            row.addView(edit, box(56, 39));
            edit.setOnClickListener(v -> tagActions(tag));
        }
        if (!found) {
            managerContents.addView(text("No tags in this group", 12, false, mutedColor));
        }
    }

    private void tagActions(final TaggingDemoModel.Tag tag) {
        final String[] choices = {"Rename", "Move to category", "Delete tag"};
        new AlertDialog.Builder(this)
                .setTitle(tag.name)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        renameDialog("Rename tag", tag.name,
                                value -> model.renameTag(tag, value));
                    } else if (which == 1) {
                        chooseCategory(tag);
                    } else {
                        confirmDeleteTag(tag);
                    }
                }).show();
    }

    private void chooseCategory(final TaggingDemoModel.Tag tag) {
        final String[] labels = categoryLabels();
        new AlertDialog.Builder(this)
                .setTitle("Move " + tag.name)
                .setItems(labels, (dialog, index) -> {
                    model.moveTag(tag, categoryIdFromIndex(index));
                    refreshAll();
                }).show();
    }

    private String[] categoryLabels() {
        final String[] labels = new String[model.categories.size() + 1];
        labels[0] = "Uncategorized";
        for (int i = 0; i < model.categories.size(); i++) {
            labels[i + 1] = model.categories.get(i).name;
        }
        return labels;
    }

    @Nullable
    private Integer categoryIdFromIndex(final int position) {
        return position == 0 ? null : model.categories.get(position - 1).id;
    }

    private void nameDialog(final String heading, final String initial,
                            final Consumer<String> accept) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.selectAll();
        final LinearLayout wrap = vertical();
        wrap.setPadding(dp(24), 0, dp(24), 0);
        wrap.addView(input, box(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(heading)
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        accept.accept(input.getText().toString());
                        refreshAll();
                    } catch (final IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void renameDialog(final String heading, final String initial,
                              final Consumer<String> accept) {
        nameDialog(heading, initial, accept);
    }

    private void newCategory() {
        nameDialog("New category", "", model::addCategory);
    }

    private void newTag(@Nullable final TaggingDemoModel.Media assignTo) {
        final LinearLayout fields = vertical();
        fields.setPadding(dp(24), 0, dp(24), 0);
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Tag name");
        fields.addView(name, box(-1, 50));
        space(fields, 10);
        fields.addView(text("Category (optional)", 12, true, mutedColor));
        final Spinner categories = new Spinner(this);
        final ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categoryLabels());
        categories.setAdapter(categoryAdapter);
        fields.addView(categories, box(-1, 48));
        new AlertDialog.Builder(this)
                .setTitle("New tag")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    try {
                        final TaggingDemoModel.Tag tag = model.addTag(
                                name.getText().toString(),
                                categoryIdFromIndex(categories.getSelectedItemPosition()));
                        if (assignTo != null) {
                            assignTo.tagIds.add(tag.id);
                            renderEditorOptions(assignTo);
                        }
                        refreshAll();
                    } catch (final IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void confirmDeleteTag(final TaggingDemoModel.Tag tag) {
        final int assignments = model.assignmentCount(java.util.Collections.singleton(tag.id));
        new AlertDialog.Builder(this)
                .setTitle("Delete " + tag.name + "?")
                .setMessage("Permanently deletes this demo tag and " + assignments
                        + " tag assignments. No videos or playlists are removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete tag", (dialog, which) -> {
                    model.deleteTag(tag);
                    refreshAll();
                }).show();
    }

    private void confirmDeleteCategory(final TaggingDemoModel.Category category) {
        final Set<Integer> tagIds = model.categoryTags(category);
        final int assignments = model.assignmentCount(tagIds);
        new AlertDialog.Builder(this)
                .setTitle("Delete " + category.name + "?")
                .setMessage("This will delete the category, all " + tagIds.size()
                        + " tags inside it, and " + assignments + " tag assignments.\n\n"
                        + "All videos and existing playlists stay untouched. "
                        + "Videos losing their last tag appear under Untagged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete category", (dialog, which) -> {
                    model.deleteCategory(category);
                    refreshAll();
                }).show();
    }

    private void refreshAll() {
        renderFilters();
        renderResults();
        if (managerSheet != null && managerSheet.isShowing()) {
            refreshManager();
        }
    }

    private void showScenarios() {
        new AlertDialog.Builder(this)
                .setTitle("Demo scenarios")
                .setItems(new String[]{"Reset sample Library", "Empty Library",
                        "Clear tags (all videos become Untagged)"}, (dialog, option) -> {
                    if (option == 0) {
                        model.reset();
                    } else if (option == 1) {
                        model.media.clear();
                    } else {
                        for (final TaggingDemoModel.Tag tag
                                : new ArrayList<>(model.tags.values())) {
                            model.deleteTag(tag);
                        }
                    }
                    model.selections.clear();
                    untaggedOnly = false;
                    searchBox.setText("");
                    refreshAll();
                }).show();
    }

    /**
     * Lightweight wrapping native ViewGroup to keep tags visible without horizontal scrolling.
     */
    private static final class WrapTags extends ViewGroup {
        WrapTags(final Context context) {
            super(context);
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        }

        @Override
        public LayoutParams generateLayoutParams(final android.util.AttributeSet attrs) {
            return new MarginLayoutParams(getContext(), attrs);
        }

        @Override
        protected LayoutParams generateLayoutParams(final LayoutParams params) {
            return new MarginLayoutParams(params);
        }

        @Override
        protected boolean checkLayoutParams(final LayoutParams params) {
            return params instanceof MarginLayoutParams;
        }

        @Override
        protected void onMeasure(final int widthSpec, final int heightSpec) {
            final int width = MeasureSpec.getSize(widthSpec);
            final int usableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
                final MarginLayoutParams margins = (MarginLayoutParams) child.getLayoutParams();
                final int itemWidth = child.getMeasuredWidth()
                        + margins.leftMargin + margins.rightMargin;
                final int itemHeight = child.getMeasuredHeight()
                        + margins.topMargin + margins.bottomMargin;
                if (x > 0 && x + itemWidth > usableWidth) {
                    y += rowHeight;
                    x = 0;
                    rowHeight = 0;
                }
                x += itemWidth;
                rowHeight = Math.max(rowHeight, itemHeight);
            }
            setMeasuredDimension(resolveSize(width, widthSpec),
                    resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
        }

        @Override
        protected void onLayout(final boolean changed, final int left, final int top,
                                final int right, final int bottom) {
            final int usableWidth = right - left - getPaddingRight();
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                final MarginLayoutParams margins = (MarginLayoutParams) child.getLayoutParams();
                final int itemWidth = child.getMeasuredWidth()
                        + margins.leftMargin + margins.rightMargin;
                final int itemHeight = child.getMeasuredHeight()
                        + margins.topMargin + margins.bottomMargin;
                if (x > getPaddingLeft() && x + itemWidth > usableWidth) {
                    y += rowHeight;
                    x = getPaddingLeft();
                    rowHeight = 0;
                }
                final int cx = x + margins.leftMargin;
                final int cy = y + margins.topMargin;
                child.layout(cx, cy, cx + child.getMeasuredWidth(),
                        cy + child.getMeasuredHeight());
                x += itemWidth;
                rowHeight = Math.max(rowHeight, itemHeight);
            }
        }
    }
}
