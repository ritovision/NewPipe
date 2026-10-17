/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.taggingdemo;

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
import android.content.res.ColorStateList;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.CompoundButtonCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private ScrollView managerScroll;
    private BottomSheetDialog editorSheet;
    private LinearLayout editorChoices;
    private String editorSearch = "";
    private boolean requireAll = true;
    private boolean untaggedOnly = false;
    private int sortMode = 0;
    private static final int UNCATEGORIZED_GROUP = 0;
    /** Collapsing is presentation-only; selected tags never lose filter state. */
    private final Map<Integer, Boolean> expandedGroups = new HashMap<>();

    private int bgColor;
    private int textColor;
    private int mutedColor;
    private int surfaceColor;
    private int borderColor;
    private int accentColor;
    private int primaryButtonColor;
    private int dialogBackgroundColor;
    private int negativeColor;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));
        super.onCreate(savedInstanceState);
        // The service accent is WHITE in NewPipe's dark theme: it cannot be used as
        // a white-text button fill. Use explicit NewPipe light/dark palette tokens.
        final boolean light = ThemeHelper.isLightThemeSelected(this);
        final boolean black = ThemeHelper.getThemeForService(this, -1)
                == R.style.BlackTheme;
        bgColor = getColor(light ? R.color.light_background_color
                : black ? R.color.black_background_color : R.color.dark_background_color);
        surfaceColor = getColor(light ? R.color.light_card_item_background_color
                : black ? R.color.black_card_item_background_color
                : R.color.dark_card_item_background_color);
        borderColor = getColor(light ? R.color.light_border_color
                : black ? R.color.black_border_color : R.color.dark_border_color);
        dialogBackgroundColor = getColor(light ? R.color.light_dialog_background_color
                : R.color.dark_dialog_background_color);
        textColor = light ? 0xFF212121 : Color.WHITE;
        mutedColor = light ? 0xFF616161 : 0xFFCCCCCC;
        accentColor = getColor(light ? R.color.light_settings_accent_color
                : R.color.dark_settings_accent_color);
        primaryButtonColor = getColor(R.color.ic_launcher_background);
        negativeColor = light ? 0xFF9A4500 : 0xFFFFC078;
        buildScreen();
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
        view.setBackground(rounded(primary ? primaryButtonColor : surfaceColor, 11,
                !primary));
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
        searchBox = themedInput();
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
        addAccordionControls(content);
        space(content, 9);
        filterSection = vertical();
        content.addView(filterSection, box(-1, -2));
        space(content, 18);

        final LinearLayout resultsHeader = horizontal();
        content.addView(resultsHeader, box(-1, 49));
        resultHeading = text("12 videos", 18, true, textColor);
        resultsHeader.addView(resultHeading, new LinearLayout.LayoutParams(0, -2, 1f));
        final Spinner sorting = new Spinner(this);
        final ArrayAdapter<String> sortAdapter = themedSpinnerAdapter(
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
        final int selectedBg = state == 1
                ? ColorUtils.blendARGB(surfaceColor, accentColor, 0.18f)
                : state == -1
                ? ColorUtils.blendARGB(surfaceColor, negativeColor, 0.18f)
                : surfaceColor;
        final String prefix = state == 1 ? "+ " : state == -1 ? "− " : "";
        // Keep text light/dark-theme readable; color encodes state in the border/tint.
        final TextView chip = text(prefix + name, 13, state != 0, textColor);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setMinHeight(dp(38));
        chip.setGravity(Gravity.CENTER);
        final GradientDrawable background = rounded(selectedBg, 22, false);
        background.setStroke(dp(1), state == 1 ? accentColor
                : state == -1 ? negativeColor : borderColor);
        chip.setBackground(background);
        chip.setFocusable(true);
        chip.setClickable(true);
        return chip;
    }

    private void renderFilters() {
        if (filterSection == null) {
            return;
        }
        allButton.setBackground(rounded(untaggedOnly ? surfaceColor : primaryButtonColor,
                10, untaggedOnly));
        allButton.setTextColor(untaggedOnly ? textColor : Color.WHITE);
        untaggedButton.setBackground(rounded(untaggedOnly ? primaryButtonColor : surfaceColor,
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

    /**
     * Default to one expanded category for discovery, avoiding a wall of tags.
     * @param groupId stable category identifier (zero for uncategorized)
     * @return whether the category is currently expanded
     */
    private boolean isExpanded(final int groupId) {
        final int firstId = model.categories.isEmpty() ? -1 : model.categories.get(0).id;
        return expandedGroups.getOrDefault(groupId, groupId == firstId);
    }

    private void addAccordionControls(final LinearLayout parent) {
        final LinearLayout row = horizontal();
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams rowParams = box(-1, 43);
        rowParams.topMargin = dp(4);
        parent.addView(row, rowParams);
        final TextView expand = accordionControl("Expand all");
        row.addView(expand, box(-2, 43));
        expand.setOnClickListener(v -> setAllExpanded(true));
        final TextView collapse = accordionControl("Collapse all");
        row.addView(collapse, box(-2, 43));
        collapse.setOnClickListener(v -> setAllExpanded(false));
    }

    private TextView accordionControl(final String title) {
        final TextView view = text(title, 13, true, accentColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(11), 0, dp(11), 0);
        view.setFocusable(true);
        view.setClickable(true);
        return view;
    }

    private void setAllExpanded(final boolean expanded) {
        for (final TaggingDemoModel.Category category : model.categories) {
            expandedGroups.put(category.id, expanded);
        }
        expandedGroups.put(UNCATEGORIZED_GROUP, expanded);
        renderFilters();
        if (managerSheet != null && managerSheet.isShowing()) {
            refreshManager();
        }
    }

    private void addFilterGroup(final String name, @Nullable final Integer categoryId) {
        final int count = model.categoryTagCount(categoryId);
        if (count == 0) {
            return;
        }
        final int groupId = categoryId == null ? UNCATEGORIZED_GROUP : categoryId;
        final boolean expanded = isExpanded(groupId);
        final List<TaggingDemoModel.Tag> active = model.visibleTags(categoryId, false);
        final LinearLayout header = horizontal();
        header.setPadding(dp(12), dp(5), dp(11), dp(5));
        header.setMinimumHeight(dp(49));
        header.setBackground(rounded(surfaceColor, 10, true));
        filterSection.addView(header, box(-1, -2));
        final TextView title = text((expanded ? "▾  " : "▸  ") + name,
                14, true, textColor);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final String countText = active.isEmpty() ? count + " tags"
                : active.size() + " active  ·  " + count + " tags";
        header.addView(text(countText, 11, false, mutedColor), box(-2, -2));
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(name + ", " + (expanded ? "expanded" : "collapsed")
                + ", " + countText + ". Tap to toggle category");
        header.setOnClickListener(v -> {
            expandedGroups.put(groupId, !expanded);
            renderFilters();
        });

        // Collapsed categories still show the actual +/- chips, not only a count.
        // A tap on one cycles it and a cleared tag disappears from the collapsed row.
        final List<TaggingDemoModel.Tag> visible = model.visibleTags(categoryId, expanded);
        if (!visible.isEmpty()) {
            space(filterSection, 8);
            final WrapTags chips = new WrapTags(this);
            filterSection.addView(chips, box(-1, -2));
            for (final TaggingDemoModel.Tag tag : visible) {
                final int state = model.selections.getOrDefault(tag.id, 0);
                final TextView chip = pill(tag.name, state);
                final ViewGroup.MarginLayoutParams params =
                        new ViewGroup.MarginLayoutParams(-2, -2);
                params.rightMargin = dp(7);
                params.bottomMargin = dp(7);
                chips.addView(chip, params);
                chip.setContentDescription(tag.name + ", " + (state == 1 ? "included"
                        : state == -1 ? "excluded" : "unselected")
                        + ". Tap to cycle include, exclude or clear");
                chip.setOnClickListener(v -> {
                    model.cycleFilter(tag.id);
                    renderFilters();
                    renderResults();
                });
            }
        }
        space(filterSection, expanded || !active.isEmpty() ? 10 : 7);
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
                .setTitle(actionName + " — Preview")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Dialog fields use NewPipe's own readable foreground colors.
     * @return an empty text input with theme-appropriate foreground and hint
     */
    private EditText themedInput() {
        final EditText field = new EditText(this);
        field.setTextColor(textColor);
        field.setHintTextColor(mutedColor);
        return field;
    }

    private ArrayAdapter<String> themedSpinnerAdapter(final String[] labels) {
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels) {
            @NonNull
            @Override
            public View getView(final int position, @Nullable final View convert,
                                @NonNull final ViewGroup parent) {
                final TextView label = (TextView) super.getView(position, convert, parent);
                label.setTextColor(textColor);
                label.setBackgroundColor(surfaceColor);
                label.setPadding(dp(8), dp(6), dp(5), dp(6));
                return label;
            }

            @NonNull
            @Override
            public View getDropDownView(final int position, @Nullable final View convert,
                                        @NonNull final ViewGroup parent) {
                final TextView label = (TextView) super.getDropDownView(position, convert,
                        parent);
                label.setTextColor(textColor);
                label.setBackgroundColor(dialogBackgroundColor);
                label.setPadding(dp(14), dp(12), dp(12), dp(12));
                return label;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void showBottomSheet(final BottomSheetDialog dialog,
                                 final LinearLayout contents) {
        final ScrollView scroll = sheetWith(contents);
        if (dialog == managerSheet) {
            managerScroll = scroll;
        }
        dialog.setContentView(scroll);
        dialog.show();
        // Material's default modal surface is white even when NewPipe uses a dark
        // AppCompat theme. Tint the actual sheet container, not only its children.
        final View container = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (container != null) {
            container.setBackground(rounded(dialogBackgroundColor, 16, false));
        }
    }

    private ScrollView sheetWith(final LinearLayout contents) {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackground(rounded(dialogBackgroundColor, 16, false));
        contents.setBackgroundColor(dialogBackgroundColor);
        contents.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void openEditor(final TaggingDemoModel.Media item) {
        if (editorSheet != null) {
            editorSheet.dismiss();
        }
        editorSheet = new BottomSheetDialog(this, ThemeHelper.getDialogTheme(this));
        final LinearLayout content = vertical();
        content.addView(text("Edit tags", 21, true, textColor));
        space(content, 5);
        content.addView(text(item.title, 15, false, mutedColor));
        space(content, 6);
        content.addView(text("Demo edits aren't saved to the real Library.",
                11, false, mutedColor));
        space(content, 14);
        final EditText search = themedInput();
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
        showBottomSheet(editorSheet, content);
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
            CompoundButtonCompat.setButtonTintList(checkbox,
                    ColorStateList.valueOf(accentColor));
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
        managerSheet = new BottomSheetDialog(this, ThemeHelper.getDialogTheme(this));
        managerContents = vertical();
        refreshManager();
        showBottomSheet(managerSheet, managerContents);
    }

    private void refreshManager() {
        if (managerContents == null) {
            return;
        }
        final int previousScrollY = managerScroll == null ? 0 : managerScroll.getScrollY();
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
        addAccordionControls(managerContents);
        space(managerContents, 8);

        for (final TaggingDemoModel.Category category : model.categories) {
            final int count = model.categoryTags(category).size();
            final LinearLayout head = horizontal();
            managerContents.addView(head, box(-1, 47));
            final boolean expanded = isExpanded(category.id);
            final int activeCount = model.visibleTags(category.id, false).size();
            final String name = (expanded ? "▾  " : "▸  ") + category.name
                    + " (" + count + ")" + (activeCount > 0 ? " · " + activeCount : "");
            final TextView title = text(name, 14, true, textColor);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(dp(44));
            title.setClickable(true);
            title.setFocusable(true);
            title.setContentDescription(category.name + ", tap to "
                    + (expanded ? "collapse" : "expand") + " tags");
            title.setOnClickListener(v -> toggleManagerGroup(category.id));
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
            if (expanded) {
                addManagedTags(category.id);
            } else {
                addManagerSelectionPreview(category.id);
            }
            space(managerContents, 12);
        }
        if (model.categoryTagCount(null) > 0) {
            final boolean expanded = isExpanded(UNCATEGORIZED_GROUP);
            final TextView heading = text((expanded ? "▾  " : "▸  ")
                    + "Uncategorized (" + model.categoryTagCount(null) + ")",
                    14, true, textColor);
            heading.setMinHeight(dp(44));
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setOnClickListener(v -> toggleManagerGroup(UNCATEGORIZED_GROUP));
            managerContents.addView(heading, box(-1, -2));
            if (expanded) {
                addManagedTags(null);
            } else {
                addManagerSelectionPreview(null);
            }
        }
        space(managerContents, 15);
        final TextView close = action("Done", true);
        managerContents.addView(close, box(-1, 48));
        close.setOnClickListener(v -> managerSheet.dismiss());
        if (managerScroll != null) {
            managerScroll.post(() -> managerScroll.scrollTo(0, previousScrollY));
        }
    }

    private void toggleManagerGroup(final int groupId) {
        expandedGroups.put(groupId, !isExpanded(groupId));
        renderFilters();
        refreshManager();
    }

    private void addManagerSelectionPreview(@Nullable final Integer categoryId) {
        final List<TaggingDemoModel.Tag> active = model.visibleTags(categoryId, false);
        if (active.isEmpty()) {
            return;
        }
        final WrapTags chips = new WrapTags(this);
        managerContents.addView(chips, box(-1, -2));
        for (final TaggingDemoModel.Tag tag : active) {
            final TextView chip = pill(tag.name, model.selections.getOrDefault(tag.id, 0));
            final ViewGroup.MarginLayoutParams params =
                    new ViewGroup.MarginLayoutParams(-2, -2);
            params.rightMargin = dp(7);
            params.bottomMargin = dp(7);
            chips.addView(chip, params);
            chip.setContentDescription("Manage " + tag.name + " tag");
            chip.setOnClickListener(v -> tagActions(tag));
        }
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        final EditText input = themedInput();
        input.setSingleLine(true);
        input.setText(initial);
        input.selectAll();
        final LinearLayout wrap = vertical();
        wrap.setPadding(dp(24), 0, dp(24), 0);
        wrap.addView(input, box(-1, -2));
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        final EditText name = themedInput();
        name.setSingleLine(true);
        name.setHint("Tag name");
        fields.addView(name, box(-1, 50));
        space(fields, 10);
        fields.addView(text("Category (optional)", 12, true, mutedColor));
        final Spinner categories = new Spinner(this);
        final ArrayAdapter<String> categoryAdapter =
                themedSpinnerAdapter(categoryLabels());
        categories.setAdapter(categoryAdapter);
        fields.addView(categories, box(-1, 48));
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
        new AlertDialog.Builder(this, ThemeHelper.getDialogTheme(this))
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
                    expandedGroups.clear();
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
