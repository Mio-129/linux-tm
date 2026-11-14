package com.termux.app.mio;

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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a ZeroTermux style module: a ZIP file with an {@code INSTALL.txt} file and the files that
 * are installed.
 *
 * Every non comment line of {@code INSTALL.txt} is
 * {@code kaynak -> hedef -> izin}, like ZeroTermux does it:
 *
 * <pre>
 * bin/foo        -> /data/data/com.mio/files/usr/bin/foo    -> 700
 * bin/bash.bashrc -> bash.bashrc                               -> 700
 * share/         -> /data/data/com.mio/files/usr/share/mio  -> 700
 * </pre>
 *
 * The source is a file or a directory inside the ZIP, the target is an absolute path or, for the
 * {@code bash.bashrc} case, a marker that the file is used as the shell start-up file of the module.
 * Lines that start with {@code #} are comments and are shown in the log, and a line whose source
 * does not exist fails the installation, so a broken module never leaves a half installed state
 * silently.
 *
 * The ZIP is extracted to a temporary folder, the files are copied, the permissions are set and the
 * temporary folder is removed again, like {@code ModuleInstallUtils.kt} of ZeroTermux.
 */
public final class MioModuleInstaller {

    public static final String LOG_TAG = "MioModuleInstaller";

    /** The folder the modules are extracted to before they are installed. */
    public static final String TEMP_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.mio/tempmodule";

    /** The folder that the modules are copied from, shown by the module dialog. */
    public static final String MODULE_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.mio/modules";

    /** The file of the module that lists what is installed. */
    public static final String INSTALL_FILE_NAME = "INSTALL.txt";

    private MioModuleInstaller() {
    }

    /** What the installation did, shown in the panel. */
    public static final class Result {

        @NonNull
        public final String message;

        public final boolean success;

        Result(@NonNull String message, boolean success) {
            this.message = message;
            this.success = success;
        }
    }

    /**
     * Install the passed module ZIP.
     *
     * @param zipFile The module, a ZIP file with an `INSTALL.txt` file.
     * @return Returns the result with the log of the installation.
     */
    @NonNull
    public static Result install(@NonNull File zipFile) {
        if (!zipFile.isFile()) {
            return new Result("Modül bulunamadı: " + zipFile.getAbsolutePath(), false);
        }

        File tempDir = new File(TEMP_DIR_PATH);
        if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
            return new Result("Geçici klasör oluşturulamadı: " + TEMP_DIR_PATH, false);
        }

        StringBuilder log = new StringBuilder("Modül: " + zipFile.getName() + "\n");
        try {
            log.append("Ayıklanıyor...\n");
            extract(zipFile, tempDir);

            File installFile = new File(tempDir, INSTALL_FILE_NAME);
            if (!installFile.isFile()) {
                deleteRecursively(tempDir);
                return new Result(log + "HATA: " + INSTALL_FILE_NAME + " bulunamadı.", false);
            }

            List<String> lines = readLines(installFile);
            int installed = 0;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                if (line.trim().startsWith("#")) {
                    log.append(line.replace("# ", "")).append('\n');
                    continue;
                }

                String[] parts = line.split("->");
                if (parts.length != 3) {
                    deleteRecursively(tempDir);
                    return new Result(log + "HATA: satır 3 parça olmalı (kaynak -> hedef -> izin):\n"
                        + line, false);
                }

                String source = parts[0].trim();
                String target = parts[1].trim();
                String permission = parts[2].trim();

                File sourceFile = new File(tempDir, source);
                if (!sourceFile.exists()) {
                    deleteRecursively(tempDir);
                    return new Result(log + "HATA: kaynak bulunamadı: " + source, false);
                }

                // bash.bashrc is used as the start-up file, the path is resolved by the caller
                if (source.contains("bash.bashrc")) {
                    log.append("bash.bashrc -> ").append(target).append('\n');
                    installed++;
                    continue;
                }

                File targetFile = new File(target);
                if (!copy(sourceFile, targetFile, log)) {
                    deleteRecursively(tempDir);
                    return new Result(log + "HATA: kopyalanamadı: " + target, false);
                }

                if (!permission.isEmpty()) {
                    try {
                        int mode = Integer.parseInt(permission);
                        //noinspection ResultOfMethodCallIgnored
                        targetFile.setExecutable((mode & 0100) != 0, false);
                        //noinspection ResultOfMethodCallIgnored
                        targetFile.setReadable(true, false);
                        //noinspection ResultOfMethodCallIgnored
                        targetFile.setWritable((mode & 0200) != 0, true);
                    } catch (NumberFormatException e) {
                        // The mode is optional information, a missing one is not an error
                        Logger.logWarn(LOG_TAG, "Invalid mode \"" + permission + "\" for \"" + target + "\"");
                    }
                }
                installed++;
            }

            deleteRecursively(tempDir);
            log.append("\nKurulan dosya: ").append(installed);
            return new Result(log.toString(), true);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install module \"" + zipFile.getName() + "\"", e);
            deleteRecursively(tempDir);
            return new Result(log + "HATA: " + e, false);
        }
    }

    /** The module ZIPs in {@link #MODULE_DIR_PATH}, sorted by name. */
    @NonNull
    public static List<File> getModules() {
        List<File> modules = new ArrayList<>();
        File[] files = new File(MODULE_DIR_PATH).listFiles((dir, name) ->
            name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));
        if (files == null) return modules;
        for (File file : files) modules.add(file);
        modules.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
        return modules;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* ZIP                                                                                       */
    /* ---------------------------------------------------------------------------------------- */

    private static void extract(@NonNull File zipFile, @NonNull File targetDir) throws Exception {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[65536];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File target = new File(targetDir, entry.getName());

                // Never write outside of the temporary folder, a module is not trusted
                if (!target.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    throw new Exception("Modül klasör dışına yazıyor: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new Exception("Klasör oluşturulamadı: " + target.getAbsolutePath());
                    }
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new Exception("Klasör oluşturulamadı: " + parent.getAbsolutePath());
                }

                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
            }
        }
    }

    private static boolean copy(@NonNull File source, @NonNull File target, @NonNull StringBuilder log) {
        try {
            if (source.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs()) return false;
                File[] children = source.listFiles();
                if (children == null) return true;
                for (File child : children) {
                    if (!copy(child, new File(target, child.getName()), log)) return false;
                }
                log.append(target.getAbsolutePath()).append(" (klasör)\n");
                return true;
            }

            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;

            try (InputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            log.append(target.getAbsolutePath()).append('\n');
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to copy \"" + source.getAbsolutePath() + "\"", e);
            log.append("HATA: ").append(e).append('\n');
            return false;
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @NonNull
    private static List<String> readLines(@NonNull File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }
}
