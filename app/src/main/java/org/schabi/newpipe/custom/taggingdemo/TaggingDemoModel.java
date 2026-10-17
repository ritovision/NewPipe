/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.taggingdemo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Throwaway in-memory model for the native tagging UX spike.
 * No Room, Library, preferences, player, or persistent storage access.
 */
public final class TaggingDemoModel {
    public static final class Category {
        public final int id;
        public String name;

        Category(final int id, final String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Tag {
        public final int id;
        public String name;
        public Integer categoryId;

        Tag(final int id, final String name, final Integer categoryId) {
            this.id = id;
            this.name = name;
            this.categoryId = categoryId;
        }
    }

    public static final class Media {
        public final String title;
        public final String uploader;
        public final Set<Integer> tagIds = new LinkedHashSet<>();

        Media(final String title, final String uploader) {
            this.title = title;
            this.uploader = uploader;
        }
    }

    public final List<Category> categories = new ArrayList<>();
    public final Map<Integer, Tag> tags = new LinkedHashMap<>();
    public final List<Media> media = new ArrayList<>();
    /** +1 include, -1 exclude; no entry means neutral. */
    public final Map<Integer, Integer> selections = new LinkedHashMap<>();
    private int nextId = 1;

    public TaggingDemoModel() {
        reset();
    }

    public void reset() {
        categories.clear();
        tags.clear();
        media.clear();
        selections.clear();
        nextId = 1;

        final int genre = addCategory("Genre").id;
        final int mood = addCategory("Mood").id;
        final int era = addCategory("Era").id;
        final int format = addCategory("Format").id;
        final int hipHop = addTag("Hip-Hop", genre).id;
        final int rock = addTag("Rock", genre).id;
        final int jazz = addTag("Jazz", genre).id;
        final int ambient = addTag("Ambient", genre).id;
        final int loFi = addTag("Lo-Fi", genre).id;
        final int energetic = addTag("Energetic", mood).id;
        final int chill = addTag("Chill", mood).id;
        final int years = addTag("2000s", era).id;
        final int live = addTag("Live", format).id;
        addTag("Funny", null);

        addMedia("Midnight Drive", "Studio Echo", hipHop, energetic);
        addMedia("Saturday Replay", "Gold Tape", hipHop, years);
        addMedia("Vintage Jazz Session", "City Trio", jazz, chill);
        addMedia("Late Night Lo-Fi", "Low Current", loFi, chill);
        addMedia("Rock Reunion", "Neon Stage", rock, live);
        addMedia("Fast Lane", "Skyline", hipHop, energetic, years);
        addMedia("Morning Coffee", "June Radio", jazz, chill);
        // Genre-only so deleting Genre visibly moves this video to Untagged.
        addMedia("Ambient Waves", "Tidepool", ambient);
        addMedia("Festival Set", "Junction", rock, live);
        addMedia("Untitled Session", "Small Room");
        addMedia("Live at the Market", "Street Session");
        addMedia("Video Essay: Sounds of 2008", "The Archive");
    }

    private void addMedia(final String title, final String uploader, final int... ids) {
        final Media item = new Media(title, uploader);
        for (final int id : ids) {
            item.tagIds.add(id);
        }
        media.add(item);
    }

    public Category addCategory(final String requested) {
        final String name = cleanName(requested);
        for (final Category category : categories) {
            if (category.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Category already exists");
            }
        }
        final Category added = new Category(nextId++, name);
        categories.add(added);
        return added;
    }

    public Tag addTag(final String requested, final Integer categoryId) {
        final String name = cleanName(requested);
        for (final Tag tag : tags.values()) {
            if (tag.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Tag already exists");
            }
        }
        checkCategory(categoryId);
        final Tag added = new Tag(nextId++, name, categoryId);
        tags.put(added.id, added);
        return added;
    }

    public void renameCategory(final Category category, final String requested) {
        final String name = cleanName(requested);
        for (final Category other : categories) {
            if (other != category && other.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Category already exists");
            }
        }
        category.name = name;
    }

    public void renameTag(final Tag tag, final String requested) {
        final String name = cleanName(requested);
        for (final Tag other : tags.values()) {
            if (other != tag && other.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Tag already exists");
            }
        }
        tag.name = name;
    }

    private static String cleanName(final String value) {
        final String result = value == null ? "" : value.trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Enter a name");
        }
        if (result.length() > 60) {
            throw new IllegalArgumentException("Name must be 60 characters or less");
        }
        return result;
    }

    private void checkCategory(final Integer categoryId) {
        if (categoryId == null) {
            return;
        }
        for (final Category category : categories) {
            if (category.id == categoryId) {
                return;
            }
        }
        throw new IllegalArgumentException("Category no longer exists");
    }

    public void moveTag(final Tag tag, final Integer categoryId) {
        checkCategory(categoryId);
        tag.categoryId = categoryId;
    }

    public int assignmentCount(final Set<Integer> tagIds) {
        int count = 0;
        for (final Media item : media) {
            for (final Integer tagId : tagIds) {
                if (item.tagIds.contains(tagId)) {
                    count++;
                }
            }
        }
        return count;
    }

    public Set<Integer> categoryTags(final Category category) {
        final Set<Integer> ids = new LinkedHashSet<>();
        for (final Tag tag : tags.values()) {
            if (category.id == (tag.categoryId == null ? -1 : tag.categoryId)) {
                ids.add(tag.id);
            }
        }
        return ids;
    }

    public void deleteCategory(final Category category) {
        final Set<Integer> removed = categoryTags(category);
        for (final Integer id : removed) {
            deleteTagById(id);
        }
        categories.remove(category);
    }

    public void deleteTag(final Tag tag) {
        deleteTagById(tag.id);
    }

    private void deleteTagById(final Integer id) {
        tags.remove(id);
        selections.remove(id);
        for (final Media item : media) {
            item.tagIds.remove(id);
        }
    }

    public void cycleFilter(final int id) {
        if (!tags.containsKey(id)) {
            return;
        }
        final int old = selections.getOrDefault(id, 0);
        if (old == 0) {
            selections.put(id, 1);
        } else if (old == 1) {
            selections.put(id, -1);
        } else {
            selections.remove(id);
        }
    }

    public List<Media> matching(final String query, final boolean allIncluded,
                                final boolean untaggedOnly, final int sort) {
        final String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final List<Media> result = new ArrayList<>();
        for (final Media item : media) {
            if (!term.isEmpty()
                    && !item.title.toLowerCase(Locale.ROOT).contains(term)
                    && !item.uploader.toLowerCase(Locale.ROOT).contains(term)) {
                continue;
            }
            if (untaggedOnly && !item.tagIds.isEmpty()) {
                continue;
            }
            if (!untaggedOnly && !matchesTags(item, allIncluded)) {
                continue;
            }
            result.add(item);
        }
        if (sort == 1) {
            result.sort(Comparator.comparing(m -> m.title.toLowerCase(Locale.ROOT)));
        } else if (sort == 2) {
            result.sort(Comparator.comparing((Media m) ->
                    m.title.toLowerCase(Locale.ROOT)).reversed());
        }
        return result;
    }

    private boolean matchesTags(final Media item, final boolean allIncluded) {
        boolean sawInclude = false;
        boolean hasInclude = false;
        for (final Map.Entry<Integer, Integer> selection : selections.entrySet()) {
            if (selection.getValue() == -1 && item.tagIds.contains(selection.getKey())) {
                return false;
            }
            if (selection.getValue() == 1) {
                sawInclude = true;
                if (allIncluded && !item.tagIds.contains(selection.getKey())) {
                    return false;
                }
                if (item.tagIds.contains(selection.getKey())) {
                    hasInclude = true;
                }
            }
        }
        return !sawInclude || allIncluded || hasInclude;
    }
}
