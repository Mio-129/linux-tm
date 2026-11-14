package com.termux.app.mio;

import com.termux.shared.termux.TermuxConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The package store of the "Paketler" (packages) section of the Mio menu.
 *
 * A package is a folder below {@code ~/.mio/pkgs/<paket>} that contains one or more scripts:
 *
 * <ul>
 *     <li>{@code *.a.sh} - the "Older-Pack" script, the first script of a package,</li>
 *     <li>{@code *.b.sh} - the "Older-Tool" script, the follow up script of a package, which is run
 *     after the {@code .a.sh} script was installed,</li>
 *     <li>{@code *.start-up.sh} - the "start-up" script, run on every start of the app (only if the
 *     package is enabled) and after a package update,</li>
 *     <li>{@code *.c.sh}, {@code *.d.sh}, ... - further scripts, run in order after the
 *     {@code .a.sh} / {@code .b.sh} scripts.</li>
 * </ul>
 *
 * The scripts are the same files that are used by {@code kur.sh} (kur.s), so a package can be
 * installed by copying its folder to {@code ~/.mio/pkgs/} or by running {@code mio paket}.
 *
 * The state of a package (installed version, whether it is enabled) is stored with simple files
 * next to it, so that it can be read from both the app and the shell scripts:
 *
 * <ul>
 *     <li>{@code ~/.mio/state/<paket>.installed} - the version that was installed last,</li>
 *     <li>{@code ~/.mio/state/<paket>.disabled} - the package is disabled if this file exists,</li>
 *     <li>{@code ~/.mio/log/<paket>.log} - the output of the last run of a script.</li>
 * </ul>
 */
public final class MioPackages {

    public static final String LOG_TAG = "MioPackages";

    /** The folder that contains one folder per package. */
    public static final String PACKAGE_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.mio/pkgs";

    /** The folder of the install state of the packages. */
    public static final String STATE_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.mio/state";

    /** The folder of the logs of the packages. */
    public static final String LOG_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.mio/log";

    /** The suffix of the first script of a package, the "Older-Pack" script. */
    public static final String SCRIPT_A_SUFFIX = ".a.sh";

    /** The suffix of the follow up script of a package, the "Older-Tool" script. */
    public static final String SCRIPT_B_SUFFIX = ".b.sh";

    /** The suffix of the start-up script of a package, run on every start of the app. */
    public static final String STARTUP_SUFFIX = ".start-up.sh";

    /**
     * The key of the `URL` file of a package: if it is set, the installation of the package is the
     * script at that address, run with `bash <(curl -s <url>)` in the terminal, like the
     * `https://raw.githubusercontent.com/Mio-129/tm-pkg/main/older-pack` script of Older-Pack.
     */
    public static final String URL_FILE_NAME = "URL";

    private MioPackages() {
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Folders                                                                                  */
    /* ---------------------------------------------------------------------------------------- */

    @NonNull
    public static File getPackageDir() {
        return new File(PACKAGE_DIR_PATH);
    }

    @NonNull
    public static File getStateDir() {
        return new File(STATE_DIR_PATH);
    }

    @NonNull
    public static File getLogDir() {
        return new File(LOG_DIR_PATH);
    }

    /** Create the folders if they do not exist yet, returns {@code false} if that failed. */
    public static boolean ensureFolders() {
        return ensureDirectory(getPackageDir()) && ensureDirectory(getStateDir()) && ensureDirectory(getLogDir());
    }

    private static boolean ensureDirectory(@NonNull File directory) {
        return directory.isDirectory() || directory.mkdirs();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Packages                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    /** A script of a package. */
    public static final class Script {

        /** The file, like `~/.mio/pkgs/Older-Pack/Older-Pack.a.sh`. */
        @NonNull
        public final File file;

        /** The order of the script, `0` for the `.a.sh` script, `1` for the `.b.sh` script, ... */
        public final int order;

        /** If the script is the start-up script (`*.start-up.sh`). */
        public final boolean isStartUp;

        Script(@NonNull File file, int order, boolean isStartUp) {
            this.file = file;
            this.order = order;
            this.isStartUp = isStartUp;
        }

        /** The Turkish label of the script, like "Older-Pack (.a.sh)". */
        @NonNull
        public String getLabel() {
            if (isStartUp) return "start-up (.start-up.sh)";
            String name = file.getName();
            int index = name.indexOf(".a.sh");
            if (index < 0) index = name.indexOf(".b.sh");
            String suffix = index < 0 ? name : name.substring(index);
            String kind = suffix.equals(SCRIPT_A_SUFFIX) ? "Older-Pack"
                : suffix.equals(SCRIPT_B_SUFFIX) ? "Older-Tool" : "Adım " + (order + 1);
            return kind + " (" + suffix + ")";
        }

        @NonNull
        public String getShellCommand() {
            return "sh " + quote(file.getAbsolutePath());
        }
    }

    /** A package: a folder with one or more scripts. */
    public static final class Package {

        /** The folder of the package. */
        @NonNull
        public final File directory;

        /** The name of the package, which is the name of its folder. */
        @NonNull
        public final String name;

        /** The scripts of the package, the start-up script last. */
        @NonNull
        public final List<Script> scripts = new ArrayList<>();

        Package(@NonNull File directory) {
            this.directory = directory;
            this.name = directory.getName();
        }

        /** The version stored in the `VERSION` file of the package, or {@code null}. */
        @Nullable
        public String getVersion() {
            File versionFile = new File(directory, "VERSION");
            if (!versionFile.isFile()) return null;
            String version = readFile(versionFile);
            if (version == null) return null;
            version = version.trim();
            return version.isEmpty() ? null : version;
        }

        /** The installed version, {@code null} if the package was not installed yet. */
        @Nullable
        public String getInstalledVersion() {
            File file = new File(getStateDir(), name + ".installed");
            if (!file.isFile()) return null;
            String version = readFile(file);
            return version == null ? null : version.trim();
        }

        /** If the package was installed at least once. */
        public boolean isInstalled() {
            return new File(getStateDir(), name + ".installed").isFile();
        }

        /** If the package is enabled, a new package is enabled until it is disabled. */
        public boolean isEnabled() {
            return !new File(getStateDir(), name + ".disabled").isFile();
        }

        /** If a newer (or a different) version of the package is available. */
        public boolean hasUpdate() {
            String version = getVersion();
            String installed = getInstalledVersion();
            if (installed == null) return true;
            if (version == null) return false;
            return !version.equals(installed);
        }

        /** The remote install script of the package from its `URL` file, {@code null} if none. */
        @Nullable
        public String getRemoteUrl() {
            File urlFile = new File(directory, URL_FILE_NAME);
            if (!urlFile.isFile()) return null;
            String url = readFile(urlFile);
            if (url == null) return null;
            url = url.trim();
            return url.isEmpty() ? null : url;
        }

        /** If the package is installed by running a remote script instead of its local scripts. */
        public boolean hasRemoteInstall() {
            return getRemoteUrl() != null;
        }

        /** The start-up script of the package, {@code null} if it has none. */
        @Nullable
        public Script getStartUpScript() {
            for (Script script : scripts) {
                if (script.isStartUp) return script;
            }
            return null;
        }

        /** Write the installed version, or remove it if {@code version} is {@code null}. */
        public void setInstalledVersion(@Nullable String version) {
            File file = new File(getStateDir(), name + ".installed");
            if (version == null) {
                if (file.isFile() && !file.delete())
                    com.termux.shared.logger.Logger.logWarn(LOG_TAG, "Failed to delete \"" + file.getAbsolutePath() + "\"");
                return;
            }
            writeFile(file, version + "\n");
        }

        /** Enable or disable the package, disabled packages are not started on app start. */
        public void setEnabled(boolean enabled) {
            File file = new File(getStateDir(), name + ".disabled");
            if (enabled) {
                if (file.isFile() && !file.delete())
                    com.termux.shared.logger.Logger.logWarn(LOG_TAG, "Failed to delete \"" + file.getAbsolutePath() + "\"");
            } else {
                writeFile(file, "disabled\n");
            }
        }

        @NonNull
        public File getLogFile() {
            return new File(getLogDir(), name + ".log");
        }

        /** The last lines of the log of the package, empty if it was not run yet. */
        @NonNull
        public String getLogTail(int maxLines) {
            String content = readFile(getLogFile());
            if (content == null) return "";
            String[] lines = content.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder builder = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(lines[i]);
            }
            return builder.toString();
        }

        /** A one line summary of the state of the package, used as the button label. */
        @NonNull
        public String getSummary() {
            StringBuilder summary = new StringBuilder(name);
            if (isInstalled()) {
                String installed = getInstalledVersion();
                summary.append(" — kurulu");
                if (installed != null) summary.append(" v").append(installed);
                if (hasUpdate()) summary.append(" (güncelleme var)");
            } else {
                summary.append(" — kurulmadı");
            }
            if (!isEnabled()) summary.append(" • kapalı");
            if (hasRemoteInstall()) summary.append(" • uzaktan");
            return summary.toString();
        }
    }

    /** Read all packages from {@link #PACKAGE_DIR_PATH}, sorted by name. */
    @NonNull
    public static List<Package> getPackages() {
        List<Package> packages = new ArrayList<>();
        File[] directories = getPackageDir().listFiles(File::isDirectory);
        if (directories == null) return packages;

        for (File directory : directories) {
            // Skip hidden folders like a `.git` folder of a cloned repository
            if (directory.getName().startsWith(".")) continue;
            Package pkg = readPackage(directory);
            if (pkg != null) packages.add(pkg);
        }

        packages.sort((first, second) -> first.name.compareToIgnoreCase(second.name));
        return packages;
    }

    @Nullable
    private static Package readPackage(@NonNull File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".sh"));
        Package pkg = new Package(directory);
        if (files == null) return pkg;

        List<Script> scripts = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            boolean isStartUp = name.endsWith(STARTUP_SUFFIX);
            int order = getScriptOrder(name);
            scripts.add(new Script(file, order, isStartUp));
        }

        // The start-up script is listed last, the other scripts in the order of their letter
        scripts.sort((first, second) -> {
            if (first.isStartUp != second.isStartUp) return first.isStartUp ? 1 : -1;
            if (first.order != second.order) return Integer.compare(first.order, second.order);
            return first.file.getName().compareToIgnoreCase(second.file.getName());
        });
        pkg.scripts.addAll(scripts);
        return pkg;
    }

    /**
     * @return Returns {@code 0} for `*.a.sh`, {@code 1} for `*.b.sh`, and so on. Scripts without a
     * letter, like `*.start-up.sh`, are put before the letters.
     */
    private static int getScriptOrder(@NonNull String name) {
        String lowerCase = name.toLowerCase(Locale.ROOT);
        if (lowerCase.endsWith(STARTUP_SUFFIX)) return -1;

        // The letter is the character before the `.sh` suffix, like `b` in `Older-Tool.b.sh`
        int length = lowerCase.length();
        if (length < 5 || !lowerCase.endsWith(".sh")) return 100;

        char character = lowerCase.charAt(length - 4);
        if (character >= 'a' && character <= 'z') {
            return character - 'a';
        }
        return 100;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Update check                                                                             */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * @return Returns the packages that were not installed yet or that have a new version, which are
     * the packages shown as "Paket güncelleme" in the menu.
     */
    @NonNull
    public static List<Package> getUpdatablePackages() {
        List<Package> updatable = new ArrayList<>();
        for (Package pkg : getPackages()) {
            if (pkg.hasUpdate()) updatable.add(pkg);
        }
        return updatable;
    }

    /**
     * Mark every package as installed with its current version, which is done after the scripts of
     * the package were run. The start-up script of a package is run on the next app start.
     *
     * @return Returns the number of packages that were updated.
     */
    public static int markAllInstalled() {
        int count = 0;
        for (Package pkg : getPackages()) {
            pkg.setInstalledVersion(pkg.getVersion() == null ? "1" : pkg.getVersion());
            count++;
        }
        return count;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* File helpers                                                                             */
    /* ---------------------------------------------------------------------------------------- */

    /** Quote a value so that it can be passed to the shell as a single argument. */
    @NonNull
    public static String quote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Nullable
    private static String readFile(@NonNull File file) {
        if (!file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception e) {
            com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to read \"" + file.getAbsolutePath() + "\"", e);
            return null;
        }
    }

    private static boolean writeFile(@NonNull File file, @NonNull String content) {
        File directory = file.getParentFile();
        if (directory != null) ensureDirectory(directory);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            com.termux.shared.logger.Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to write \"" + file.getAbsolutePath() + "\"", e);
            return false;
        }
    }
}
