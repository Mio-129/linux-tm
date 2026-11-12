package com.termux.app.mio;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * The font, color and fullscreen helpers of the `mio` command, so that the terminal can be
 * customized from the command line like the "beautify" (美化) settings of ZeroTermux.
 *
 * - The font size is stored in the {@link TermuxAppSharedPreferences} with
 *   {@link TermuxAppSharedPreferences#setFontSize(int)}.
 * - The colors are stored in {@code ~/.termux/colors.properties}, the same file that
 *   Termux:Styling writes, and the same keys as {@link com.termux.terminal.TerminalColors}.
 * - The fullscreen mode is stored in {@code ~/.termux/termux.properties} with the
 *   {@code fullscreen} key of {@link com.termux.shared.termux.settings.properties.TermuxPropertyConstants#KEY_USE_FULLSCREEN}.
 */
public final class MioTextStyle {

    public static final String LOG_TAG = "MioTextStyle";

    /** The color keys of `~/.termux/colors.properties`, in the order of the ANSI colors. */
    public static final String[] COLOR_NAMES = new String[]{
        "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
        "bright_black", "bright_red", "bright_green", "bright_yellow",
        "bright_blue", "bright_magenta", "bright_cyan", "bright_white"
    };

    public static final String KEY_FOREGROUND = "foreground";
    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_CURSOR = "cursor";

    private MioTextStyle() {
    }

    @NonNull
    public static File getColorsFile() {
        return TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
    }

    @NonNull
    public static File getPropertiesFile() {
        return TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Font size                                                                                */
    /* ---------------------------------------------------------------------------------------- */

    /** @return Returns the current font size in dp. */
    public static int getFontSize(@NonNull Context context) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, false);
        return preferences == null ? 0 : preferences.getFontSize();
    }

    /** @return Returns the default font size in dp. */
    public static int getDefaultFontSize(@NonNull Context context) {
        return TermuxAppSharedPreferences.getDefaultFontSizes(context)[0];
    }

    /**
     * Set the font size and apply it to the running activity.
     *
     * @param fontSize The new font size in dp, {@code 0} for the default size.
     * @return Returns the size that was actually set, {@code -1} if it could not be changed.
     */
    public static int setFontSize(@NonNull Context context, int fontSize) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, false);
        if (preferences == null) return -1;

        if (fontSize <= 0) fontSize = getDefaultFontSize(context);
        preferences.setFontSize(fontSize);

        TermuxActivity activity = TermuxActivity.getInstance();
        if (activity != null && activity.getTerminalView() != null) {
            int applied = preferences.getFontSize();
            activity.getTerminalView().setTextSize(applied);
            return applied;
        }
        return preferences.getFontSize();
    }

    /** Increase or decrease the font size by {@code delta} dp. */
    public static int changeFontSize(@NonNull Context context, int delta) {
        return setFontSize(context, getFontSize(context) + delta);
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Fullscreen                                                                               */
    /* ---------------------------------------------------------------------------------------- */

    /** @return Returns {@code true} if the `fullscreen` property is enabled. */
    public static boolean isFullScreen(@NonNull Context context) {
        TermuxActivity activity = TermuxActivity.getInstance();
        if (activity != null && activity.getProperties() != null)
            return activity.getProperties().isUsingFullScreen();
        return "true".equals(getProperty("fullscreen"));
    }

    /**
     * Enable or disable the fullscreen mode, the activity is recreated so that the window flag is
     * applied (or removed).
     *
     * @return Returns the value that was written.
     */
    public static boolean setFullScreen(@NonNull Context context, boolean enabled) {
        boolean written = setProperty("fullscreen", enabled ? "true" : "false");
        if (!written) return !enabled;

        TermuxActivity activity = TermuxActivity.getInstance();
        if (activity != null) {
            TermuxActivity.updateTermuxActivityStyling(activity, true);
        }
        return enabled;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Colors                                                                                   */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * Set one color of the terminal, like {@code foreground}, {@code background} or one of the
     * names in {@link #COLOR_NAMES}, and reload the terminal styling.
     *
     * @param value The color, like {@code #FF8800}.
     * @return Returns {@code true} if the color was written.
     */
    public static boolean setColor(@NonNull Context context, @NonNull String key, @NonNull String value) {
        Properties properties = readColors();
        properties.setProperty(key, value);
        if (!writeColors(properties)) return false;
        reloadStyling(context);
        return true;
    }

    /**
     * Reset all colors to the colors of the app theme, by deleting `~/.termux/colors.properties`.
     *
     * @return Returns {@code true} if the file was deleted or did not exist.
     */
    public static boolean resetColors(@NonNull Context context) {
        File colorsFile = getColorsFile();
        if (colorsFile.exists() && !colorsFile.delete()) {
            Logger.logError(LOG_TAG, "Failed to delete colors file \"" + colorsFile.getAbsolutePath() + "\"");
            return false;
        }
        writeColors(new Properties());
        reloadStyling(context);
        return true;
    }

    /** @return Returns the color value for the key, or {@code null} if it is not set. */
    @Nullable
    public static String getColor(@NonNull String key) {
        return readColors().getProperty(key);
    }

    /** @return Returns a `key=value` dump of the colors file. */
    @NonNull
    public static String getColorsString() {
        Properties properties = readColors();
        if (properties.isEmpty()) return "(varsayılan renkler)";

        StringBuilder builder = new StringBuilder();
        for (String key : properties.stringPropertyNames()) {
            builder.append(key).append('=').append(properties.getProperty(key)).append('\n');
        }
        return builder.toString().trim();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Helpers                                                                                  */
    /* ---------------------------------------------------------------------------------------- */

    /** Reload the colors and the font of an already running activity. */
    public static void reloadStyling(@NonNull Context context) {
        TermuxActivity activity = TermuxActivity.getInstance();
        if (activity != null) {
            TermuxActivity.updateTermuxActivityStyling(activity, false);
        } else {
            TermuxActivity.updateTermuxActivityStyling(context, false);
        }
    }

    /** Read the `~/.termux/termux.properties` file, an empty set of properties is returned if it does not exist. */
    @NonNull
    private static Properties readTermuxProperties() {
        Properties properties = new Properties();
        File propertiesFile = getPropertiesFile();
        if (!propertiesFile.isFile()) return properties;

        try (InputStream in = new FileInputStream(propertiesFile)) {
            properties.load(in);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + propertiesFile.getAbsolutePath() + "\"", e);
        }
        return properties;
    }

    @NonNull
    private static Properties readColors() {
        Properties properties = new Properties();
        File colorsFile = getColorsFile();
        if (!colorsFile.isFile()) return properties;

        try (InputStream in = new FileInputStream(colorsFile)) {
            properties.load(in);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + colorsFile.getAbsolutePath() + "\"", e);
        }
        return properties;
    }

    private static boolean writeColors(@NonNull Properties properties) {
        File colorsFile = getColorsFile();
        File directory = colorsFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create directory \"" + directory.getAbsolutePath() + "\"");
            return false;
        }

        try (FileOutputStream out = new FileOutputStream(colorsFile)) {
            properties.store(out, "Written by the mio command of this Termux fork");
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + colorsFile.getAbsolutePath() + "\"", e);
            return false;
        }
    }

    /** @return Returns the value of the property in `~/.termux/termux.properties`, or {@code null}. */
    @Nullable
    public static String getProperty(@NonNull String key) {
        Properties properties = readTermuxProperties();
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    /**
     * Set a property in `~/.termux/termux.properties`, the other properties and comments of the
     * file are kept as they are, except for the comment header written by {@link Properties#store}.
     */
    public static boolean setProperty(@NonNull String key, @NonNull String value) {
        File propertiesFile = getPropertiesFile();
        Properties properties = new Properties();
        String existingContent = "";

        if (propertiesFile.isFile()) {
            try (InputStream in = new FileInputStream(propertiesFile)) {
                properties.load(in);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + propertiesFile.getAbsolutePath() + "\"", e);
            }
            try {
                existingContent = readFile(propertiesFile);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read text of \"" + propertiesFile.getAbsolutePath() + "\"", e);
            }
        }

        File directory = propertiesFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create directory \"" + directory.getAbsolutePath() + "\"");
            return false;
        }

        try (FileOutputStream out = new FileOutputStream(propertiesFile)) {
            // Keep the existing file as it is (comments included) and only replace the key, this
            // avoids reordering the users file like Properties.store() would do
            StringBuilder builder = new StringBuilder();
            if (!existingContent.isEmpty() && !existingContent.endsWith("\n")) existingContent += "\n";
            boolean replaced = false;
            for (String line : existingContent.split("\n", -1)) {
                String trimmed = line.trim();
                if (!replaced && (trimmed.startsWith(key + "=") || trimmed.startsWith(key + " ="))) {
                    builder.append(key).append('=').append(value).append('\n');
                    replaced = true;
                } else {
                    builder.append(line).append('\n');
                }
            }

            if (!replaced) {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n')
                    builder.append('\n');
                builder.append(key).append('=').append(value).append('\n');
            }

            String output = builder.toString();
            // Remove the empty line that the trailing newline of the existing file produces
            while (output.startsWith("\n")) output = output.substring(1);
            if (output.endsWith("\n\n")) output = output.substring(0, output.length() - 1);
            out.write(output.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + propertiesFile.getAbsolutePath() + "\"", e);
            return false;
        }
    }

    @NonNull
    private static String readFile(@NonNull File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        return builder.toString();
    }

    /** @return Returns the index of the color name in {@link #COLOR_NAMES}, or {@code -1}. */
    public static int getColorIndex(@Nullable String name) {
        if (name == null) return -1;
        String lowerCaseName = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(lowerCaseName)) return i;
        }
        return -1;
    }
}
