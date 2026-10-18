package org.schabi.newpipe.local.playlist;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.sqlite.db.SupportSQLiteStatement;

import org.schabi.newpipe.custom.library.LibraryRepository;
import org.schabi.newpipe.custom.tagging.TagRuntime;
import org.schabi.newpipe.custom.library.PlaylistEditGuard;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class LocalPlaylistManager {
    private static final long THUMBNAIL_ID_LEAVE_UNCHANGED = -2;

    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final PlaylistDAO playlistTable;
    private final PlaylistStreamDAO playlistStreamTable;

    public LocalPlaylistManager(final AppDatabase db) {
        database = db;
        streamTable = db.streamDAO();
        playlistTable = db.playlistDAO();
        playlistStreamTable = db.playlistStreamDAO();
    }

    public Maybe<List<Long>> createPlaylist(final String name, final List<StreamEntity> streams) {
        // Disallow creation of empty playlists
        if (streams.isEmpty()) {
            return Maybe.empty();
        }

        // Save to the database directly.
        // Make sure the new playlist is always on the top of bookmark.
        // The index will be reassigned to non-negative number in BookmarkFragment.
        return Maybe.fromCallable(() -> TagRuntime.mutate(database,
                () -> database.runInTransaction(() -> {
                    // The protected Unavailable name is reserved across UI and AI.
                    if (name != null && name.trim().equalsIgnoreCase(
                            org.schabi.newpipe.custom.library.UnavailableRepository.NAME)) {
                        throw new IllegalArgumentException(
                                "Unavailable is reserved for the protected playlist");
                    }
                    final List<Long> streamIds = streamTable.upsertAll(streams);
                    final PlaylistEntity newPlaylist = new PlaylistEntity(name, false,
                            streamIds.get(0), -1);

                    return insertJoinEntities(playlistTable.insert(newPlaylist),
                            streamIds, 0);
                }
        ))).subscribeOn(Schedulers.io());
    }

    public Maybe<List<Long>> appendToPlaylist(final long playlistId,
                                              final List<StreamEntity> streams) {
        return Maybe.fromCallable(() -> TagRuntime.mutate(database,
                () -> database.runInTransaction(() -> {
            PlaylistEditGuard.requireExisting(database, playlistId);
            final List<Long> streamIds = streamTable.upsertAll(streams);
            if (LibraryRepository.isLibrary(database, playlistId)) {
                return LibraryRepository.appendDirectly(database, playlistId, streamIds);
            }
            // Unavailable is a set of saved streams, not a playlist of repeated copies.
            if (org.schabi.newpipe.custom.library.UnavailableRepository.isBound(
                    database, playlistId)) {
                final List<Long> missing = streamIds.stream().distinct()
                        .filter(id -> !org.schabi.newpipe.custom.library.UnavailableRepository
                                .isMarked(database, id)).toList();
                final List<Long> inserted = insertJoinEntities(playlistId, missing,
                        LibraryRepository.nextJoinIndex(database, playlistId));
                LibraryRepository.refreshAutomaticThumbnail(database, playlistId);
                return inserted;
            }
            // Derive the next position *inside* the same transaction as insertion.
            final List<Long> inserted = insertJoinEntities(playlistId, streamIds,
                    LibraryRepository.nextJoinIndex(database, playlistId));
            LibraryRepository.refreshAutomaticThumbnail(database, playlistId);
            return inserted;
        }))).subscribeOn(Schedulers.io());
    }

    private List<Long> insertJoinEntities(final long playlistId, final List<Long> streamIds,
                                          final int indexOffset) {

        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());

        for (int index = 0; index < streamIds.size(); index++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(index),
                    index + indexOffset));
        }
        final List<Long> inserted = playlistStreamTable.insertAll(joinEntities);
        LibraryRepository.afterPlaylistInsert(database, playlistId, streamIds);
        return inserted;
    }

    public Completable updateJoin(final long playlistId, final List<Long> streamIds) {
        return updateJoin(playlistId, streamIds, false);
    }

    public Completable updateJoin(final long playlistId, final List<Long> streamIds,
                                  final boolean refreshAutomaticThumbnail) {
        return updateJoinChecked(playlistId, new ArrayList<>(streamIds), null,
                refreshAutomaticThumbnail);
    }

    public Completable updateJoinIfUnchanged(final long playlistId,
                                             final List<Long> streamIds,
                                             final List<Long> expectedStreamIds,
                                             final boolean refreshAutomaticThumbnail) {
        return updateJoinChecked(playlistId, new ArrayList<>(streamIds),
                new ArrayList<>(expectedStreamIds), refreshAutomaticThumbnail);
    }

    private Completable updateJoinChecked(final long playlistId,
                                           final List<Long> streamIds,
                                           @Nullable final List<Long> expectedStreamIds,
                                           final boolean refreshAutomaticThumbnail) {
        return Completable.fromRunnable(() -> TagRuntime.runMutation(database,
                () -> database.runInTransaction(() ->
                updateJoinInTransaction(playlistId, streamIds, expectedStreamIds,
                        refreshAutomaticThumbnail)))).subscribeOn(Schedulers.io());
    }

    /**
     * Saves on an IO worker independently of a Fragment view subscription.
     * @param playlistId the local playlist being edited
     * @param expectedStreamIds the previously observed ordered membership
     * @param streamIds the desired ordered membership
     * @return the membership actually committed, including Library protections
     */
    @WorkerThread
    public List<Long> savePlaylistSnapshot(final long playlistId,
                                          final List<Long> expectedStreamIds,
                                          final List<Long> streamIds) {
        return TagRuntime.mutate(database,
                () -> database.runInTransaction(() -> {
            updateJoinInTransaction(playlistId, streamIds, expectedStreamIds, true);
            return PlaylistEditGuard.readCurrentIds(database, playlistId);
        }));
    }

    private void updateJoinInTransaction(final long playlistId,
                                          final List<Long> streamIds,
                                          @Nullable final List<Long> expectedStreamIds,
                                          final boolean refreshAutomaticThumbnail) {
        if (expectedStreamIds != null) {
            PlaylistEditGuard.verifyUnchanged(database, playlistId, expectedStreamIds);
        }
        // A full UI replacement cannot implicitly remove canonical Library members.
        if (!LibraryRepository.rewriteLibraryWithoutRemoving(database, playlistId, streamIds)) {
            final List<Long> safeIds = LibraryRepository.filterStaleOrdinaryUpdate(
                    database, playlistId, streamIds);
            final List<Long> uniqueIds =
                    org.schabi.newpipe.custom.library.UnavailableRepository.isBound(
                            database, playlistId) ? safeIds.stream().distinct().toList() : safeIds;
            final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(uniqueIds.size());
            for (int i = 0; i < uniqueIds.size(); i++) {
                joinEntities.add(new PlaylistStreamEntity(playlistId, uniqueIds.get(i), i));
            }
            playlistStreamTable.deleteBatch(playlistId);
            playlistStreamTable.insertAll(joinEntities);
            if (refreshAutomaticThumbnail) {
                LibraryRepository.refreshAutomaticThumbnail(database, playlistId);
            }
        }
    }

    public Completable updatePlaylists(final List<PlaylistMetadataEntry> updateItems,
                                       final List<Long> deletedItems) {
        final List<PlaylistEntity> items = new ArrayList<>(updateItems.size());
        for (final PlaylistMetadataEntry item : updateItems) {
            items.add(new PlaylistEntity(item));
        }
        return Completable.fromRunnable(() -> TagRuntime.runMutation(database,
                () -> database.runInTransaction(() -> {
            for (final Long uid : deletedItems) {
                LibraryRepository.requireNotLibrary(database, uid);
                playlistTable.deletePlaylist(uid);
            }
            for (final PlaylistEntity item : items) {
                // This is a bookmark ORDER save, not a metadata replacement. A stale
                // screen must not undo a rename/thumbnail change or recreate a deleted list.
                database.getOpenHelper().getWritableDatabase().execSQL(
                        "UPDATE playlists SET display_index = ? WHERE uid = ?",
                        new Object[]{item.getDisplayIndex(), item.getUid()});
            }
        }))).subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getDistinctPlaylistStreams(final long playlistId) {
        return playlistStreamTable
                .getStreamsWithoutDuplicates(playlistId).subscribeOn(Schedulers.io());
    }

    /**
     * Get playlists with attached information about how many times the provided stream is already
     * contained in each playlist.
     *
     * @param streamUrl the stream url for which to check for duplicates
     * @return a list of {@link PlaylistDuplicatesEntry}
     */
    public Flowable<List<PlaylistDuplicatesEntry>> getPlaylistDuplicates(final String streamUrl) {
        return playlistStreamTable.getPlaylistDuplicatesMetadata(streamUrl)
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistMetadataEntry>> getPlaylists() {
        return playlistStreamTable.getPlaylistMetadata().subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getPlaylistStreams(final long playlistId) {
        return playlistStreamTable.getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io());
    }

    public Maybe<Integer> renamePlaylist(final long playlistId, final String name) {
        return modifyPlaylist(playlistId, name, THUMBNAIL_ID_LEAVE_UNCHANGED, false);
    }

    public Maybe<Integer> changePlaylistThumbnail(final long playlistId,
                                                  final long thumbnailStreamId,
                                                  final boolean isPermanent) {
        return modifyPlaylist(playlistId, null, thumbnailStreamId, isPermanent);
    }

    public Maybe<Integer> unsetPlaylistThumbnail(final long playlistId) {
        return Maybe.fromCallable(() -> TagRuntime.mutate(database,
                () -> database.runInTransaction(() -> {
            // Select from current membership while holding the writer lock, not a stale UI row.
            try (SupportSQLiteStatement statement = database.getOpenHelper()
                    .getWritableDatabase().compileStatement(
                            "UPDATE playlists SET is_thumbnail_permanent = 0, "
                                    + "thumbnail_stream_id = COALESCE((SELECT stream_id "
                                    + "FROM playlist_stream_join WHERE playlist_id = playlists.uid "
                                    + "ORDER BY join_index LIMIT 1), -1) WHERE uid = ?")) {
                statement.bindLong(1, playlistId);
                return statement.executeUpdateDelete();
            }
        }))).subscribeOn(Schedulers.io());
    }

    public long getPlaylistThumbnailStreamId(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0).getThumbnailStreamId();
    }

    public boolean getIsPlaylistThumbnailPermanent(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0)
                .isThumbnailPermanent();
    }

    public long getAutomaticPlaylistThumbnailStreamId(final long playlistId) {
        final long streamId = playlistStreamTable.getAutomaticThumbnailStreamId(playlistId)
                .blockingFirst();
        if (streamId < 0) {
            return PlaylistEntity.DEFAULT_THUMBNAIL_ID;
        }
        return streamId;
    }

    private Maybe<Integer> modifyPlaylist(final long playlistId,
                                          @Nullable final String name,
                                          final long thumbnailStreamId,
                                          final boolean isPermanent) {
        return Maybe.fromCallable(() -> TagRuntime.mutate(database,
                () -> database.runInTransaction(() -> {
            if (name != null) {
                LibraryRepository.requireNotProtectedForRename(database, playlistId, name);
                try (SupportSQLiteStatement statement = database.getOpenHelper()
                        .getWritableDatabase().compileStatement(
                                "UPDATE playlists SET name = ? WHERE uid = ?")) {
                    statement.bindString(1, name);
                    statement.bindLong(2, playlistId);
                    return statement.executeUpdateDelete();
                }
            }
            if (thumbnailStreamId == THUMBNAIL_ID_LEAVE_UNCHANGED) {
                return 0;
            }
            PlaylistEditGuard.verifyThumbnail(database, playlistId, thumbnailStreamId);
            try (SupportSQLiteStatement statement = database.getOpenHelper()
                    .getWritableDatabase().compileStatement(
                            "UPDATE playlists SET thumbnail_stream_id = ?, "
                                    + "is_thumbnail_permanent = ? WHERE uid = ?")) {
                statement.bindLong(1, thumbnailStreamId);
                statement.bindLong(2, isPermanent ? 1 : 0);
                statement.bindLong(3, playlistId);
                return statement.executeUpdateDelete();
            }
        }))).subscribeOn(Schedulers.io());
    }

    public Maybe<Boolean> hasPlaylists() {
        return playlistTable.getCount()
                .firstElement()
                .map(count -> count > 0)
                .subscribeOn(Schedulers.io());
    }
}
