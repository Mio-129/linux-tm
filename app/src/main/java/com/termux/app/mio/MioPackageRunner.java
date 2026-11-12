package com.termux.app.mio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the scripts of the packages of {@link MioPackages}.
 *
 * A package is installed step by step, like the tiers of the package manager of ZeroTermux:
 *
 * <ol>
 *     <li>the {@code *.a.sh} script ("Older-Pack") is run,</li>
 *     <li>if that succeeded, the {@code *.b.sh} script ("Older-Tool") is run,</li>
 *     <li>the further scripts ({@code .c.sh}, {@code .d.sh}, ...) are run in order,</li>
 *     <li>the package is marked as installed with its {@code VERSION},</li>
 *     <li>the {@code *.start-up.sh} script of the package is run from then on at every app start.</li>
 * </ol>
 *
 * The scripts are run with the app shell ({@code sh}) in the folder of the package, and their
 * output is written to {@code ~/.mio/log/<paket>.log}, so that the panel can show what happened
 * without a terminal being open. There is only one run at a time, a run that is already going on is
 * not started a second time.
 */
public final class MioPackageRunner {

    public static final String LOG_TAG = "MioPackageRunner";

    /** The folder the scripts are run in, since they may use relative paths. */
    private static final String HOME_DIR_PATH = "/data/data/com.termux/files/home";

    /** The shell that runs the scripts. */
    private static final String SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh";

    /** If a package or a start-up script is being run, so that a run is not started twice. */
    private static boolean sRunning = false;

    private MioPackageRunner() {
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Install and update                                                                        */
    /* ---------------------------------------------------------------------------------------- */

    /** What a run of a package did, shown in the panel. */
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
     * Install or update a package: run its scripts and mark it as installed if they succeeded.
     *
     * @param pkg The package to install.
     * @return Returns the result of the run, also when the package could not be started.
     */
    @NonNull
    public static Result install(@NonNull MioPackages.Package pkg) {
        if (!MioPackages.ensureFolders()) {
            return new Result("Paket klasörü oluşturulamadı: " + MioPackages.PACKAGE_DIR_PATH, false);
        }

        if (pkg.scripts.isEmpty()) {
            return new Result("Paket boş: " + pkg.name + " (içinde .sh dosyası yok)", false);
        }

        List<String> commands = new ArrayList<>();
        for (MioPackages.Script script : pkg.scripts) {
            if (script.isStartUp) continue;
            // The output of a failing script must stop the run, so that the `.b.sh` script of a
            // broken `.a.sh` script is not run
            commands.add("sh " + MioPackages.quote(script.file.getAbsolutePath()) + " || exit $?");
        }

        if (commands.isEmpty()) {
            return new Result("Paket boş: " + pkg.name + " (.a.sh / .b.sh yok)", false);
        }

        ShellResult result = run(pkg, commands);
        if (!result.success) {
            return new Result(pkg.name + " kurulamadı (çıkış kodu " + result.exitCode + "). "
                + "Günlük: " + pkg.getLogFile().getAbsolutePath(), false);
        }

        pkg.setInstalledVersion(pkg.getVersion() == null ? "1" : pkg.getVersion());
        StringBuilder message = new StringBuilder(pkg.name).append(" kuruldu");
        String version = pkg.getVersion();
        if (version != null) message.append(" (v").append(version).append(")");
        if (pkg.getStartUpScript() != null) message.append(", start-up etkin");
        return new Result(message.toString(), true);
    }

    /** Install or update every package that has a new version. */
    @NonNull
    public static Result installAll() {
        List<MioPackages.Package> packages = MioPackages.getUpdatablePackages();
        if (packages.isEmpty()) {
            return new Result("Güncellenecek paket yok.", true);
        }

        StringBuilder done = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (MioPackages.Package pkg : packages) {
            Result result = install(pkg);
            StringBuilder target = result.success ? done : failed;
            if (target.length() > 0) target.append(", ");
            target.append(pkg.name);
        }

        boolean success = failed.length() == 0;
        String message = (done.length() > 0 ? "Kuruldu: " + done : "Kurulan olmadı")
            + (!success ? " • Hata: " + failed : "");
        return new Result(message, success);
    }

    /** Run the start-up script of a package, like after the app was started. */
    @NonNull
    public static Result runStartUp(@NonNull MioPackages.Package pkg) {
        MioPackages.Script script = pkg.getStartUpScript();
        if (script == null) {
            return new Result("start-up betiği yok: " + pkg.name, false);
        }

        List<String> commands = new ArrayList<>();
        commands.add("sh " + MioPackages.quote(script.file.getAbsolutePath()));
        ShellResult result = run(pkg, commands);
        return new Result(pkg.name + " start-up " + (result.success ? "tamam" : "hata (" + result.exitCode + ")"),
            result.success);
    }

    /**
     * Run the start-up scripts of all enabled packages that were installed, once per app start.
     *
     * The start-up scripts are not run when the app is only opened by a broadcast, since the shell
     * of the app may not be ready yet; failures are only written to the logs.
     */
    public static void runEnabledStartUpScripts() {
        if (sRunning) return;
        List<MioPackages.Package> packages = MioPackages.getPackages();
        for (MioPackages.Package pkg : packages) {
            if (!pkg.isInstalled() || !pkg.isEnabled()) continue;
            if (pkg.getStartUpScript() == null) continue;
            if (pkg.hasUpdate()) {
                // Do not run the start-up script of a package that was not updated yet, its files
                // may not be installed yet
                Logger.logDebug(LOG_TAG, "Skipping start-up of \"" + pkg.name + "\" since it has an update");
                continue;
            }
            Result result = runStartUp(pkg);
            Logger.logInfo(LOG_TAG, "start-up: " + result.message);
        }
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Shell                                                                                     */
    /* ---------------------------------------------------------------------------------------- */

    private static final class ShellResult {
        final int exitCode;
        final boolean success;

        ShellResult(int exitCode) {
            this.exitCode = exitCode;
            this.success = exitCode == 0;
        }
    }

    /**
     * Run the commands with `sh -c` in the folder of the package and append the output to its log.
     *
     * The commands are joined with `;` in one shell, so that a failing script stops the run.
     */
    @NonNull
    private static ShellResult run(@NonNull MioPackages.Package pkg, @NonNull List<String> commands) {
        if (sRunning) {
            return new ShellResult(-1);
        }

        sRunning = true;
        try {
            StringBuilder script = new StringBuilder();
            for (String command : commands) {
                if (script.length() > 0) script.append('\n');
                script.append(command);
            }

            List<String> commandLine = new ArrayList<>();
            commandLine.add(SHELL_PATH);
            commandLine.add("-c");
            commandLine.add("cd " + MioPackages.quote(pkg.directory.getAbsolutePath())
                + " || exit 1\n" + script);

            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.directory(new File(HOME_DIR_PATH));
            builder.redirectErrorStream(true);

            File logFile = pkg.getLogFile();
            File logDirectory = logFile.getParentFile();
            if (logDirectory != null && !logDirectory.isDirectory()) logDirectory.mkdirs();

            StringBuilder output = new StringBuilder();
            try {
                // The environment of the app is not the environment of the terminal, so the paths
                // of the bootstrap are added, since the scripts use `pkg`, `apt`, `$PREFIX`, ...
                builder.environment().put("PREFIX", "/data/data/com.termux/files/usr");
                builder.environment().put("HOME", HOME_DIR_PATH);
                builder.environment().put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
                builder.environment().put("PATH", "/data/data/com.termux/files/usr/bin:"
                    + "/data/data/com.termux/files/usr/bin/applets:/system/bin:/system/xbin");
                builder.environment().put("LD_LIBRARY_PATH", "/data/data/com.termux/files/usr/lib");
                builder.environment().put("LANG", "en_US.UTF-8");
                builder.environment().put("TERM", "xterm-256color");

                Process process = builder.start();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }

                int exitCode = process.waitFor();
                writeLog(pkg, output.toString(), exitCode);
                return new ShellResult(exitCode);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run the scripts of \"" + pkg.name + "\"", e);
                writeLog(pkg, output + "\nHata: " + e, -1);
                return new ShellResult(-1);
            }
        } finally {
            sRunning = false;
        }
    }

    private static void writeLog(@NonNull MioPackages.Package pkg, @NonNull String output, int exitCode) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(pkg.getLogFile())) {
            out.write(("# " + pkg.name + " — " + new java.util.Date() + " — çıkış kodu " + exitCode + "\n")
                .getBytes("UTF-8"));
            out.write(output.getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write the log of \"" + pkg.name + "\"", e);
        }
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Creation of the template                                                                  */
    /* ---------------------------------------------------------------------------------------- */

    /** The name of the template package that the menu creates for the user. */
    public static final String TEMPLATE_NAME = "Older-Pack";

    /** The remote install script of the Older-Pack template package. */
    public static final String TEMPLATE_URL =
        "https://raw.githubusercontent.com/Mio-129/tm-pkg/refs/heads/main/older-pack";

    /**
     * Create the example package of the menu: the `Older-Pack` folder with the `Older-Pack.a.sh`,
     * `Older-Tool.b.sh` and `Older-Pack.start-up.sh` scripts described by the package section of the
     * menu. Existing files are kept, so that the work of the user is not overwritten.
     *
     * @return Returns the created package, {@code null} if it could not be created.
     */
    @Nullable
    public static MioPackages.Package createTemplatePackage() {
        if (!MioPackages.ensureFolders()) return null;

        File directory = new File(MioPackages.getPackageDir(), TEMPLATE_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;

        writeTemplateFile(new File(directory, TEMPLATE_NAME + MioPackages.SCRIPT_A_SUFFIX), TEMPLATE_A_SCRIPT);
        writeTemplateFile(new File(directory, "Older-Tool" + MioPackages.SCRIPT_B_SUFFIX), TEMPLATE_B_SCRIPT);
        writeTemplateFile(new File(directory, TEMPLATE_NAME + MioPackages.STARTUP_SUFFIX), TEMPLATE_STARTUP_SCRIPT);
        writeTemplateFile(new File(directory, "URL"), TEMPLATE_URL + "\n");
        writeTemplateFile(new File(directory, "VERSION"), "1\n");

        for (MioPackages.Package pkg : MioPackages.getPackages()) {
            if (pkg.name.equals(TEMPLATE_NAME)) return pkg;
        }
        return null;
    }

    private static void writeTemplateFile(@NonNull File file, @NonNull String content) {
        if (file.isFile()) {
            Logger.logInfo(LOG_TAG, "Keeping the existing file \"" + file.getAbsolutePath() + "\"");
            return;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + file.getAbsolutePath() + "\"", e);
        }
    }

    /**
     * The `Older-Pack.a.sh` template.
     *
     * A package is a folder below `~/.mio/pkgs/<paket>` with the scripts `.a.sh`, `.b.sh` and
     * `.start-up.sh` and a `VERSION` file. The `.a.sh` script is run first by "Paketler > Kur".
     * The commands of the template only print what a real package would do, so that it can be run
     * without installing anything, replace them with the commands of the package.
     */
    private static final String TEMPLATE_A_SCRIPT = "#!/data/data/com.termux/files/usr/bin/sh\n"
        + "#\n"
        + "# Older-Pack — birinci aşama betiği (.a.sh)\n"
        + "#\n"
        + "# Bu betik \"Paketler > Older-Pack > Kur\" ile ilk çalışan betiktir. Kurulacak paketin\n"
        + "# komutlarını buraya yaz, örneğin:\n"
        + "#   pkg install -y git\n"
        + "#   pip install requests\n"
        + "#\n"
        + "# Çıkış kodu 0 değilse .b.sh betiği çalışmaz, yani birinci aşama başarısız olursa ikinci\n"
        + "# aşama denenmez.\n"
        + "\n"
        + "set -u\n"
        + "\n"
        + "echo \"Older-Pack: birinci aşama başladı\"\n"
        + "echo \"Ev dizini: $HOME\"\n"
        + "echo \"Paketler: $PREFIX/bin/pkg\"\n"
        + "\n"
        + "# Kurulacak komutların örneği:\n"
        + "# pkg update -y && pkg install -y git\n"
        + "\n"
        + "echo \"Older-Pack: birinci aşama tamam\"\n";

    /**
     * The `Older-Tool.b.sh` template, run only after the `.a.sh` script of the package succeeded.
     */
    private static final String TEMPLATE_B_SCRIPT = "#!/data/data/com.termux/files/usr/bin/sh\n"
        + "#\n"
        + "# Older-Tool — ikinci aşama betiği (.b.sh)\n"
        + "#\n"
        + "# Bu betik (.a.sh) başarıyla bittikten sonra çalışır, örneğin paket kurulduktan sonra\n"
        + "# yapılandırma, kısayol veya ayar yapmak için:\n"
        + "#   git config --global init.defaultBranch main\n"
        + "#\n"
        + "# .a.sh betiği hata verirse bu betik çalışmaz.\n"
        + "\n"
        + "set -u\n"
        + "\n"
        + "echo \"Older-Tool: ikinci aşama başladı\"\n"
        + "echo \"Older-Tool: ikinci aşama tamam\"\n";

    /**
     * The `Older-Pack.start-up.sh` template, run on every start of the app and after an update.
     */
    private static final String TEMPLATE_STARTUP_SCRIPT = "#!/data/data/com.termux/files/usr/bin/sh\n"
        + "#\n"
        + "# Older-Pack — başlangıç betiği (.start-up.sh)\n"
        + "#\n"
        + "# Bu betik paket kurulduktan sonra Termux her açıldığında ve paket her güncellendiğinde\n"
        + "# çalışır, örneğin servisleri başlatmak veya yolları ayarlamak için:\n"
        + "#   pgrep sshd >/dev/null || sshd\n"
        + "#\n"
        + "# \"Paketler > Older-Pack > Başlangıç\" ile elle de çalıştırılabilir.\n"
        + "\n"
        + "set -u\n"
        + "\n"
        + "echo \"Older-Pack: start-up çalıştı ($(date 2>/dev/null))\"\n";
}
