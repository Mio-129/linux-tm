package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * The data directory path the bootstrap binaries are built with. This fork installs everything to
     * its own data directory ({@link TermuxConstants#TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH}), so
     * every occurrence of this path inside the extracted files is replaced while the bootstrap is
     * installed, otherwise the shebangs, the `$PREFIX`/`$HOME` paths of the scripts, the symlinks and
     * apt would point to a directory the app can not write to.
     */
    private static final String LEGACY_PREFIX = TermuxConstants.TERMUX_LEGACY_DATA_DIR_PATH;

    /** Files bigger than this are not patched in place, they are binaries anyway. */
    private static final long MAX_PATCH_FILE_SIZE = 16L * 1024 * 1024;

    /**
     * Written after the bootstrap was extracted and patched with {@link #PATCH_VERSION}. The builds
     * before the prefix was moved to the data directory of this app extracted the bootstrap with
     * the (longer) legacy path rewritten as raw bytes, which corrupted every binary and left a
     * prefix that can not be run. If the marker is missing, the prefix is extracted again.
     */
    private static final String PATCH_MARKER_FILE_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/.bootstrap-patch";

    /** Bumped when the patching changes, so that the prefix is rebuilt once. */
    private static final String PATCH_VERSION = "com.mio-prefix-4";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // A prefix that was extracted before the prefix patching changed may still point to the Termux
        // data directory, which this app can not write to. The files are patched again in place, since
        // extracting the whole bootstrap again would throw away the packages the user installed.
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true) && !isPatchUpToDate()) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The prefix directory is empty, extracting the bootstrap.");
            } else {
                Logger.logInfo(LOG_TAG, "Patching the existing prefix \"" + TERMUX_PREFIX_DIR_PATH + "\" for the current paths.");
                repatchInstalledPrefix();

                if (isPrefixUsable()) {
                    writePatchMarker();
                    whenDone.run();
                    return;
                }

                Logger.logInfo(LOG_TAG, "The existing prefix is not usable, extracting the bootstrap again.");
                Error deleteError = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                if (deleteError != null) {
                    Logger.logErrorExtended(LOG_TAG, "Failed to delete the outdated prefix:\n" + deleteError.getErrorLogString());
                }
            }
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = rewriteLegacyPath(parts[0]);
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Patching the bootstrap files for the prefix \"" + TERMUX_PREFIX_DIR_PATH + "\".");
                    patchLegacyPaths(TERMUX_STAGING_PREFIX_DIR);

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    writePatchMarker();

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // Install the custom `swset` and `so` commands since the prefix was wiped earlier
                    com.termux.app.mio.MioBootstrap.install(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /**
     * Patch the files of an already installed prefix: the shell is run once for every text file and
     * the binaries are patched byte wise, so that a prefix extracted with an older patch keeps the
     * installed packages but gets the new paths.
     */
    private static void repatchInstalledPrefix() {
        patchLegacyPaths(TERMUX_PREFIX_DIR);
        // `$HOME` and `~/.termux` of the second stage script of the bootstrap
        patchLegacyPaths(TermuxConstants.TERMUX_FILES_DIR);

        // The files written by an older patch may contain the broken (NUL padded) paths, so the apt
        // configuration is rewritten from scratch and the generated wrapper scripts are reinstalled
        deleteIfExists(new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt/apt.conf.d/zz-mio-sources.conf"));
        deleteIfExists(new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt/apt.conf.d/mio-apt.conf"));
        com.termux.app.mio.MioBootstrap.repairAptState();
        com.termux.app.mio.MioBootstrap.repairAptConfigFile();
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && file.delete()) {
            Logger.logInfo(LOG_TAG, "Deleted the stale apt configuration \"" + file.getAbsolutePath() + "\"");
        }
    }

    /**
     * @return {@code true} if the prefix contains a shell that can actually be executed, i.e. the
     * binaries of the prefix were patched correctly and not shifted byte by byte.
     */
    private static boolean isPrefixUsable() {
        File sh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sh");
        if (!sh.isFile()) return false;
        try {
            Process process = new ProcessBuilder(sh.getAbsolutePath(), "-c", "echo mio-ok")
                .redirectErrorStream(true).start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            process.destroy();
            return finished && line != null && line.trim().equals("mio-ok");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to check the prefix: " + e.getMessage());
            return false;
        }
    }

    /** @return {@code true} if the prefix was extracted with the current patching. */
    private static boolean isPatchUpToDate() {
        File marker = new File(PATCH_MARKER_FILE_PATH);
        if (!marker.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(marker), "UTF-8"))) {
            String line = reader.readLine();
            return line != null && line.trim().equals(PATCH_VERSION);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePatchMarker() {
        File marker = new File(PATCH_MARKER_FILE_PATH);
        File parent = marker.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write((PATCH_VERSION + "\n").getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to write the patch marker: " + e.getMessage());
        }
    }

    /**
     * Replace the legacy prefix path of the Termux binaries with the prefix of this fork, so that
     * the shebangs of the scripts in `bin`, the `$PREFIX` paths inside them and the `rpath`/`DT_NEEDED`
     * entries of the ELF binaries point to the data directory of this app.
     *
     * The new path is shorter than the old one (`/data/data/com.mio` is 17 bytes,
     * `/data/data/com.termux` is 21), so a path is never rewritten as raw bytes: every
     * occurrence is replaced inside its NUL terminated string and the remaining bytes of that string
     * are zeroed. That keeps the size and the offsets of the file, which is what the ELF loader
     * needs; rewriting the bytes one to one would corrupt the binary ("empty/missing DT_HASH").
     */
    private static void patchLegacyPaths(@NonNull File directory) {
        File[] children = directory.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                patchLegacyPaths(child);
            } else if (child.isFile() && child.length() <= MAX_PATCH_FILE_SIZE) {
                patchLegacyPathInFile(child);
            }
        }
    }

    /** Rewrite the legacy path inside a single file, keeping the size and the executable bit. */
    private static void patchLegacyPathInFile(@NonNull File file) {
        byte[] legacy;
        byte[] current;
        try {
            legacy = LEGACY_PREFIX.getBytes("UTF-8");
            current = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH.getBytes("UTF-8");
        } catch (Exception e) {
            return;
        }

        byte[] content;
        try {
            content = readAllBytes(file);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read \"" + file.getAbsolutePath() + "\": " + e.getMessage());
            return;
        }
        if (content == null || indexOf(content, legacy, 0) < 0) return;

        byte[] patched = replaceInsideNullTerminatedStrings(content, legacy, current);
        if (patched == null) return;

        boolean executable = file.canExecute();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(patched);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to patch \"" + file.getAbsolutePath() + "\": " + e.getMessage());
            return;
        }
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        }
        Logger.logDebug(LOG_TAG, "Patched \"" + file.getAbsolutePath() + "\"");
    }

    /**
     * Replace {@code from} with {@code to} inside every NUL terminated string of the content, where
     * {@code to} is shorter than {@code from}, and zero the unused bytes at the end of the string.
     *
     * @return The patched content of the same length as the original, or {@code null} if the
     * replacement would not fit.
     */
    @Nullable
    private static byte[] replaceInsideNullTerminatedStrings(@NonNull byte[] content,
                                                             @NonNull byte[] from,
                                                             @NonNull byte[] to) {
        if (to.length > from.length) return null;

        byte[] result = content.clone();
        int searchFrom = 0;
        while (true) {
            int found = indexOf(result, from, searchFrom);
            if (found < 0) break;

            // Bounds of the NUL terminated string that contains the path. When the match runs until
            // the end of the file (e.g. the default `Dir::Etc` value that ends the .rodata section)
            // there is no NUL after it, so the end of the file is used as the end of the string.
            int start = found;
            while (start > 0 && result[start - 1] != 0) start--;
            int end = found;
            while (end < result.length && result[end] != 0) end++;

            // The replacement is shorter than the path, so the string keeps at least one unused byte.
            // That byte is zeroed, which turns the string into a valid NUL terminated one instead of
            // leaving the last characters of the old path behind (they were read as part of the value,
            // e.g. `data/data/com.mio/files/usr\0\0\0` instead of `/data/data/com.mio/files/usr`).
            if (end >= result.length) end = result.length - 1;
            if (end <= start) {
                searchFrom = found + from.length;
                continue;
            }

            byte[] token = new byte[end - start];
            System.arraycopy(result, start, token, 0, token.length);
            byte[] replaced = replaceAll(token, from, to);
            if (replaced.length > token.length) return null;

            System.arraycopy(replaced, 0, result, start, replaced.length);
            for (int i = start + replaced.length; i <= end; i++) result[i] = 0;

            searchFrom = end + 1;
        }
        return result;
    }

    @Nullable
    private static byte[] readAllBytes(@NonNull File file) throws Exception {
        long size = file.length();
        if (size > MAX_PATCH_FILE_SIZE) return null;
        byte[] buffer = new byte[(int) size];
        int offset = 0;
        try (InputStream in = new java.io.FileInputStream(file)) {
            int read;
            while (offset < buffer.length && (read = in.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += read;
            }
        }
        if (offset == buffer.length) return buffer;
        byte[] shorter = new byte[offset];
        System.arraycopy(buffer, 0, shorter, 0, offset);
        return shorter;
    }

    private static int indexOf(@NonNull byte[] haystack, @NonNull byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @NonNull
    private static byte[] replaceAll(@NonNull byte[] content, @NonNull byte[] from, @NonNull byte[] to) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length + 64);
        int index = 0;
        while (true) {
            int found = indexOf(content, from, index);
            if (found < 0) {
                out.write(content, index, content.length - index);
                break;
            }
            out.write(content, index, found - index);
            out.write(to, 0, to.length);
            index = found + from.length;
        }
        return out.toByteArray();
    }

    /** Rewrite a single path string that points into the legacy data directory, e.g. a symlink target. */
    @NonNull
    private static String rewriteLegacyPath(@NonNull String path) {
        if (path.startsWith(LEGACY_PREFIX)) {
            return TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + path.substring(LEGACY_PREFIX.length());
        }
        return path;
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        final String details = message;

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title)
                    .setMessage(activity.getString(R.string.bootstrap_error_body)
                        + "\n\n" + details)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
