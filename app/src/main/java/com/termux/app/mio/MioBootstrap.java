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

        // A broken `sources.list` (a directory instead of a file) makes `pkg` fail with
        // "Read error - read (21: Is a directory)", so it is repaired before anything is installed
        repairAptSources();
        sanitizeSourcesFile();
        repairAptMethods();
        repairAptState();
        repairAptConfigFile();
        ensureHushLoginOnce();
        ensureHushLoginScript();
        silenceLoginSecondStageFallback();

        File binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR;
        if (!binDir.isDirectory()) {
            Logger.logDebug(LOG_TAG, "Not installing commands since \"" + binDir.getAbsolutePath() + "\" does not exist yet");
            return;
        }

        String installedVersion = readVersionFile();
        String currentVersion = getCurrentVersion(context);
        repairUnpatchedElfsIfNeeded(currentVersion);
        boolean forceInstall = installedVersion == null || !installedVersion.equals(currentVersion) ||
            !areCommandsInstalled(binDir) || !areCommandsUpToDate(context, binDir);

        if (!forceInstall) {
            Logger.logDebug(LOG_TAG, "Commands are already installed for version \"" + currentVersion + "\"");
            // An update that only changes the wrappers does not touch the commands, so the
            // wrappers are refreshed even when the commands are already up to date.
            repairPackageWrappers();
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
            // The wrappers live in the same prefix as the commands, so they can be refreshed now
            repairPackageWrappers();
        } else {
            Logger.logError(LOG_TAG, "Failed to install some of the commands, will retry on next app start");
        }
    }

    /**
     * Run `mio sarmalayici` in the background, so that the apt and dpkg wrappers are refreshed on
     * every app start (no network access, only the wrapper files are rewritten).
     *
     * The dpkg wrapper is what converts the `com.termux` paths of a package to `com.mio` before
     * dpkg unpacks it. It used to be installed only when `mio apt-fix` or `mio depo` was run, so a
     * user who went straight to `apt install` kept an old wrapper, which ignored the conversion
     * errors and let dpkg fail with `unable to stat './data/data/com.termux'` and exit code 1
     * while printing nothing about the real reason.
     */
    private static void repairPackageWrappers() {
        final File mio = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "mio");
        if (!mio.isFile()) return;

        final File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        Thread thread = new Thread(() -> {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(mio.getAbsolutePath(), "sarmalayici");
                builder.redirectErrorStream(true);
                builder.redirectOutput(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "tmp/mio-wrappers.log"));
                builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                builder.environment().put("HOME", home.getAbsolutePath());
                builder.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
                builder.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");
                process = builder.start();
                int exitCode = process.waitFor();
                Logger.logInfo(LOG_TAG, "Refreshed the package wrappers with `mio sarmalayici`, exit code " + exitCode);
                ensureBaseToolsOnce();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh the package wrappers", e);
            } finally {
                if (process != null) process.destroy();
            }
        }, "mio-package-wrappers");
        thread.setDaemon(true);
        thread.start();
    }

    private static volatile boolean sBaseToolsAttempted = false;

    /**
     * Silently install `curl` and `wget` once on first launch when missing, so scripts and
     * "Mio Paket" downloads do not have to install or ask for them later.
     */
    private static void ensureBaseToolsOnce() {
        if (sBaseToolsAttempted) return;
        sBaseToolsAttempted = true;

        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/.mio-base-tools-done");
        File curl = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "curl");
        File wget = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "wget");
        if (marker.isFile() && curl.isFile() && wget.isFile()) return;
        if (curl.isFile() && wget.isFile()) {
            if (marker.getParentFile() != null && marker.getParentFile().isDirectory()) {
                writeBytes(marker, new byte[]{'1', '\n'});
            }
            return;
        }

        File sh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "sh");
        if (!sh.isFile()) return;

        Process process = null;
        try {
            String cmd = "for _pc in /proc/[0-9]*/cmdline; do "
                + "[ -r \"$_pc\" ] || continue; "
                + "if tr '\\000' ' ' < \"$_pc\" 2>/dev/null | grep -qE 'dpkg\\.mio-real|apt\\.mio-real|apt-get\\.mio-real'; then exit 0; fi; "
                + "done; "
                + "(ls \"$PREFIX/var/lib/apt/lists/\"*Packages* >/dev/null 2>&1 || apt-get update -qq) && "
                + "apt-get install -y -qq curl wget";
            ProcessBuilder builder = new ProcessBuilder(sh.getAbsolutePath(), "-c", cmd);
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "tmp/mio-base-tools.log"));
            builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            builder.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            builder.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
            builder.environment().put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
            builder.environment().put("APT_CONFIG", TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH + "/apt/apt.conf.d/mio-apt.conf");
            builder.environment().put("PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/libexec/mio:"
                + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");
            builder.environment().remove("LD_PRELOAD");
            process = builder.start();
            boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
            } else if (curl.isFile() && wget.isFile() && marker.getParentFile() != null && marker.getParentFile().isDirectory()) {
                writeBytes(marker, new byte[]{'1', '\n'});
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to install base tools (curl/wget): " + e.getMessage());
        } finally {
            if (process != null) process.destroy();
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

            rewritePrefixPaths(targetFile);

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

    /**
     * Repair the apt sources of the prefix.
     *
     * `sources.list` has to be a file. In some installations (a bootstrap that was extracted while
     * the prefix was patched) it ends up being a directory, and then every `pkg` command fails with
     * `E: Read error - read (21: Is a directory)`. The directory is replaced with the list that it
     * contains, or with the default Termux mirror.
     *
     * @return {@code true} if something was repaired.
     */
    public static boolean repairAptSources() {
        final File aptDir = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt");
        if (!aptDir.isDirectory()) return false;

        boolean repaired = false;

        File sourcesList = new File(aptDir, "sources.list");
        // A symlink, a directory or any other non regular file breaks apt ("Is a directory")
        boolean broken = !sourcesList.isFile();
        if (broken) {
            Logger.logInfo(LOG_TAG, "Repairing \"" + sourcesList.getAbsolutePath() + "\", which is not a regular file");

            String content = null;
            File[] children = sourcesList.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isFile() && child.getName().contains("sources.list")) {
                        content = readText(child);
                        if (content != null && !content.trim().isEmpty()) break;
                        content = null;
                    }
                }
            }
            if (content != null) content = sanitizeSources(content);
            if (content == null || content.trim().isEmpty()) {
                content = "deb https://packages-cf.termux.dev/apt/termux-main stable main\n";
            }

            File temporary = new File(aptDir, "sources.list.mio-tmp");
            boolean written = false;
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(content.getBytes("UTF-8"));
                written = true;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + temporary.getAbsolutePath() + "\"", e);
            }

            if (written && deleteRecursively(sourcesList) && temporary.renameTo(sourcesList)) {
                Logger.logInfo(LOG_TAG, "Repaired \"" + sourcesList.getAbsolutePath() + "\"");
                repaired = true;
            } else {
                Logger.logWarn(LOG_TAG, "Failed to repair \"" + sourcesList.getAbsolutePath() + "\"");
            }
        }

        // Directories inside `sources.list.d` are ignored by apt, but they keep failing the ones that
        // are followed by a real entry, so they are removed as well
        File sourcesListDir = new File(aptDir, "sources.list.d");
        File[] entries = sourcesListDir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    if (deleteRecursively(entry)) repaired = true;
                    Logger.logInfo(LOG_TAG, "Removed the broken sources entry \"" + entry.getAbsolutePath() + "\"");
                }
            }
        }


        // apt reads `Dir::Etc::sourceparts` as a directory: if `sources.list` ends up being that
        // directory, apt prints "Ignoring file 'sources.list' ... invalid filename extension"
        // together with "E: Read error - read (21: Is a directory)". Both paths are pinned to
        // absolute locations here so that a broken value can never leak into apt again.
        File sourceParts = new File(aptDir, "sources.list.d");
        // A symlink is only removed when it does not point to a directory: apt must be able to read
        // it as the `sourceparts` directory, and a link to `..` makes it list `sources.list` itself.
        String sourcePartsLink = readSymlink(sourceParts);
        if (sourcePartsLink != null) {
            if (!sourceParts.isDirectory() && sourceParts.delete()) repaired = true;
        } else if (sourceParts.exists() && !sourceParts.isDirectory()) {
            if (deleteRecursively(sourceParts)) repaired = true;
        }
        if (!sourceParts.isDirectory() && sourceParts.mkdirs()) repaired = true;

        File aptConfDir = new File(aptDir, "apt.conf.d");
        if (aptConfDir.exists() && !aptConfDir.isDirectory()) {
            if (deleteRecursively(aptConfDir)) repaired = true;
        }
        if (!aptConfDir.isDirectory() && aptConfDir.mkdirs()) repaired = true;

        File mainList = new File(sourceParts, "mio.list");
        if (!mainList.isFile() || mainList.length() == 0) {
            String url = readFirstDebUrl(sourcesList);
            if (url == null) url = "https://packages-cf.termux.dev/apt/termux-main";
            if (writeText(mainList, "deb " + url + " stable main\n")) repaired = true;
        }

        // An older version of the app wrote this file; apt reads `apt.conf.d` alphabetically and the
        // pinned values must win over anything else
        File oldAptConf = new File(aptConfDir, "99-mio-sources.conf");
        if (oldAptConf.exists() && oldAptConf.delete()) repaired = true;

        // Any other file in `apt.conf.d` that mentions `sources.list` without setting the proper
        // `Dir::Etc::sourcelist` key makes apt resolve the sources path as a directory, which is
        // exactly what prints "Ignoring file 'sources.list' ... Is a directory"
        File[] confFiles = aptConfDir.listFiles();
        if (confFiles != null) {
            for (File confFile : confFiles) {
                if (!confFile.isFile() || confFile.getName().equals("zz-mio-sources.conf")) continue;
                if (confFile.length() > 8192) continue;
                String content = readText(confFile);
                if (content != null && content.contains("sources.list") && !content.contains("Dir::Etc::sourcelist")) {
                    if (confFile.delete()) repaired = true;
                    Logger.logInfo(LOG_TAG, "Removed the broken apt configuration \"" + confFile.getAbsolutePath() + "\"");
                }
            }
        }

        File aptConf = new File(aptConfDir, "zz-mio-sources.conf");
        String conf = "# mio: pin the apt source locations\n" +
            "Dir::Etc::sourcelist \"" + sourcesList.getAbsolutePath() + "\";\n" +
            "Dir::Etc::sourceparts \"" + sourceParts.getAbsolutePath() + "\";\n";
        String currentConf = aptConf.isFile() ? readText(aptConf) : null;
        if (currentConf == null || !currentConf.equals(conf)) {
            if (writeText(aptConf, conf)) repaired = true;
        }

        return repaired;
    }



    /**
     * Drop the `deb` lines whose URI is not http(s).
     *
     * A single bad line (for example `deb Termux stable main`) makes apt fail with
     * "Malformed entry ... (URI parse)" and then "The list of sources could not be read.", so no
     * command works at all. The valid lines are kept; if none are left {@code null} is returned and
     * the caller writes the default mirror.
     */
    private static String sanitizeSources(String content) {
        StringBuilder builder = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("deb ")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2 || !(parts[1].startsWith("http://") || parts[1].startsWith("https://"))) {
                    continue;
                }
            }
            builder.append(line).append("\n");
        }
        return builder.toString().trim().isEmpty() ? null : builder.toString();
    }

    /**
     * Remove the invalid `deb` lines from an otherwise healthy `sources.list`.
     *
     * A line like `deb Termux stable main` (written by an older mirror picker) makes every apt
     * command fail with "Malformed entry ... (URI parse)". The file is only rewritten when
     * something actually has to be dropped.
     *
     * @return {@code true} if the file was repaired.
     */
    public static boolean sanitizeSourcesFile() {
        File sourcesList = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt/sources.list");
        if (!sourcesList.isFile()) return false;
        String current = readText(sourcesList);
        if (current == null || current.trim().isEmpty()) return false;
        String sanitized = sanitizeSources(current);
        if (sanitized == null) return false;
        if (sanitized.equals(current)) return false;
        Logger.logInfo(LOG_TAG, "Dropping the invalid entries of \"" + sourcesList.getAbsolutePath() + "\"");
        return writeText(sourcesList, sanitized);
    }

    /**
     * apt needs its working directories (`var/lib/apt/lists/partial`, `var/cache/apt/archives/partial`,
     * `var/lib/dpkg`, `tmp`). When they are missing apt fails with
     * "E: List directory .../usr/var/lib/apt is missing" and the repository is never read.
     */
    public static boolean repairAptState() {
        boolean repaired = false;
        String[] directories = new String[]{
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg",
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt",
            "etc/apt/trusted.gpg.d",
            "tmp"
        };
        for (String directory : directories) {
            File file = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, directory);
            if (!file.isDirectory() && file.mkdirs()) {
                repaired = true;
                Logger.logInfo(LOG_TAG, "Created the apt directory \"" + file.getAbsolutePath() + "\"");
            }
        }
        File status = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/status");
        if (!status.isFile()) {
            if (writeText(status, "")) repaired = true;
        }

        // dpkg, kurulu paketlerin dosya listelerini `var/lib/dpkg/info/*.list` içinde tutar ve
        // bu dosyaların son satırının satır sonuyla bitmesini şart koşar. Termux öneğinde bazı
        // listeler satır sonu olmadan yazılmış oluyor; dpkg o zaman
        // "files list file for package 'sed' is missing final newline" deyip kurulumu
        // "unrecoverable fatal error" ile tamamen durduruyor. Eksik satır sonları burada eklenir.
        if (repairDpkgFileLists()) repaired = true;
        return repaired;
    }

    /**
     * Add the missing trailing newlines to the dpkg file lists, which otherwise make every
     * `apt install` abort with "files list file for package ... is missing final newline".
     *
     * @return {@code true} if a file list was repaired.
     */
    public static boolean repairDpkgFileLists() {
        File infoDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/info");
        if (!infoDir.isDirectory()) return false;
        File[] files = infoDir.listFiles();
        if (files == null) return false;

        boolean repaired = false;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.contains(".mio-tmp")) {
                if (file.delete()) repaired = true;
                continue;
            }
            boolean isList = name.endsWith(".list");
            if (!isList && !name.endsWith(".md5sums") && !name.endsWith(".conffiles") && !name.endsWith(".triggers")) {
                continue;
            }
            byte[] raw = readAllBytesQuiet(file);
            if (raw == null) continue;

            // `TermuxInstaller` eski sürümlerde metin dosyalarının sonuna NUL (\0) baytı
            // eklemişti; ayrıca satır sonu eksik ya da 0 baytlı .list dosyaları da dpkg'yi
            // "files list file for package ... is missing final newline" ile durduruyor.
            java.io.ByteArrayOutputStream clean = new java.io.ByteArrayOutputStream(raw.length + 4);
            for (byte b : raw) {
                if (b != 0) clean.write(b);
            }
            byte[] cleaned = clean.toByteArray();
            if (cleaned.length == 0) {
                if (isList) {
                    cleaned = new byte[]{'/', '.', '\n'};
                } else {
                    if (raw.length == 0) continue;
                }
            } else if (cleaned[cleaned.length - 1] != '\n') {
                clean.write('\n');
                cleaned = clean.toByteArray();
            }

            if (!Arrays.equals(raw, cleaned)) {
                if (writeBytes(file, cleaned)) repaired = true;
            }
        }
        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/.mio-lists-nul-cleaned");
        if (!marker.exists() || repaired) {
            writeBytes(marker, new byte[]{'1', '\n'});
        }
        return repaired;
    }

    /**
     * Previously `.so` files were skipped by `mio_binary_like`, leaving DT_RUNPATH pointing to
     * `/data/data/com.termux/files/usr/lib` inside libraries like `libpython3.14.so`. Scan
     * `$PREFIX/{lib,bin,libexec}` once per app update (or when dpkg info changes) and patch any
     * remaining ELF paths in-place using the equal-length `/data/data/com.mio///` replacement.
     */
    public static void repairUnpatchedElfsIfNeeded(@NonNull String currentVersion) {
        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/.mio-elfs-checked");
        File infoDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/info");
        if (marker.isFile()) {
            String prev = readText(marker);
            if (prev != null && prev.trim().equals(currentVersion)) {
                if (!infoDir.isDirectory() || infoDir.lastModified() <= marker.lastModified()) {
                    return;
                }
            }
        }

        byte[] from;
        byte[] to;
        try {
            from = "/data/data/com.termux".getBytes("UTF-8");
            to = "/data/data/com.mio///".getBytes("UTF-8");
        } catch (Exception e) {
            return;
        }
        if (from.length != to.length) return;

        int fixed = 0;
        for (String sub : new String[]{"lib", "bin", "libexec"}) {
            File dir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, sub);
            if (dir.isDirectory()) {
                fixed += repairElfDirectory(dir, from, to);
            }
        }
        if (fixed > 0) {
            Logger.logInfo(LOG_TAG, "Patched legacy com.termux paths in " + fixed + " ELF/shared-library files");
        }
        if (marker.getParentFile() != null && marker.getParentFile().isDirectory()) {
            writeText(marker, currentVersion + "\n");
        }
    }

    private static int repairElfDirectory(@NonNull File dir, @NonNull byte[] from, @NonNull byte[] to) {
        if (isSymlink(dir)) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int fixed = 0;
        for (File child : children) {
            if (isSymlink(child)) continue;
            if (child.isDirectory()) {
                fixed += repairElfDirectory(child, from, to);
            } else if (child.isFile()) {
                if (patchElfLegacyPath(child, from, to)) fixed++;
            }
        }
        return fixed;
    }

    private static boolean patchElfLegacyPath(@NonNull File file, @NonNull byte[] from, @NonNull byte[] to) {
        long size = file.length();
        if (size < 16 || size > 32L * 1024 * 1024) return false;
        if (!isElfFile(file)) return false;

        byte[] data = readAllBytesQuietLimit(file, 32L * 1024 * 1024);
        if (data == null) return false;
        int pos = indexOfBytes(data, from, 0);
        if (pos < 0) return false;

        while (pos >= 0) {
            System.arraycopy(to, 0, data, pos, to.length);
            pos = indexOfBytes(data, from, pos + to.length);
        }

        boolean executable = file.canExecute();
        if (!writeBytes(file, data)) return false;
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        }
        return true;
    }

    private static boolean isElfFile(@NonNull File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            int n = in.read(magic);
            return n == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSymlink(@NonNull File file) {
        try {
            android.system.StructStat st = android.system.Os.lstat(file.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(st.st_mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int indexOfBytes(@NonNull byte[] haystack, @NonNull byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Nullable
    private static byte[] readAllBytesQuiet(@NonNull File file) {
        return readAllBytesQuietLimit(file, 8L * 1024 * 1024);
    }

    @Nullable
    private static byte[] readAllBytesQuietLimit(@NonNull File file, long maxBytes) {
        long size = file.length();
        if (size > maxBytes) return null;
        byte[] buffer = new byte[(int) size];
        int offset = 0;
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while (offset < buffer.length && (read = in.read(buffer, offset, buffer.length - offset)) > 0) {
                offset += read;
            }
        } catch (Exception e) {
            return null;
        }
        if (offset == buffer.length) return buffer;
        return Arrays.copyOf(buffer, offset);
    }

    private static boolean writeBytes(@NonNull File file, @NonNull byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + file.getAbsolutePath() + "\"", e);
            return false;
        }
    }

    /**
     * Write the apt configuration that is enforced through the `APT_CONFIG` environment variable, so
     * that even wrappers which do not pass `-o` (like `pkg`) read the sources from the right place.
     */
    public static boolean repairAptConfigFile() {
        File aptDir = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt");
        File confDir = new File(aptDir, "apt.conf.d");
        if (!confDir.isDirectory()) return false;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File protectedDpkg = new File(prefix, "libexec/mio/dpkg");
        String dpkgPath = protectedDpkg.isFile() ? protectedDpkg.getAbsolutePath() : (prefix + "/bin/dpkg");
        String content = "# mio: apt paths (APT_CONFIG)\n" +
            "Dir::Etc \"" + aptDir.getAbsolutePath() + "\";\n" +
            "Dir::Etc::sourcelist \"" + new File(aptDir, "sources.list").getAbsolutePath() + "\";\n" +
            "Dir::Etc::sourceparts \"" + new File(aptDir, "sources.list.d").getAbsolutePath() + "\";\n" +
            "Dir::Etc::trusted \"" + new File(aptDir, "trusted.gpg").getAbsolutePath() + "\";\n" +
            "Dir::Etc::trustedparts \"" + new File(aptDir, "trusted.gpg.d").getAbsolutePath() + "\";\n" +
            "Dir::State \"" + prefix + "/var/lib/apt\";\n" +
            "Dir::State::lists \"" + prefix + "/var/lib/apt/lists\";\n" +
            "Dir::State::status \"" + prefix + "/var/lib/dpkg/status\";\n" +
            "Dir::Cache \"" + prefix + "/var/cache/apt\";\n" +
            "Dir::Cache::archives \"" + prefix + "/var/cache/apt/archives\";\n" +
            "Dir::Log \"" + prefix + "/var/log/apt\";\n" +
            "Dir::Bin::methods \"" + prefix + "/lib/apt/methods\";\n" +
            "Dir::Bin::dpkg \"" + dpkgPath + "\";\n" +
            "Dir::State::extended_states \"" + prefix + "/var/lib/apt/extended_states\";\n" +
            "DPkg::Path \"" + prefix + "/libexec/mio:" + prefix + "/bin:/system/bin:/system/xbin\";\n" +
            "Dpkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n" +
            "DPkg::Post-Invoke { \"" + prefix + "/bin/mio sarmalayici >/dev/null 2>&1 || true\"; };\n" +
            "Acquire::ForceIPv4 \"true\";\n" +
            "Acquire::Languages \"none\";\n";
        File conf = new File(confDir, "mio-apt.conf");
        String current = conf.isFile() ? readText(conf) : null;
        if (content.equals(current)) return false;
        return writeText(conf, content);
    }

    /**
     * Suppress the default Termux welcome/MOTD banner once so opening the terminal lands directly
     * on the prompt without the initial bootstrap/motd wall of text.
     */
    private static void ensureHushLoginOnce() {
        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/dpkg/.mio-hushlogin-done");
        if (marker.isFile()) return;
        try {
            File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            //noinspection ResultOfMethodCallIgnored
            homeDir.mkdirs();
            File hush = new File(homeDir, ".hushlogin");
            if (!hush.exists()) {
                writeBytes(hush, new byte[0]);
            }
            for (String rel : new String[]{"etc/motd", "etc/motd-playstore"}) {
                File motd = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, rel);
                if (motd.isFile() && motd.length() > 0) {
                    writeBytes(motd, new byte[0]);
                }
            }
            if (marker.getParentFile() != null && marker.getParentFile().isDirectory()) {
                writeBytes(marker, new byte[]{'1', '\n'});
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to ensure .hushlogin: " + e.getMessage());
        }
    }

    /**
     * Install `$PREFIX/etc/profile.d/mio-hushlogin.sh` so that writing `echo ABC`, `echo "ABC"`,
     * or plain text (`ABC`) into `~/.hushlogin` prints it on new terminal sessions while keeping
     * an empty `~/.hushlogin` completely silent.
     */
    public static void ensureHushLoginScript() {
        File profileD = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "profile.d");
        if (!profileD.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            profileD.mkdirs();
        }
        if (!profileD.isDirectory()) return;

        File target = new File(profileD, "mio-hushlogin.sh");
        String script = "# mio: display ~/.hushlogin text or echo lines on new session\n"
            + "if [ -z \"${_MIO_HUSHLOGIN_SHOWN:-}\" ] && [ -s \"$HOME/.hushlogin\" ]; then\n"
            + "    _MIO_HUSHLOGIN_SHOWN=1\n"
            + "    export _MIO_HUSHLOGIN_SHOWN\n"
            + "    while IFS= read -r _mio_hl || [ -n \"$_mio_hl\" ]; do\n"
            + "        _mio_hl=$(printf '%s' \"$_mio_hl\" | tr -d '\\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')\n"
            + "        case \"$_mio_hl\" in\n"
            + "            \"\"|\"#\"*) continue ;;\n"
            + "        esac\n"
            + "        case \"$_mio_hl\" in\n"
            + "            \"'echo \"*|\"'echo\t\"*|\"'printf \"*|\"'printf\t\"*)\n"
            + "                _mio_hl=${_mio_hl#\\'}\n"
            + "                _mio_hl=${_mio_hl%\\'}\n"
            + "                ;;\n"
            + "            '\"echo '*|'\"echo\t'*|'\"printf '*|'\"printf\t'*)\n"
            + "                _mio_hl=${_mio_hl#\\\"}\n"
            + "                _mio_hl=${_mio_hl%\\\"}\n"
            + "                ;;\n"
            + "        esac\n"
            + "        case \"$_mio_hl\" in\n"
            + "            \"echo \"*|\"echo\t\"*)\n"
            + "                _mio_arg=${_mio_hl#echo}\n"
            + "                _mio_arg=$(printf '%s' \"$_mio_arg\" | sed 's/^[[:space:]]*//')\n"
            + "                case \"$_mio_arg\" in\n"
            + "                    '\"'*|\"'\"*|-*)\n"
            + "                        if ! sh -c \"$_mio_hl\" 2>/dev/null; then\n"
            + "                            printf '%s\\n' \"$_mio_arg\"\n"
            + "                        fi\n"
            + "                        ;;\n"
            + "                    *)\n"
            + "                        printf '%s\\n' \"$_mio_arg\"\n"
            + "                        ;;\n"
            + "                esac\n"
            + "                ;;\n"
            + "            \"printf \"*|\"printf\t\"*)\n"
            + "                if ! sh -c \"$_mio_hl\" 2>/dev/null; then\n"
            + "                    printf '%s\\n' \"$_mio_hl\"\n"
            + "                fi\n"
            + "                ;;\n"
            + "            *)\n"
            + "                printf '%s\\n' \"$_mio_hl\"\n"
            + "                ;;\n"
            + "        esac\n"
            + "    done < \"$HOME/.hushlogin\"\n"
            + "    unset _mio_hl _mio_arg\n"
            + "fi\n";
        String current = target.isFile() ? readText(target) : null;
        if (!script.equals(current)) {
            writeText(target, script);
        }
    }

    /**
     * Silence the fallback second-stage banner inside `$PREFIX/bin/login` if present.
     */
    public static void silenceLoginSecondStageFallback() {
        File login = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "login");
        if (!login.isFile() || login.length() > 512 * 1024) return;
        byte[] raw = readAllBytesQuiet(login);
        if (raw == null || raw.length < 4) return;
        // Skip if ELF binary
        if (raw[0] == 0x7f && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F') return;
        String content;
        try {
            content = new String(raw, "UTF-8");
        } catch (Exception e) {
            return;
        }
        if (content.contains("# mio-silent-second-stage")) return;
        if (!content.contains("Starting fallback run of termux bootstrap second stage") &&
            !content.contains("termux-bootstrap-second-stage")) {
            return;
        }

        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length() + 128);
        boolean modified = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.contains("Starting fallback run of termux bootstrap second stage")) {
                line = ": # mio-silent-second-stage";
                modified = true;
            } else if (trimmed.contains("termux-bootstrap-second-stage.sh") &&
                (trimmed.startsWith("\"") || trimmed.startsWith("$") || trimmed.startsWith("/")) &&
                !trimmed.contains(">/dev/null") && !trimmed.contains("bootstrap-second-stage.log")) {
                line = line + " >/dev/null 2>&1";
                modified = true;
            } else if (trimmed.contains("TERMUX_BOOTSTRAP__SECOND_STAGE_FILE") &&
                (trimmed.startsWith("\"$") || trimmed.startsWith("'$") || trimmed.startsWith("$")) &&
                !trimmed.contains("=") && !trimmed.contains(">/dev/null")) {
                line = line + " >/dev/null 2>&1";
                modified = true;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        if (modified) {
            boolean exec = login.canExecute();
            if (writeText(login, out.toString()) && exec) {
                //noinspection ResultOfMethodCallIgnored
                login.setExecutable(true, false);
            }
        }
    }

    /**
     * Make the apt download methods (`lib/apt/methods/http`, `https`, ...) executable.
     *
     * The bootstrap archives store them as `0700` files, but a package upgrade (or a prefix that was
     * extracted with an older patch) can leave them without the executable bit. apt then fails with
     * "Failed to exec method .../lib/apt/methods/https" and no repository can be read, which shows up
     * as the misleading "The list of sources could not be read" error.
     */
    public static boolean repairAptMethods() {
        final File methodsDir = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "apt/methods");
        if (!methodsDir.isDirectory()) return false;

        boolean repaired = false;
        File[] methods = methodsDir.listFiles();
        if (methods == null) return false;

        for (File method : methods) {
            if (!method.isFile()) continue;
            if (method.canExecute()) continue;
            if (method.setExecutable(true, false)) {
                repaired = true;
                Logger.logInfo(LOG_TAG, "Made the apt method \"" + method.getAbsolutePath() + "\" executable");
            }
        }
        return repaired;
    }

    /**
     * Returns the target of a symlink, or {@code null} if the path is not a symlink or cannot be
     * read.
     */
    @Nullable
    private static String readSymlink(@NonNull File file) {
        try {
            return android.system.Os.readlink(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readFirstDebUrl(@NonNull File sourcesList) {
        if (!sourcesList.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sourcesList)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("deb ")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && !parts[1].isEmpty()) return parts[1];
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + sourcesList.getAbsolutePath() + "\"", e);
        }
        return null;
    }

    private static boolean writeText(@NonNull File file, @NonNull String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + file.getAbsolutePath() + "\"", e);
            return false;
        }
    }

    private static boolean deleteRecursively(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete();
    }

    @Nullable
    private static String readText(@NonNull File file) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
        } catch (Exception e) {
            return null;
        }
        return content.toString();
    }

    /**
     * The scripts in the assets are written for the standard Termux prefix
     * (`/data/data/com.termux/files/usr`). This fork installs the prefix inside its own data
     * directory, so the path is rewritten while the script is installed. The replacement has the
     * same length in the standard case, so the file is only rewritten when it is really different.
     */
    private static void rewritePrefixPaths(@NonNull File file) {
        final String legacy = TermuxConstants.TERMUX_LEGACY_DATA_DIR_PATH;
        final String current = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        if (legacy.equals(current)) return;

        try {
            String content = readText(file);
            if (content == null) return;
            // Bileşen adları: `com.termux/com.termux.app.TermuxService` gibi hedefler bu uygulamada
            // yoktur ve sessizce kaybolur. Veri yolu ve paket adı birlikte çevrilir, çünkü
            // `/data/data/com.termux` içindeki `com.termux` paket adıdır, sınıf yolu
            // `com.termux.app...` ise kaynak koddaki adıyla kalır.
            String patched = content
                .replace("/data/data/" + legacyPackage(), "/data/data/" + currentPackage())
                .replace(legacy, current)
                .replace(legacyPackage() + "/" + legacyPackage() + ".app.", currentPackage() + "/com.termux.app.");
            if (patched.equals(content)) return;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(patched.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to rewrite the prefix path of \"" + file.getAbsolutePath() + "\"", e);
        }
    }


    /**
     * @return Returns the package name this fork was installed with, like {@code com.mio}, read from
     * the data directory constant of {@link TermuxConstants}.
     */
    @NonNull
    private static String currentPackage() {
        String path = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    /**
     * @return Returns the package name of the upstream Termux app, which the command assets and the
     * packages of the repositories use in the component names and the data directory.
     */
    @NonNull
    private static String legacyPackage() {
        String path = TermuxConstants.TERMUX_LEGACY_DATA_DIR_PATH;
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    @Nullable
    private static String readVersionFile() {
        File versionFile = new File(VERSION_FILE_PATH);
        if (!versionFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(versionFile), "UTF-8"))) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                // Older releases wrote only the full version, newer ones write the app version first
                if (lineNumber == 2 || line.startsWith("v") == false) return line.trim();
            }
            return null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read version file \"" + VERSION_FILE_PATH + "\"", e);
            return null;
        }
    }

    private static void writeVersionFile(@NonNull String version) {
        File versionFile = new File(VERSION_FILE_PATH);
        // The first line is the app version, the second the full (length based) version, so that
        // `mio teshis` can report which app build installed the commands
        String content = "v" + BuildConfig.VERSION_NAME + "\n" + version + "\n";
        try (FileOutputStream out = new FileOutputStream(versionFile)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write version file \"" + VERSION_FILE_PATH + "\"", e);
        }
    }

    /**
     * The version is the app version combined with the total length of the command scripts, so that
     * the commands are also reinstalled when a script changes without a version bump, like during
     * development, and so that a broken or truncated installed script is always replaced.
     */
    @NonNull
    private static String getCurrentVersion(@Nullable Context context) {
        StringBuilder version = new StringBuilder(BuildConfig.VERSION_NAME)
            .append('-').append(BuildConfig.VERSION_CODE);

        if (context != null) {
            for (String command : COMMANDS) {
                try (InputStream asset = context.getAssets().open(ASSET_DIR + "/" + command)) {
                    version.append('-').append(asset.available());
                } catch (IOException e) {
                    version.append("-0");
                }
            }
        }

        return version.toString();
    }
}
