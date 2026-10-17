package org.schabi.newpipe.settings;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import org.schabi.newpipe.custom.tagging.CatalogTransferModel;
import androidx.preference.PreferenceManager;


import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.subscription.SubscriptionsImportExportHelper;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.NavigationHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BackupRestoreSettingsFragment extends BasePreferenceFragment {

    private static final String ZIP_MIME_TYPE = "application/zip";

    private final SimpleDateFormat exportDateFormat =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private CatalogTransferModel transfer;
    private androidx.appcompat.app.AlertDialog settingsChoice;
    private String importExportDataPathKey;
    private final ActivityResultLauncher<Intent> requestImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestImportPathResult);
    private final ActivityResultLauncher<Intent> requestExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestExportPathResult);
    private SubscriptionsImportExportHelper importExportHelper;


    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        importExportHelper = new SubscriptionsImportExportHelper(this);
    }

    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        transfer = new ViewModelProvider(requireActivity()).get(CatalogTransferModel.class);
        transfer.getState().observe(this, this::renderTransfer);

        importExportDataPathKey = getString(R.string.import_export_data_path);

        addPreferencesFromResourceRegistry();

        final Preference importDataPreference = requirePreference(R.string.import_data);
        importDataPreference.setOnPreferenceClickListener((Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestImportPathLauncher,
                    StoredFileHelper.getPicker(requireContext(),
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference exportDataPreference = requirePreference(R.string.export_data);
        exportDataPreference.setOnPreferenceClickListener((final Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestExportPathLauncher,
                    StoredFileHelper.getNewPicker(requireContext(),
                            "NewPipeData-" + exportDateFormat.format(new Date()) + ".zip",
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference resetSettings = requirePreference(R.string.reset_settings);
        // Resets all settings by deleting shared preference and restarting the app
        // A dialogue will pop up to confirm if user intends to reset all settings
        resetSettings.setOnPreferenceClickListener(preference -> {
            // Show Alert Dialogue
            final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setMessage(R.string.reset_all_settings);
            builder.setCancelable(true);
            builder.setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                // Deletes all shared preferences xml files.
                final SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(requireContext());
                org.schabi.newpipe.custom.aicontrol.AiControlLifecycle.beforeImport(
                        requireContext(),
                        () -> {
                            sharedPreferences.edit().clear().apply();
                            if (getActivity() != null) {
                                NavigationHelper.restartApp(getActivity());
                            }
                        },
                        error -> {
                            if (isAdded()) {
                                showErrorSnackbar(error, "Resetting AI Control settings");
                            }
                        });
            });
            builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            });
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            return true;
        });

        final Preference exportSubsPreference =
                requirePreference(R.string.export_subscriptions_key);
        exportSubsPreference.setOnPreferenceClickListener(reference -> {
            importExportHelper.onExportSelected();
            return true;
        });

        final Preference importSubsPreference =
                requirePreference(R.string.import_subscriptions_key);
        importSubsPreference.setOnPreferenceClickListener(preference -> {
            importExportHelper.onImportPreviousSelected();
            return true;
        });

    }

    private void requestExportPathResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastExportDataUri = result.getData().getData();

            final StoredFileHelper file = new StoredFileHelper(
                    requireContext(), result.getData().getData(), ZIP_MIME_TYPE);

            exportDatabase(file, lastExportDataUri);
        }
    }

    private void requestImportPathResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastImportDataUri = result.getData().getData();

            final StoredFileHelper file = new StoredFileHelper(
                    requireContext(), result.getData().getData(), ZIP_MIME_TYPE);

            new androidx.appcompat.app.AlertDialog.Builder(requireActivity())
                    .setMessage(R.string.override_current_data)
                    .setPositiveButton(R.string.ok, (d, id) ->
                            transfer.prepareImport(file, lastImportDataUri))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void exportDatabase(final StoredFileHelper file, final Uri exportDataUri) {
        transfer.export(file, exportDataUri);
    }

    private void renderTransfer(final CatalogTransferModel.State state) {
        if (!isAdded()) {
            return;
        }
        final boolean busy = state instanceof CatalogTransferModel.State.Working
                || state instanceof CatalogTransferModel.State.ChooseSettings;
        requirePreference(R.string.import_data).setEnabled(!busy);
        requirePreference(R.string.export_data).setEnabled(!busy);
        requirePreference(R.string.reset_settings).setEnabled(!busy);
        final String message = state instanceof CatalogTransferModel.State.Working
                ? ((CatalogTransferModel.State.Working) state).getMessage() : null;
        requirePreference(R.string.import_data).setSummary(message);
        if (state instanceof CatalogTransferModel.State.ChooseSettings) {
            if (settingsChoice != null && settingsChoice.isShowing()) {
                return;
            }
            final CatalogTransferModel.State.ChooseSettings choice =
                    (CatalogTransferModel.State.ChooseSettings) state;
            settingsChoice = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.import_settings)
                    .setMessage(choice.getJson() ? null : getString(
                            R.string.import_settings_vulnerable_format))
                    .setPositiveButton(R.string.ok, (dialog, which) -> transfer.install(true))
                    .setNegativeButton(R.string.cancel, (dialog, which) -> transfer.install(false))
                    .setOnCancelListener(dialog -> transfer.cancelPrepared())
                    .show();
        } else if (state instanceof CatalogTransferModel.State.Done) {
            Toast.makeText(requireContext(),
                    ((CatalogTransferModel.State.Done) state).getMessage(), Toast.LENGTH_LONG)
                    .show();
            transfer.consumed();
        } else if (state instanceof CatalogTransferModel.State.Failed) {
            showErrorSnackbar(((CatalogTransferModel.State.Failed) state).getError(),
                    "Transferring Library, tags and settings");
            transfer.consumed();
        }
    }

    @Override
    public void onDestroyView() {
        if (settingsChoice != null) {
            settingsChoice.dismiss();
            settingsChoice = null;
        }
        super.onDestroyView();
    }

    private Uri getImportExportDataUri() {
        final String path = defaultPreferences.getString(importExportDataPathKey, null);
        return isBlank(path) ? null : Uri.parse(path);
    }

    private void showErrorSnackbar(final Throwable e, final String request) {
        ErrorUtil.showSnackbar(this, new ErrorInfo(e, UserAction.DATABASE_IMPORT_EXPORT, request));
    }

}
