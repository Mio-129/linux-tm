package com.termux.app.mio;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * Installs the `swset`, `so` and `mio` command scripts of this fork to `$PREFIX/bin`, so that they
 * can be run from the terminal like any other command.
 *
 * The scripts are stored in the assets of the app at `assets/mio/` and are copied the first time
 * the app is run, and again after an app update (the app version name is written to
 * {@link #VERSION_FILE_PATH} to detect updates).
 */
public final class MioBootstrap {

    public static final String LOG_TAG = "MioBootstrap";

    /** The name of the commands installed to `$PREFIX/bin`. */
    public static final List<String> COMMANDS = Arrays.asList("swset", "so", "mio");

    private static final String ASSET_DIR = "mio";
    private static final String VERSION_FILE_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/.mio-commands-version";

    private MioBootstrap() {
    }

    /**
     * Also called when the app is already running, so that the commands are installed right after
     * an update without the user having to restart the app.
     */
    public static synchronized void ensureInstalled(@Nullable Context context) {
        if (context == null) return;
        install(context);
    }

    /**
     * Install the commands if they are not installed or if the app was updated.
     *
     * @param context The {@link Context} to read the assets from.
     */
    public static synchronized void install(@Nullable Context context) {
        if (context == null) return;

        File binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR;
        if (!binDir.isDirectory()) {
            Logger.logDebug(LOG_TAG, "Not installing commands since \"" + binDir.getAbsolutePath() + "\" does not exist yet");
            return;
        }

        String installedVersion = readVersionFile();
        String currentVersion = getCurrentVersion();
        boolean forceInstall = installedVersion == null || !installedVersion.equals(currentVersion) ||
            !areCommandsInstalled(binDir) || !areCommandsUpToDate(context, binDir);

        if (!forceInstall) {
            Logger.logDebug(LOG_TAG, "Commands are already installed for version \"" + currentVersion + "\"");
            return;
        }

        Logger.logInfo(LOG_TAG, "Installing commands for version \"" + currentVersion + "\" to \"" + binDir.getAbsolutePath() + "\"");

        boolean allInstalled = true;
        for (String command : COMMANDS) {
            if (!installCommand(context, binDir, command)) allInstalled = false;
        }

        if (allInstalled) {
            writeVersionFile(currentVersion);
            Logger.logInfo(LOG_TAG, "Commands installed successfully: " + COMMANDS.toString());
        } else {
            Logger.logError(LOG_TAG, "Failed to install some of the commands, will retry on next app start");
        }
    }

    /**
     * Check if the installed commands are identical to the ones in the assets, so that the commands
     * are reinstalled if they were modified or deleted, like after a `pkg upgrade`.
     */
    private static boolean areCommandsUpToDate(@NonNull Context context, @NonNull File binDir) {
        for (String command : COMMANDS) {
            File file = new File(binDir, command);
            if (!file.isFile()) return false;

            try (InputStream asset = context.getAssets().open(ASSET_DIR + "/" + command)) {
                long installedLength = file.length();
                long assetLength = asset.available();
                if (installedLength != assetLength) {
                    Logger.logDebug(LOG_TAG, "Installed command \"" + command + "\" is outdated, reinstalling: installed length " + installedLength + ", asset length " + assetLength);
                    return false;
                }
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read asset of command \"" + command + "\"", e);
                return false;
            }
        }

        return true;
    }

    private static boolean areCommandsInstalled(@NonNull File binDir) {
        for (String command : COMMANDS) {
            File file = new File(binDir, command);
            if (!file.isFile()) return false;
        }
        return true;
    }

    private static boolean installCommand(@NonNull Context context, @NonNull File binDir, @NonNull String command) {
        File targetFile = new File(binDir, command);
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + command)) {
            try (FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int readBytes;
                while ((readBytes = in.read(buffer)) != -1) {
                    out.write(buffer, 0, readBytes);
                }
            }

            if (!targetFile.setExecutable(true, false)) {
                Logger.logWarn(LOG_TAG, "Failed to set the execute permission of \"" + targetFile.getAbsolutePath() + "\"");
            }

            Logger.logInfo(LOG_TAG, "Installed command \"" + command + "\" to \"" + targetFile.getAbsolutePath() + "\"");
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install command \"" + command + "\"", e);
            return false;
        }
    }

    @Nullable
    private static String readVersionFile() {
        File versionFile = new File(VERSION_FILE_PATH);
        if (!versionFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(versionFile), "UTF-8"))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read version file \"" + VERSION_FILE_PATH + "\"", e);
            return null;
        }
    }

    private static void writeVersionFile(@NonNull String version) {
        File versionFile = new File(VERSION_FILE_PATH);
        try (FileOutputStream out = new FileOutputStream(versionFile)) {
            out.write(version.getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write version file \"" + VERSION_FILE_PATH + "\"", e);
        }
    }

    @NonNull
    private static String getCurrentVersion() {
        return BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE;
    }
}
