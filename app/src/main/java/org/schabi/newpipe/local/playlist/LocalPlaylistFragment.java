package org.schabi.newpipe.local.playlist;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static org.schabi.newpipe.error.ErrorUtil.showUiErrorSnackbar;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.local.playlist.ExportPlaylistKt.export;
import static org.schabi.newpipe.local.playlist.PlayListShareMode.JUST_URLS;
import static org.schabi.newpipe.local.playlist.PlayListShareMode.WITH_TITLES;
import static org.schabi.newpipe.local.playlist.PlayListShareMode.YOUTUBE_TEMP_PLAYLIST;
import static org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout;


import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.evernote.android.state.State;
import com.google.android.material.snackbar.Snackbar;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.custom.library.LibraryRepository;
import org.schabi.newpipe.custom.library.UnavailableRepository;
import org.schabi.newpipe.custom.library.PlaylistEditConflictException;
import org.schabi.newpipe.custom.library.PlaylistSaveQueue;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.DialogEditTextBinding;
import org.schabi.newpipe.databinding.LocalPlaylistHeaderBinding;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.MainFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.PlayButtonHelper;
import org.schabi.newpipe.util.debounce.DebounceSavable;
import org.schabi.newpipe.util.debounce.DebounceSaver;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class LocalPlaylistFragment extends BaseLocalListFragment<List<PlaylistStreamEntry>, Void>
        implements PlaylistControlViewHolder, DebounceSavable {

    private static final int MINIMUM_INITIAL_DRAG_VELOCITY = 12;
    @State
    protected Long playlistId;
    @State
    protected String name;
    @State
    Parcelable itemsListState;

    private LocalPlaylistHeaderBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;

    private ItemTouchHelper itemTouchHelper;

    private LocalPlaylistManager playlistManager;

    private boolean isProtectedLibrary() {
        return LibraryRepository.isUiProtected(
                NewPipeDatabase.getInstance(requireContext()), playlistId, name);
    }

    private boolean isUnavailable() {
        return playlistId != null && UnavailableRepository.isBound(
                NewPipeDatabase.getInstance(requireContext()), playlistId);
    }

    private LocalPlaylistViewModel viewModel;
    private Subscription databaseSubscription;

    private CompositeDisposable disposables;
    private Disposable libraryIdentitySubscription;
    private Disposable unavailableIdentitySubscription;
    private boolean hasUnavailableItems;

    /** Whether the playlist has been fully loaded from db. */
    private AtomicBoolean isLoadingComplete;
    /** Used to debounce saving playlist edits to disk. */
    private DebounceSaver debounceSaver;
    private List<Long> loadedStreamIds = new ArrayList<>();
    /** A mark changed Unavailable while the playlist editor/save queue was dirty. */
    private boolean unavailableMarkRefreshPending;
    private PlaylistSaveQueue playlistSaveQueue;
    private long playlistSaveListenerGeneration;
    private long playlistLoadGeneration;
    private final Handler playlistSaveHandler = new Handler(Looper.getMainLooper());

    /**
     * The pager adapter that the fragment is created from when it is used as frontpage, i.e.
     * {@link #useAsFrontPage} is {@link true}.
     */
    @Nullable
    private MainFragment.SelectedTabsPagerAdapter tabsPagerAdapter = null;

    public static LocalPlaylistFragment getInstance(final long playlistId, final String name) {
        final var instance = new LocalPlaylistFragment();
        instance.setInitialData(playlistId, name);
        return instance;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistManager = new LocalPlaylistManager(NewPipeDatabase.getInstance(requireContext()));

        viewModel = new ViewModelProvider(this).get(LocalPlaylistViewModel.class);
        viewModel.getWorkState().observe(this, state -> {
            if (state instanceof LocalPlaylistViewModel.WorkState.Loading) {
                showLoading();
            } else {
                hideLoading();
                if (state instanceof LocalPlaylistViewModel.WorkState.Error error) {
                    showPlaylistSaveError(error.getThrowable());
                }
            }
        });

        disposables = new CompositeDisposable();

        isLoadingComplete = new AtomicBoolean();
        debounceSaver = new DebounceSaver(this);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (isLoadingComplete != null) {
            isLoadingComplete.set(false);
        }
        attachPlaylistSaveListener();
        // Separate from load disposables: refreshing playlist rows must not
        // unsubscribe the menu from late startup/rebind identity changes.
        libraryIdentitySubscription = LibraryRepository
                .observeLibraryId(NewPipeDatabase.getInstance(requireContext()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(id -> {
                    if (getActivity() != null) {
                        getActivity().invalidateOptionsMenu();
                    }
                }, error -> showUiErrorSnackbar(this, "Updating Library menus", error));
        unavailableIdentitySubscription = UnavailableRepository
                .observeId(NewPipeDatabase.getInstance(requireContext()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(id -> invalidatePlaylistActions(),
                        error -> showUiErrorSnackbar(this, "Updating Unavailable menus", error));
    }

    private void invalidatePlaylistActions() {
        if (getActivity() != null) {
            getActivity().invalidateOptionsMenu();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Views
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);

        if (headerBinding != null) {
            headerBinding.playlistTitleView.setText(title);
        }
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        setTitle(name);
    }

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        headerBinding = LocalPlaylistHeaderBinding.inflate(activity.getLayoutInflater(), itemsList,
            false);
        playlistControlBinding = headerBinding.playlistControl;

        headerBinding.playlistTitleView.setSelected(true);

        return headerBinding::getRoot;
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        headerBinding.playlistTitleView.setOnClickListener(view -> {
            if (!isProtectedLibrary() && !isUnavailable()) {
                createRenameDialog();
            }
        });

        itemTouchHelper = new ItemTouchHelper(getItemTouchCallback());
        itemTouchHelper.attachToRecyclerView(itemsList);

        itemListAdapter.setSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistStreamEntry entry) {
                    final StreamEntity item = entry.getStreamEntity();
                    NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                            item.getServiceId(), item.getUrl(), item.getTitle(), null, false);
                }
            }

            @Override
            public void held(final LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistStreamEntry) {
                    showInfoItemDialog((PlaylistStreamEntry) selectedItem);
                }
            }

            @Override
            public void drag(final LocalItem selectedItem,
                             final RecyclerView.ViewHolder viewHolder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(viewHolder);
                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Loading
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void showLoading() {
        super.showLoading();
        if (headerBinding != null) {
            animate(headerBinding.getRoot(), false, 200);
            animate(playlistControlBinding.getRoot(), false, 200);
        }
    }

    @Override
    public void hideLoading() {
        super.hideLoading();
        if (headerBinding != null) {
            animate(headerBinding.getRoot(), true, 200);
            animate(playlistControlBinding.getRoot(), true, 200);
        }
    }

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);
        if (disposables != null) {
            disposables.clear();
        }
        if (databaseSubscription != null) {
            databaseSubscription.cancel();
            databaseSubscription = null;
        }
        isLoadingComplete.set(false);
        final long generation = ++playlistLoadGeneration;
        final Runnable load = () -> playlistSaveHandler.post(() -> {
            if (generation != playlistLoadGeneration || getView() == null
                    || playlistManager == null || disposables == null) {
                return;
            }
            if (debounceSaver != null) {
                disposables.add(debounceSaver.getDebouncedSaver());
                debounceSaver.setNoChangesToSave();
            }
            playlistManager.getPlaylistStreams(playlistId)
                    .onBackpressureLatest()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(getPlaylistObserver());
        });
        if (playlistSaveQueue == null) {
            load.run();
        } else {
            // A retained save may belong to the previous view. Never expose its stale
            // predecessor as a new editable baseline and overwrite the retained intent.
            playlistSaveQueue.whenIdle(load);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Destruction
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onPause() {
        super.onPause();
        itemsListState = itemsList.getLayoutManager().onSaveInstanceState();

        // Save on exit
        saveImmediate();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_local_playlist, menu);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final boolean ordinaryPlaylist = !isProtectedLibrary() && !isUnavailable();
        for (final int id : new int[]{R.id.menu_item_rename_playlist,
                R.id.menu_item_remove_watched, R.id.menu_item_remove_duplicates}) {
            final MenuItem action = menu.findItem(id);
            if (action != null) {
                action.setVisible(ordinaryPlaylist);
            }
        }
        for (final int id : new int[]{R.id.menu_item_clear_unavailable,
                R.id.menu_item_remove_all_unavailable}) {
            final MenuItem action = menu.findItem(id);
            if (action != null) {
                action.setVisible(isUnavailable());
                action.setEnabled(isUnavailable() && hasUnavailableItems);
            }
        }
    }

    @Override
    public void onDestroyView() {
        // Capture the newest edit even if an earlier save is still executing.
        saveImmediate();
        if (isLoadingComplete != null) {
            isLoadingComplete.set(false);
        }
        playlistLoadGeneration++;
        playlistSaveListenerGeneration++;
        unavailableMarkRefreshPending = false;
        if (playlistSaveQueue != null) {
            playlistSaveQueue.setListener(null);
        }
        if (libraryIdentitySubscription != null) {
            libraryIdentitySubscription.dispose();
            libraryIdentitySubscription = null;
        }
        if (unavailableIdentitySubscription != null) {
            unavailableIdentitySubscription.dispose();
            unavailableIdentitySubscription = null;
        }
        super.onDestroyView();

        if (itemListAdapter != null) {
            itemListAdapter.unsetSelectedListener();
        }

        headerBinding = null;
        playlistControlBinding = null;


        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
        if (disposables != null) {
            disposables.clear();
        }

        databaseSubscription = null;
        itemTouchHelper = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (debounceSaver != null) {
            debounceSaver.getDebouncedSaveSignal().onComplete();
        }
        if (disposables != null) {
            disposables.dispose();
        }
        if (tabsPagerAdapter != null) {
            tabsPagerAdapter.getLocalPlaylistFragments().remove(this);
        }

        debounceSaver = null;
        playlistManager = null;
        disposables = null;

        isLoadingComplete = null;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Playlist Stream Loader
    ///////////////////////////////////////////////////////////////////////////

    private Subscriber<List<PlaylistStreamEntry>> getPlaylistObserver() {
        return new Subscriber<>() {
            @Override
            public void onSubscribe(final Subscription s) {
                showLoading();
                isLoadingComplete.set(false);

                if (databaseSubscription != null) {
                    databaseSubscription.cancel();
                }
                databaseSubscription = s;
                databaseSubscription.request(1);
            }

            @Override
            public void onNext(final List<PlaylistStreamEntry> streams) {
                // Menu availability follows the committed DB, even while the
                // editor intentionally retains a pending reordered snapshot.
                hasUnavailableItems = !streams.isEmpty();
                invalidatePlaylistActions();
                // Skip handling the result after it has been modified
                if (debounceSaver == null || !debounceSaver.getIsModified()) {
                    loadedStreamIds = playlistStreamIds(streams);
                    if (playlistSaveQueue != null) {
                        playlistSaveQueue.acceptLoadedBaseline(loadedStreamIds);
                    }
                    handleResult(streams);
                    isLoadingComplete.set(true);
                }

                if (databaseSubscription != null) {
                    databaseSubscription.request(1);
                }
            }

            @Override
            public void onError(final Throwable exception) {
                showError(new ErrorInfo(exception, UserAction.REQUESTED_BOOKMARK,
                        "Loading local playlist"));
            }

            @Override
            public void onComplete() {
            }
        };
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if ((isProtectedLibrary() || isUnavailable())
                && (item.getItemId() == R.id.menu_item_rename_playlist
                || item.getItemId() == R.id.menu_item_remove_watched
                || item.getItemId() == R.id.menu_item_remove_duplicates)) {
            return true; // Individual Library deletion keeps its own explicit confirmation.
        }
        if (item.getItemId() == R.id.menu_item_clear_unavailable) {
            confirmUnavailableBulk(false);
        } else if (item.getItemId() == R.id.menu_item_remove_all_unavailable) {
            confirmUnavailableBulk(true);
        } else if (item.getItemId() == R.id.menu_item_share_playlist) {
            createShareConfirmationDialog();
        } else if (item.getItemId() == R.id.menu_item_rename_playlist) {
            createRenameDialog();
        } else if (item.getItemId() == R.id.menu_item_remove_watched) {
            openRemoveWatchedConfirmationDialog();
        } else if (item.getItemId() == R.id.menu_item_remove_duplicates) {
            openRemoveDuplicatesDialog();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /**
     * Shares the playlist in one of 3 ways, depending on the value of {@code shareMode}:
     * <ul>
     *     <li>{@code JUST_URLS}: shares the URLs only.</li>
     *     <li>{@code WITH_TITLES}: each entry in the list is accompanied by its title.</li>
     *     <li>{@code YOUTUBE_TEMP_PLAYLIST}: shares as a YouTube temporary playlist.</li>
     * </ul>
     *
     * @param shareMode The way the playlist should be shared.
     */
    private void sharePlaylist(final PlayListShareMode shareMode) {
        final Context context = requireContext();

        disposables.add(playlistManager.getPlaylistStreams(playlistId)
            .flatMapSingle(playlist -> Single.just(export(

                shareMode,
                playlist,
                context
            )))
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                urlsText -> {

                    final String content = shareMode == WITH_TITLES
                        ? context.getString(R.string.share_playlist_content_details,
                                            name,
                                            urlsText
                                           )
                        : urlsText;

                    ShareUtils.shareText(context, name, content);
                },
                throwable -> showUiErrorSnackbar(this, "Sharing playlist", throwable)
            )
        );
    }

    public void removeWatchedStreams(final boolean removePartiallyWatched) {
        if (!isProtectedLibrary()) {
            viewModel.removeWatchedStreams(playlistId, removePartiallyWatched);
        }
    }

    @Override
    public void handleResult(@NonNull final List<PlaylistStreamEntry> result) {
        super.handleResult(result);
        if (itemListAdapter == null) {
            return;
        }

        itemListAdapter.clearStreamItemList();

        if (result.isEmpty()) {
            showEmptyState();
            return;
        }

        itemListAdapter.addItems(result);
        if (itemsListState != null) {
            itemsList.getLayoutManager().onRestoreInstanceState(itemsListState);
            itemsListState = null;
        }
        setStreamCountAndOverallDuration(itemListAdapter.getItemsList());

        PlayButtonHelper.initPlaylistControlClickListener(activity, playlistControlBinding, this);

        hideLoading();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void resetFragment() {
        super.resetFragment();
        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playlist Metadata/Streams Manipulation
    //////////////////////////////////////////////////////////////////////////*/

    private void createRenameDialog() {
        if (playlistId == null || name == null || getContext() == null
                || isProtectedLibrary() || isUnavailable()) {
            return;
        }

        final var dialogBinding = DialogEditTextBinding.inflate(getLayoutInflater());
        dialogBinding.dialogEditText.setHint(R.string.name);
        dialogBinding.dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        dialogBinding.dialogEditText.setSelection(dialogBinding.dialogEditText.getText().length());
        dialogBinding.dialogEditText.setText(name);

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.rename_playlist)
                .setView(dialogBinding.getRoot())
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rename, (dialogInterface, i) ->
                        changePlaylistName(dialogBinding.dialogEditText.getText().toString()))
                .show();
    }

    private void changePlaylistName(final String title) {
        if (playlistManager == null || isProtectedLibrary() || isUnavailable()) {
            return;
        }
        if (title.trim().equalsIgnoreCase(UnavailableRepository.NAME)) {
            showUiErrorSnackbar(this, "Renaming playlist",
                    new IllegalArgumentException("Unavailable is a reserved playlist name"));
            return;
        }

        this.name = title;
        setTitle(title);

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + playlistId + "] "
                    + "with new title=[" + title + "] items");
        }

        final Disposable disposable = playlistManager.renamePlaylist(playlistId, title)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(longs -> { /*Do nothing on success*/ }, throwable ->
                        showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                "Renaming playlist")));
        disposables.add(disposable);
    }

    private void changeThumbnailStreamId(final long thumbnailStreamId, final boolean isPermanent) {
        if (playlistManager == null || (!isPermanent && playlistManager
                .getIsPlaylistThumbnailPermanent(playlistId))) {
            return;
        }

        final Toast successToast = Toast.makeText(getActivity(),
                R.string.playlist_thumbnail_change_success,
                Toast.LENGTH_SHORT);

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + playlistId + "] "
                    + "with new thumbnail stream id=[" + thumbnailStreamId + "]");
        }

        final Disposable disposable = playlistManager
                .changePlaylistThumbnail(playlistId, thumbnailStreamId, isPermanent)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignore -> successToast.show(), throwable ->
                        showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                "Changing playlist thumbnail")));
        disposables.add(disposable);
    }

    private void openRemoveDuplicatesDialog() {
        new AlertDialog.Builder(this.getActivity())
                .setTitle(R.string.remove_duplicates_title)
                .setMessage(R.string.remove_duplicates_message)
                .setPositiveButton(R.string.ok, (dialog, i) ->
                        removeDuplicatesInPlaylist())
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private void removeDuplicatesInPlaylist() {
        viewModel.removeDuplicates(playlistId);
    }

    private void deleteItem(final PlaylistStreamEntry item) {
        if (itemListAdapter == null || playlistId == null || isLoadingComplete == null
                || !isLoadingComplete.get()) {
            return;
        }
        if (isProtectedLibrary() && !LibraryRepository.isBoundLibrary(
                NewPipeDatabase.getInstance(requireContext()), playlistId)) {
            // Name-only startup protection is not enough to choose a global
            // deletion dialog. Wait, then route using the verified ID.
            disposables.add(LibraryRepository.ensureInitialized(requireContext())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(() -> deleteItem(item),
                            error -> showUiErrorSnackbar(this,
                                    "Open Bookmarked Playlists to repair Library", error)));
            return;
        }
        if (isProtectedLibrary()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.rv_library_remove_title)
                    .setMessage(R.string.rv_library_remove_message)
                    .setPositiveButton(R.string.rv_library_remove_action, (dialog, which) -> {
                        if (playlistId == null || disposables == null) {
                            return;
                        }
                        disposables.add(LibraryRepository
                                .removeEverywhere(requireContext(), playlistId, item.getStreamId())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                        () -> {
                                            // This explicit global mutation invalidated the
                                            // editor baseline. Wait for any started save, then
                                            // reload committed membership rather than submitting
                                            // another snapshot based on the removed item.
                                            if (getView() != null) {
                                                reloadAfterDiscardingEdits();
                                            }
                                        },
                                        throwable -> showUiErrorSnackbar(this,
                                                "Removing video from Library", throwable)
                                ));
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        itemListAdapter.removeItem(item);
        setStreamCountAndOverallDuration(itemListAdapter.getItemsList());
        debounceSaver.setHasChangesToSave();
        saveImmediate();
    }

    /**
     * <p>Commit changes immediately if the playlist has been modified.</p>
     *  Delete operations and other modifications will be committed to ensure that the database
     *  is up to date, e.g. when the user adds the just deleted stream from another fragment.
     */
    @Override
    public void saveImmediate() {
        if (playlistManager == null || itemListAdapter == null || playlistId == null
                || isLoadingComplete == null || debounceSaver == null
                || !isLoadingComplete.get() || !debounceSaver.getIsModified()) {
            return;
        }
        if (playlistSaveQueue == null) {
            final LocalPlaylistManager writer = playlistManager;
            final long targetId = playlistId;
            playlistSaveQueue = new PlaylistSaveQueue(
                    command -> Schedulers.io().scheduleDirect(command),
                    (expected, desired) ->
                            writer.savePlaylistSnapshot(targetId, expected, desired));
            attachPlaylistSaveListener();
        }
        playlistSaveQueue.submit(loadedStreamIds,
                playlistStreamIds(itemListAdapter.getItemsList()));
    }

    private void attachPlaylistSaveListener() {
        if (playlistSaveQueue == null || getView() == null) {
            return;
        }
        final long generation = ++playlistSaveListenerGeneration;
        playlistSaveQueue.setListener(result -> playlistSaveHandler.post(() -> {
            if (generation != playlistSaveListenerGeneration || getView() == null) {
                return;
            }
            if (result.getError() != null) {
                showPlaylistSaveError(result.getError());
                return;
            }
            loadedStreamIds = new ArrayList<>(result.getCommitted());
            if (debounceSaver != null && itemListAdapter != null
                    && debounceSaver.getIsModified()) {
                if (result.getSubmitted().equals(
                        playlistStreamIds(itemListAdapter.getItemsList()))) {
                    debounceSaver.setNoChangesToSave();
                } else {
                    saveImmediate();
                }
            }
            // Room can emit the marking change while an older editor save is
            // still dirty, causing the observer to skip it. Refresh only after
            // the save acknowledgment; never discard a pending reorder.
            refreshUnavailableAfterMarkWhenSafe();
        }));
    }

    /**
     * An Unavailable mark is an external membership change to this editor.
     * Wait for retained edits to commit and their UI acknowledgments to settle,
     * then re-query Room. An error/dirty editor must stay available for recovery.
     */
    private void refreshUnavailableAfterMarkWhenSafe() {
        if (!unavailableMarkRefreshPending || getView() == null
                || debounceSaver == null || debounceSaver.getIsModified()) {
            return;
        }
        final long generation = playlistSaveListenerGeneration;
        final Runnable afterIdle = () -> playlistSaveHandler.post(() -> {
            if (!unavailableMarkRefreshPending || getView() == null
                    || generation != playlistSaveListenerGeneration
                    || debounceSaver == null || debounceSaver.getIsModified()) {
                return;
            }
            unavailableMarkRefreshPending = false;
            startLoading(false);
        });
        if (playlistSaveQueue == null) {
            afterIdle.run();
        } else {
            playlistSaveQueue.whenIdle(afterIdle);
        }
    }

    /**
     * Sequence Unavailable marking after any outstanding drag/reorder save.
     *
     * @param context the active application context
     * @param item the saved local stream to mark or unmark
     * @param desiredMark whether the stream belongs in Unavailable
     */
    private void markUnavailablePreservingEdits(final Context context,
                                                final PlaylistStreamEntry item,
                                                final boolean desiredMark) {
        if (disposables == null || getView() == null) {
            return;
        }
        final boolean editsThisPlaylist = isUnavailable();
        if (editsThisPlaylist) {
            saveImmediate();
        }
        final PlaylistSaveQueue saves = editsThisPlaylist ? playlistSaveQueue : null;
        // Capture the data operation now. A destroyed view must not erase this
        // user request, and a restored catalog must not receive the old row ID.
        final io.reactivex.rxjava3.core.Completable mutation = UnavailableRepository
                .setMarked(context.getApplicationContext(), item.getStreamId(), desiredMark);
        final Runnable issueMark = () -> {
            final Throwable saveFailure = saves == null ? null : saves.failure();
            if (saveFailure != null || (getView() != null && editsThisPlaylist
                    && debounceSaver != null && debounceSaver.getIsModified())) {
                if (getView() != null) {
                    showPlaylistSaveError(saveFailure == null
                            ? new PlaylistEditConflictException() : saveFailure);
                } else {
                    Log.w(TAG, "Mark not applied because its preceding playlist save failed",
                            saveFailure);
                }
                return;
            }
            mutation.observeOn(AndroidSchedulers.mainThread()).subscribe(() -> {
                if (getView() != null && isUnavailable()) {
                    unavailableMarkRefreshPending = true;
                    refreshUnavailableAfterMarkWhenSafe();
                }
            }, error -> {
                if (getView() != null) {
                    showUiErrorSnackbar(this, "Updating Unavailable mark", error);
                } else {
                    Log.w(TAG, "Deferred Unavailable mark failed", error);
                }
            });
        };
        if (saves != null) {
            saves.afterSavesForMutation(() -> playlistSaveHandler.post(issueMark));
        } else {
            issueMark.run();
        }
    }

    /**
     * A confirmed write is independent of list reloads and view subscriptions.
     *
     * @param operation the already targeted data mutation
     * @param description the contextual failure description
     */
    private void executeConfirmedRemoval(final io.reactivex.rxjava3.core.Completable operation,
                                         final String description) {
        operation.observeOn(AndroidSchedulers.mainThread()).subscribe(() -> {
            if (getView() != null) {
                reloadAfterDiscardingEdits();
            }
        }, error -> {
            if (getView() == null) {
                Log.w(TAG, description, error);
            } else if (error instanceof UnavailableRepository.SelectionChangedException) {
                Snackbar.make(requireView(), R.string.rv_unavailable_selection_changed,
                                Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.rv_playlist_reload,
                                view -> reloadAfterDiscardingEdits()).show();
            } else {
                // A membership commit may have succeeded before tag cleanup
                // failed. Refresh clean editors so we do not claim it rolled back.
                if (debounceSaver != null && !debounceSaver.getIsModified()) {
                    startLoading(false);
                }
                showUiErrorSnackbar(this, description, error);
            }
        });
    }

    private void reloadAfterDiscardingEdits() {
        playlistLoadGeneration++;
        unavailableMarkRefreshPending = false;
        final long generation = ++playlistSaveListenerGeneration;
        if (playlistSaveQueue != null) {
            playlistSaveQueue.setListener(null);
        }
        final Runnable reload = () -> playlistSaveHandler.post(() -> {
            if (generation != playlistSaveListenerGeneration || getView() == null
                    || debounceSaver == null) {
                return;
            }
            debounceSaver.setNoChangesToSave();
            attachPlaylistSaveListener();
            startLoading(false);
        });
        if (playlistSaveQueue == null) {
            reload.run();
        } else {
            playlistSaveQueue.discardAndWhenIdle(reload);
        }
    }

    private static List<Long> playlistStreamIds(final List<? extends LocalItem> items) {
        final List<Long> result = new ArrayList<>(items.size());
        for (final LocalItem item : items) {
            if (item instanceof PlaylistStreamEntry entry) {
                result.add(entry.getStreamId());
            }
        }
        return result;
    }

    private void showPlaylistSaveError(final Throwable error) {
        if (getView() == null) {
            return;
        }
        if (error instanceof PlaylistEditConflictException) {
            Snackbar.make(requireView(), R.string.rv_playlist_edit_conflict,
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.rv_playlist_reload,
                            view -> reloadAfterDiscardingEdits()).show();
        } else {
            showError(new ErrorInfo(error, UserAction.REQUESTED_BOOKMARK, "Saving playlist"));
        }
    }


    private ItemTouchHelper.SimpleCallback getItemTouchCallback() {
        int directions = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
        if (shouldUseGridLayout(requireContext())) {
            directions |= ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
        }
        return new ItemTouchHelper.SimpleCallback(directions,
                ItemTouchHelper.ACTION_STATE_IDLE) {
            @Override
            public int interpolateOutOfBoundsScroll(@NonNull final RecyclerView recyclerView,
                                                    final int viewSize,
                                                    final int viewSizeOutOfBounds,
                                                    final int totalSize,
                                                    final long msSinceStartScroll) {
                final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView,
                        viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll);
                final int minimumAbsVelocity = Math.max(MINIMUM_INITIAL_DRAG_VELOCITY,
                        Math.abs(standardSpeed));
                return minimumAbsVelocity * (int) Math.signum(viewSizeOutOfBounds);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder source,
                                  @NonNull final RecyclerView.ViewHolder target) {
                if (source.getItemViewType() != target.getItemViewType()
                        || itemListAdapter == null || isLoadingComplete == null
                        || !isLoadingComplete.get()) {
                    return false;
                }

                final int sourceIndex = source.getBindingAdapterPosition();
                final int targetIndex = target.getBindingAdapterPosition();
                final boolean isSwapped = itemListAdapter.swapItems(sourceIndex, targetIndex);
                if (isSwapped) {
                    debounceSaver.setHasChangesToSave();
                }
                return isSwapped;
            }

            @Override
            public void clearView(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                saveImmediate();
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder,
                                 final int swipeDir) {
            }
        };
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private PlayQueue getPlayQueueStartingAt(final PlaylistStreamEntry infoItem) {
        return getPlayQueue(Math.max(itemListAdapter.getItemsList().indexOf(infoItem), 0));
    }

    protected void showInfoItemDialog(final PlaylistStreamEntry item) {
        if (!isAdded() || disposables == null) {
            return;
        }
        disposables.add(UnavailableRepository.inspect(requireContext(), item.getStreamId())
                .observeOn(AndroidSchedulers.mainThread())
                // A broken Unavailable identity disables only marking. It must
                // never suppress the existing share, playback, tag or removal menu.
                .subscribe(marked -> showInfoItemDialogWithMark(item, marked),
                        error -> showInfoItemDialogWithMark(item, null)));
    }

    private void showInfoItemDialogWithMark(final PlaylistStreamEntry item,
                                            @Nullable final Boolean marked) {
        if (!isAdded() || disposables == null) {
            return;
        }
        final StreamInfoItem infoItem = item.toStreamInfoItem();
        try {
            final Context context = requireContext();
            final InfoItemDialog.Builder dialogBuilder =
                    new InfoItemDialog.Builder(getActivity(), context, this, infoItem);
            if (!isProtectedLibrary() && !isUnavailable()) {
                dialogBuilder.addEntry(StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL);
                dialogBuilder.addEntry(StreamDialogDefaultEntry.REMOVE_FROM_THIS_PLAYLIST);
            }
            dialogBuilder.addEntry(StreamDialogDefaultEntry.EDIT_TAGS);
            if (marked != null) {
                final StreamDialogDefaultEntry markAction = marked
                        ? StreamDialogDefaultEntry.MARK_AVAILABLE
                        : StreamDialogDefaultEntry.MARK_UNAVAILABLE;
                dialogBuilder.addEntry(markAction);
                dialogBuilder.setAction(markAction,
                        (f, i) -> markUnavailablePreservingEdits(context, item, !marked));
            }
            dialogBuilder.addEntry(StreamDialogDefaultEntry.REMOVE_EVERYWHERE);
            dialogBuilder
                    .setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND,
                            (f, i) -> NavigationHelper.playOnBackgroundPlayer(
                                    context, getPlayQueueStartingAt(item), true))
                    .setAction(StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL,
                            (f, i) -> changeThumbnailStreamId(
                                    item.getStreamEntity().getUid(), true))
                    .setAction(StreamDialogDefaultEntry.REMOVE_FROM_THIS_PLAYLIST,
                            (f, i) -> confirmRemoveFromPlaylist(item))
                    .setAction(StreamDialogDefaultEntry.REMOVE_EVERYWHERE,
                            (f, i) -> confirmRemoveEverywhere(item))
                    .create()
                    .show();
        } catch (final IllegalArgumentException error) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(error, infoItem);
        }
    }

    private void confirmRemoveFromPlaylist(final PlaylistStreamEntry item) {
        if (isProtectedLibrary() || isUnavailable()) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rv_unavailable_remove_from_playlist)
                .setMessage(R.string.rv_unavailable_remove_from_playlist_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rv_unavailable_remove_from_playlist,
                        (dialog, which) -> deleteItem(item))
                .show();
    }

    private void confirmRemoveEverywhere(final PlaylistStreamEntry item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rv_unavailable_remove_everywhere)
                .setMessage(R.string.rv_unavailable_remove_one_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rv_unavailable_remove_everywhere, (dialog, which) -> {
                    if (disposables == null) {
                        return;
                    }
                    final Context context = requireContext().getApplicationContext();
                    executeConfirmedRemoval(LibraryRepository.ensureInitialized(context)
                            .andThen(io.reactivex.rxjava3.core.Completable.defer(() -> {
                                final Long id = LibraryRepository.boundLibraryId(
                                        NewPipeDatabase.getInstance(context));
                                if (id == null) {
                                    return io.reactivex.rxjava3.core.Completable.error(
                                            new IllegalStateException(
                                                    "Library identity unavailable"));
                                }
                                return LibraryRepository.removeEverywhere(context, id,
                                        item.getStreamId());
                            })), "Removing video everywhere");
                }).show();
    }

    private void confirmUnavailableBulk(final boolean removeEverywhere) {
        if (!isUnavailable() || disposables == null) {
            return;
        }
        disposables.add(UnavailableRepository.getPreview(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> {
                    if (!isAdded() || !isUnavailable() || preview.getCount() == 0) {
                        return;
                    }
                    final Context context = requireContext();
                    final int title = removeEverywhere
                            ? R.string.rv_unavailable_remove_all
                            : R.string.rv_unavailable_clear;
                    final int message = removeEverywhere
                            ? R.plurals.rv_unavailable_remove_all_message
                            : R.plurals.rv_unavailable_clear_message;
                    new AlertDialog.Builder(context)
                            .setTitle(title)
                            .setMessage(getResources().getQuantityString(
                                    message, preview.getCount(), preview.getCount()))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(removeEverywhere
                                    ? R.string.rv_unavailable_remove_everywhere
                                    : R.string.rv_unavailable_clear, (dialog, which) -> {
                                if (disposables == null) {
                                    return;
                                }
                                final Context appContext = context.getApplicationContext();
                                final io.reactivex.rxjava3.core.Completable operation =
                                        removeEverywhere
                                                ? UnavailableRepository.removeAll(
                                                        appContext, preview)
                                                : UnavailableRepository.clear(appContext, preview);
                                executeConfirmedRemoval(operation, "Changing Unavailable playlist");
                            }).show();
                }, error -> showUiErrorSnackbar(this, "Reading Unavailable playlist", error)));
    }

    private void setInitialData(final long pid, final String title) {
        this.playlistId = pid;
        this.name = !TextUtils.isEmpty(title) ? title : "";
    }

    private void setStreamCountAndOverallDuration(final ArrayList<LocalItem> itemsList) {
        if (activity != null && headerBinding != null) {
            final long streamCount = itemsList.size();
            final long playlistOverallDurationSeconds = itemsList.stream()
                    .filter(PlaylistStreamEntry.class::isInstance)
                    .map(PlaylistStreamEntry.class::cast)
                    .map(PlaylistStreamEntry::getStreamEntity)
                    .mapToLong(StreamEntity::getDuration)
                    .sum();
            headerBinding.playlistStreamCount.setText(
                    Localization.concatenateStrings(
                            Localization.localizeStreamCount(activity, streamCount),
                            Localization.getDurationString(playlistOverallDurationSeconds,
                                                            true, true))
            );
        }
    }

    @Override
    public PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        if (itemListAdapter == null) {
            return new SinglePlayQueue(Collections.emptyList(), 0);
        }

        final List<LocalItem> infoItems = itemListAdapter.getItemsList();
        final List<StreamInfoItem> streamInfoItems = new ArrayList<>(infoItems.size());
        for (final LocalItem item : infoItems) {
            if (item instanceof PlaylistStreamEntry) {
                streamInfoItems.add(((PlaylistStreamEntry) item).toStreamInfoItem());
            }
        }
        return new SinglePlayQueue(streamInfoItems, index);
    }

    /**
     * Creates a dialog to confirm whether the user wants to share the playlist
     * with the playlist details or just the list of stream URLs.
     * After the user has made a choice, the playlist is shared.
     */
    private void createShareConfirmationDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.share_playlist)
                .setCancelable(true)
                .setPositiveButton(R.string.share_playlist_with_titles, (dialog, which) ->
                    sharePlaylist(WITH_TITLES)
                )
                .setNeutralButton(R.string.share_playlist_as_youtube_temporary_playlist,
                    (dialog, which) -> sharePlaylist(YOUTUBE_TEMP_PLAYLIST)
                )
                .setNegativeButton(R.string.share_playlist_with_list, (dialog, which) ->
                    sharePlaylist(JUST_URLS)
                )
                .show();
    }

    /**
     * Opens a confirmation dialog to remove watched streams from the playlist.
     * The user can also choose to remove partially watched streams.
     */
    private void openRemoveWatchedConfirmationDialog() {
        final android.widget.CheckBox removePartiallyWatchedCheckbox =
                new android.widget.CheckBox(requireContext());
        removePartiallyWatchedCheckbox.setText(
                R.string.remove_watched_popup_partially_watched_streams);

        // Wrap the checkbox in a container with dialog-like horizontal padding
        // so it aligns with the dialog title and message on the start side.
        final LinearLayout checkboxContainer = new LinearLayout(requireContext());
        checkboxContainer.setOrientation(LinearLayout.VERTICAL);
        final int padding = DeviceUtils.dpToPx(20, requireContext());
        checkboxContainer.setPadding(padding, padding, padding, 0);
        checkboxContainer.addView(removePartiallyWatchedCheckbox,
                new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.remove_watched_popup_warning)
                .setTitle(R.string.remove_watched_popup_title)
                .setView(checkboxContainer)
                .setPositiveButton(R.string.yes, (d, id) ->
                        removeWatchedStreams(removePartiallyWatchedCheckbox.isChecked()))
                .setNegativeButton(R.string.cancel, (d, id) -> d.cancel())
                .show();
    }

    public void setTabsPagerAdapter(
            @Nullable final MainFragment.SelectedTabsPagerAdapter tabsPagerAdapter) {
        this.tabsPagerAdapter = tabsPagerAdapter;
    }
}

