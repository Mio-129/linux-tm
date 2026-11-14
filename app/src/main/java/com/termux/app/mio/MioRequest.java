package com.termux.app.mio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A request sent by the `swset` or `so` scripts.
 *
 * The broadcast sent by the scripts (`termux-am broadcast -a ...`) can only carry extras with
 * simple values, and arguments with spaces or unicode characters may be mangled by the `am`
 * command. To prevent this, the scripts also write the arguments to a `key=value` file at
 * {@code ~/.termux/mio/request} and the app reads it back. The broadcast is only a trigger.
 *
 * Example request file:
 * <pre>
 * created=1730000000
 * command=swset
 * name=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/photo.png
 * media_type=image
 * audio=0
 * dir=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "
 * </pre>
 */
public final class MioRequest {

    public static final String LOG_TAG = "MioRequest";

    public static final String KEY_COMMAND = "command";
    /** The action of the command, e.g. `com.termux.mio.action.MENU`. */
    public static final String KEY_ACTION = "action";
    /** The command line of the `mio` script, so that the app can handle commands without `am`. */
    public static final String KEY_ARGS = "args";
    public static final String KEY_NAME = "name";
    public static final String KEY_MEDIA_TYPE = "media_type";
    public static final String KEY_AUDIO = "audio";
    public static final String KEY_DIR = "dir";

    /** The path of the request file written by the scripts. */
    public static final String REQUEST_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/mio/request";

    /** The max age of a request file, older files are ignored to prevent stale requests. */
    private static final long MAX_REQUEST_AGE_SECONDS = 30;

    private final Map<String, String> mValues = new HashMap<>();

    private MioRequest() {
    }

    /**
     * Read the request file written by the scripts.
     *
     * @param expectedCommand The command that is expected to have written the request, one of
     * "swset" or "so". If the request was written by another command, then it is ignored.
     * @return Returns the {@link MioRequest} or {@code null} if the file does not exist, is older
     * than {@link #MAX_REQUEST_AGE_SECONDS} or was written by another command.
     */
    @Nullable
    public static MioRequest fromRequestFile(@Nullable String expectedCommand) {
        File requestFile = new File(REQUEST_FILE_PATH);
        if (!requestFile.isFile()) {
            Logger.logWarn(LOG_TAG, "Request file does not exist: \"" + requestFile.getAbsolutePath() + "\"");
            return null;
        }

        MioRequest request = new MioRequest();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(requestFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf('=');
                if (index <= 0) continue;
                String key = line.substring(0, index).trim();
                String value = line.substring(index + 1).trim();
                request.mValues.put(key, value);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read request file \"" + requestFile.getAbsolutePath() + "\"", e);
            return null;
        }

        long created = request.getLong("created", 0);
        if (created > 0) {
            long age = System.currentTimeMillis() / 1000 - created;
            if (age > MAX_REQUEST_AGE_SECONDS) {
                Logger.logWarn(LOG_TAG, "Ignoring stale request file, age: " + age + " seconds");
                return null;
            }
        }

        if (expectedCommand != null) {
            String command = request.getCommand();
            if (command != null && !command.equals(expectedCommand)) {
                Logger.logWarn(LOG_TAG, "Ignoring request from command \"" + command + "\", expected \"" + expectedCommand + "\"");
                return null;
            }
        }

        Logger.logDebug(LOG_TAG, "Read request: " + request);
        return request;
    }

    /**
     * Delete the request file, it should be called after the request has been handled so that the
     * same request is not read again by the next intent that is received.
     */
    public static void deleteRequestFile() {
        File requestFile = new File(REQUEST_FILE_PATH);
        if (requestFile.isFile() && !requestFile.delete())
            Logger.logWarn(LOG_TAG, "Failed to delete request file \"" + requestFile.getAbsolutePath() + "\"");
    }

    /**
     * Read the request file regardless of the command that wrote it.
     *
     * The `mio` command of the terminal can not always reach the app: `am` may be blocked by the
     * system ("am broadcast" and "am startservice" are restricted for apps that are not exported, and
     * some ROMs block `am start` for the shell user). The script always writes its request file, so
     * the app polls it while the service is running and handles the command from there, without any
     * broadcast.
     *
     * @return The request, or {@code null} if there is none or it was already handled.
     */
    @Nullable
    public static MioRequest fromAnyRequestFile() {
        File requestFile = new File(REQUEST_FILE_PATH);
        if (!requestFile.isFile()) return null;

        MioRequest request = new MioRequest();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(requestFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf('=');
                if (index <= 0) continue;
                request.mValues.put(line.substring(0, index).trim(), line.substring(index + 1).trim());
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + requestFile.getAbsolutePath() + "\"", e);
            return null;
        }

        return request.mValues.isEmpty() ? null : request;
    }

    /** @return The value of the `created` key as seconds, {@code -1} if it is missing. */
    public long getCreatedSeconds() {
        try {
            return Long.parseLong(mValues.get("created"));
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return The action of the command, {@code null} if the request has none. */
    @Nullable
    public String getAction() {
        return get(KEY_ACTION);
    }

    /** @return {@code true} if the request was written by the `mio` command of the terminal. */
    public boolean isFromMioCommand() {
        return "mio".equals(get(KEY_COMMAND));
    }

    @Nullable
    public String get(@NonNull String key) {
        String value = mValues.get(key);
        if (value == null || value.isEmpty()) return null;
        return value;
    }

    /**
     * @return Returns the command that sent the request, "swset" or "so", or {@code null} if not set.
     */
    @Nullable
    public String getCommand() {
        return get(KEY_COMMAND);
    }

    @Nullable
    public String getName() {
        return get(KEY_NAME);
    }

    @Nullable
    public String getMediaType() {
        return get(KEY_MEDIA_TYPE);
    }

    public boolean getAudio() {
        return "1".equals(get(KEY_AUDIO));
    }

    /** @return Returns the directory the command was run from, or {@code null} if not set. */
    @Nullable
    public String getDirectory() {
        return get(KEY_DIR);
    }

    /**
     * @return Returns the directory the command was run from as a {@link File}, or {@code null} if
     * it is not set or does not exist as a directory.
     */
    @Nullable
    public File getSearchDirectory() {
        String directory = getDirectory();
        if (directory == null) return null;
        File file = new File(directory);
        return file.isDirectory() ? file : null;
    }

    private long getLong(@NonNull String key, long defaultValue) {
        String value = get(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "MioRequest" + mValues;
    }
}
