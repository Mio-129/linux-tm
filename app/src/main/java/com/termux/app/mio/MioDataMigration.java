package com.termux.app.mio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One time migration of the data of the old `com.termux` installation to this fork.
 *
 * The app was installed as `com.termux` before this fork changed its application id to `com.mio`,
 * so the files of the user (the home folder with the projects, `~/.mio` with the packages and the
 * modules, `~/.termux` with the settings, and the packages installed to `$PREFIX`) live under
 * `/data/data/com.termux`. Android gives every app its own `/data/data` directory, so the old files
 * are not readable by the new app any more. They are copied before the old app is uninstalled.
 *
 * Only the home folder and the app data are migrated: the prefix of this fork lives inside the data
 * directory of the app and is installed by the bootstrap (the old `usr` folder can not be read).
 *
 * The migration is marked as done in {@link #MARKER_FILE_PATH}, so it never runs twice, and it also
 * runs when the app is started after the old files were copied but before the marker was written.
 */
public final class MioDataMigration {

    public static final String LOG_TAG = "MioDataMigration";

    /** The data directory of the old installation. */
    public static final String OLD_DATA_DIR_PATH = "/data/data/com.termux";

    /** The data directory of this installation. */
    public static final String NEW_DATA_DIR_PATH = "/data/data/com.mio";

    /** Written after a successful migration, so that it is done only once. */
    public static final String MARKER_FILE_PATH = NEW_DATA_DIR_PATH + "/files/.mio-migrated";

    /**
     * The folders that are copied, relative to the data directory. The prefix (`usr`) is not in the
     * list on purpose: the binaries of the bootstrap expect the old path, see the class comment.
     */
    private static final List<String> COPY_DIRECTORIES = Arrays.asList("files/home");

    /** The files that are copied, relative to the data directory. */
    private static final List<String> COPY_FILES = Arrays.asList("files/.mio-commands-version");

    public static final class Result {
        /** {@code true} if something was copied. */
        public final boolean migrated;
        /** Number of copied files. */
        public final int files;
        /** {@code true} if the old data directory could not be read. */
        public final boolean oldDataUnreadable;
        /** Human readable explanation, shown in the panel. */
        @NonNull public final String message;

        Result(boolean migrated, int files, boolean oldDataUnreadable, @NonNull String message) {
            this.migrated = migrated;
            this.files = files;
            this.oldDataUnreadable = oldDataUnreadable;
            this.message = message;
        }
    }

    private MioDataMigration() {
    }

    /** @return {@code true} if the migration was already done. */
    public static boolean isDone() {
        return new File(MARKER_FILE_PATH).isFile();
    }

    /** @return {@code true} if the data of an old `com.termux` installation was found. */
    public static boolean hasOldData() {
        return new File(OLD_DATA_DIR_PATH).isDirectory();
    }

    /** @return {@code true} if the old data directory can be listed, i.e. it is readable. */
    public static boolean canReadOldData() {
        File oldHome = new File(OLD_DATA_DIR_PATH, "files/home");
        if (!oldHome.isDirectory()) return false;
        String[] names = oldHome.list();
        return names != null;
    }

    /**
     * Copy the old data if there is any and the migration was not done yet. Safe to call on every
     * start of the app: it does nothing once the marker file exists.
     */
    @Nullable
    public static synchronized Result migrateIfNeeded(@Nullable Context context) {
        if (context == null) return null;
        if (isDone()) return null;
        if (!hasOldData()) return null;

        return migrate(context, false);
    }

    /**
     * Copy the old data to this installation.
     *
     * @param context The context, used for the toast/logs.
     * @param force When {@code true}, the migration is done even if the marker file exists; the
     *              existing files of the new installation are not overwritten.
     */
    @NonNull
    public static synchronized Result migrate(@Nullable Context context, boolean force) {
        File oldHome = new File(OLD_DATA_DIR_PATH, "files/home");
        if (!oldHome.isDirectory()) {
            return new Result(false, 0, true,
                "Eski kurulum (" + OLD_DATA_DIR_PATH + ") bulunamadı ya da okunamıyor.\n"
                    + "Eski uygulama kaldırıldıysa verileri Android ile birlikte silinmiştir.");
        }

        if (!canReadOldData()) {
            return new Result(false, 0, true,
                "Eski veriler okunamıyor: " + oldHome.getAbsolutePath() + "\n"
                    + "Dosya erişimi Android tarafından kısıtlanmış olabilir.\n"
                    + "Eski Termux'u kaldırmadan önce bu ekranı tekrar dene.");
        }

        int copied = 0;
        List<String> failures = new ArrayList<>();

        for (String relative : COPY_DIRECTORIES) {
            File source = new File(OLD_DATA_DIR_PATH, relative);
            File target = new File(NEW_DATA_DIR_PATH, relative);
            if (!source.isDirectory()) continue;
            copied += copyDirectory(source, target, failures);
        }

        for (String relative : COPY_FILES) {
            File source = new File(OLD_DATA_DIR_PATH, relative);
            File target = new File(NEW_DATA_DIR_PATH, relative);
            if (!source.isFile()) continue;
            if (copyFile(source, target)) copied++;
            else failures.add(relative);
        }

        // The marker is only written when the copy succeeded, so that a failed migration is retried
        // on the next start
        if (failures.isEmpty()) {
            writeMarker();
            Logger.logInfo(LOG_TAG, "Migrated " + copied + " files from \"" + OLD_DATA_DIR_PATH + "\"");
            return new Result(copied > 0, copied, false,
                copied + " dosya taşındı: " + OLD_DATA_DIR_PATH + " -> " + NEW_DATA_DIR_PATH);
        }

        StringBuilder message = new StringBuilder(copied + " dosya taşındı, ")
            .append(failures.size()).append(" dosya taşınamadı:\n");
        for (String failure : failures.subList(0, Math.min(8, failures.size()))) {
            message.append("  ").append(failure).append('\n');
        }
        message.append("Taşınamayanlar izinlerden dolayı olabilir; tekrar denenebilir.");
        return new Result(copied > 0, copied, false, message.toString());
    }

    /** Forget the migration, so that it runs again on the next start. */
    public static void reset() {
        //noinspection ResultOfMethodCallIgnored
        new File(MARKER_FILE_PATH).delete();
    }

    /* ---------------------------------------------------------------------------------------- */

    private static int copyDirectory(@NonNull File source, @NonNull File target,
                                     @NonNull List<String> failures) {
        File[] children = source.listFiles();
        if (children == null) {
            failures.add(relativeToDataDir(source));
            return 0;
        }

        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            failures.add(relativeToDataDir(target));
            return 0;
        }

        int copied = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                copied += copyDirectory(child, new File(target, child.getName()), failures);
            } else {
                // Existing files of the new installation are not overwritten
                File targetFile = new File(target, child.getName());
                if (targetFile.exists()) continue;
                if (copyFile(child, targetFile)) copied++;
                else failures.add(relativeToDataDir(child));
            }
        }
        return copied;
    }

    private static boolean copyFile(@NonNull File source, @NonNull File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return false;
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to copy \"" + source.getAbsolutePath() + "\"", e);
            return false;
        }

        // Keep the executable bit of the scripts, the rest is not important for the home folder
        if (source.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            target.setExecutable(true, false);
        }
        return true;
    }

    @NonNull
    private static String relativeToDataDir(@NonNull File file) {
        String path = file.getAbsolutePath();
        String prefix = OLD_DATA_DIR_PATH + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static void writeMarker() {
        File marker = new File(MARKER_FILE_PATH);
        File parent = marker.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(("migrated " + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write the migration marker", e);
        }
    }

    /** @return The first lines of the migration marker, used by the diagnostics. */
    @Nullable
    public static String readMarker() {
        File marker = new File(MARKER_FILE_PATH);
        if (!marker.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(marker), "UTF-8"))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
