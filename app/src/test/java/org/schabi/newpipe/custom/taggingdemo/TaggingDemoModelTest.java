/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.taggingdemo;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests the disposable UI demo model; never touches Android storage or the real Library. */
public final class TaggingDemoModelTest {
    @Test
    public void untaggedIsDerivedAndUpdatesWhenTagChanges() {
        final TaggingDemoModel model = new TaggingDemoModel();
        assertEquals(12, model.media.size());
        assertEquals(3, model.matching("", true, true, 0).size());
        final TaggingDemoModel.Media item = model.media.get(9);
        final int tag = model.tags.values().iterator().next().id;
        item.tagIds.add(tag);
        assertEquals(2, model.matching("", true, true, 0).size());
        item.tagIds.remove(tag);
        assertEquals(3, model.matching("", true, true, 0).size());
    }

    @Test
    public void deletingCategoryDeletesTagsAndAssignmentsNotVideosOrPlaylists() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final TaggingDemoModel.Category genre = model.categories.get(0);
        final Set<Integer> tagIds = model.categoryTags(genre);
        assertEquals(5, tagIds.size());
        assertTrue(model.assignmentCount(tagIds) > 0);
        model.deleteCategory(genre);
        assertEquals(12, model.media.size());
        assertEquals(5, model.tags.size());
        for (final TaggingDemoModel.Media media : model.media) {
            for (final Integer tagId : tagIds) {
                assertFalse(media.tagIds.contains(tagId));
            }
        }
        assertEquals(4, model.matching("", true, true, 0).size());
    }

    @Test
    public void andOrAndNotFiltersProduceCorrectSets() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final int hipHop = model.tags.values().iterator().next().id;
        int energetic = -1;
        int live = -1;
        for (final TaggingDemoModel.Tag tag : model.tags.values()) {
            if (tag.name.equals("Energetic")) {
                energetic = tag.id;
            }
            if (tag.name.equals("Live")) {
                live = tag.id;
            }
        }
        model.selections.put(hipHop, 1);
        model.selections.put(energetic, 1);
        assertEquals(2, model.matching("", true, false, 0).size());
        assertEquals(3, model.matching("", false, false, 0).size());
        model.selections.clear();
        model.selections.put(live, -1);
        assertEquals(10, model.matching("", true, false, 0).size());
    }

    @Test
    public void collapsedCategoryShowsOnlyIncludedAndExcludedTags() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final Integer genre = model.categories.get(0).id;
        final int hipHop = model.visibleTags(genre, true).get(0).id;
        final int rock = model.visibleTags(genre, true).get(1).id;
        assertEquals(5, model.categoryTagCount(genre));
        assertTrue(model.visibleTags(genre, false).isEmpty());
        model.cycleFilter(hipHop); // Include
        model.cycleFilter(rock); // Include
        model.cycleFilter(rock); // Exclude
        assertEquals(2, model.visibleTags(genre, false).size());
        assertEquals(1, model.selections.get(hipHop).intValue());
        assertEquals(-1, model.selections.get(rock).intValue());
        assertEquals(5, model.visibleTags(genre, true).size());
        model.cycleFilter(rock); // Clear exclusion
        assertEquals(1, model.visibleTags(genre, false).size());
        model.cycleFilter(hipHop); // Exclude
        assertEquals(1, model.visibleTags(genre, false).size());
        model.cycleFilter(hipHop); // Clear
        assertTrue(model.visibleTags(genre, false).isEmpty());
    }

    @Test
    public void collapsedUncategorizedAndMovedTagsStillDisplaySelections() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final TaggingDemoModel.Tag funny = model.visibleTags(null, true).get(0);
        model.cycleFilter(funny.id);
        assertEquals(1, model.visibleTags(null, false).size());
        final Integer genre = model.categories.get(0).id;
        model.moveTag(funny, genre);
        assertTrue(model.visibleTags(null, false).isEmpty());
        assertTrue(model.visibleTags(genre, false).contains(funny));
        model.deleteCategory(model.categories.get(0));
        assertTrue(model.visibleTags(genre, false).isEmpty());
        assertFalse(model.selections.containsKey(funny.id));
    }

    @Test
    public void sortingAndSearchAreScopedToSyntheticLibrary() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final List<TaggingDemoModel.Media> result = model.matching("city trio", true, false, 1);
        assertEquals(1, result.size());
        assertEquals("Vintage Jazz Session", result.get(0).title);
        final List<TaggingDemoModel.Media> sorted = model.matching("", true, false, 1);
        assertEquals("Ambient Waves", sorted.get(0).title);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateTagNamesAreNotAllowedAcrossCategories() {
        final TaggingDemoModel model = new TaggingDemoModel();
        model.addTag("hip-hop", model.categories.get(1).id);
    }

    @Test
    public void deletingLastTagNeverDeletesMedia() {
        final TaggingDemoModel model = new TaggingDemoModel();
        final TaggingDemoModel.Tag tag = model.addTag("Demo-only tag", null);
        final TaggingDemoModel.Media media = model.media.get(9);
        media.tagIds.add(tag.id);
        model.deleteTag(tag);
        assertTrue(media.tagIds.isEmpty());
        assertTrue(model.media.contains(media));
    }
}
