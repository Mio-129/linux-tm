package com.termux.app.mio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small code editor of the "Mio menü", so that the scripts of the packages (`*.a.sh`, `*.b.sh`,
 * `*.start-up.sh`) and other text files can be edited on the phone without `nano` and without the
 * soft keyboard hiding the file.
 *
 * The screen is a column: a title with the file name and the changed state, a row of the actions
 * (Kaydet / Çalıştır / Kapat), the editor itself and the row of the code keys (Tab, braces,
 * brackets, quotes, ...), which are hard to reach on a phone keyboard.
 *
 * The activity only edits text: the file is read when it is opened and written when it is saved.
 */
public final class MioEditorActivity extends Activity {

    public static final String LOG_TAG = "MioEditorActivity";

    /** The absolute path of the file that is edited. */
    public static final String EXTRA_PATH = "com.termux.mio.extra.EDITOR_PATH";

    /** Files bigger than this are not opened, since the editor is meant for scripts. */
    private static final int MAX_FILE_SIZE = 512 * 1024;

    /** The keys of the code key row, inserted at the cursor. */
    private static final String[] CODE_KEYS = new String[]{
        "Tab", "{", "}", "(", ")", "[", "]", "<", ">", "=", "\"", "'", "/", ":", ";", "|", "$", "#", "_", "&&"
    };

    private EditText mEditor;
    private TextView mLineNumbers;
    private TextView mTitle;
    private Button mRunButton;
    private File mFile;
    private String mOpenedContent = "";
    private boolean mShowLineNumbers = true;

    /** The preference the line number gutter is remembered in. */
    private static final String PREFS_NAME = "mio_editor";
    private static final String PREF_LINE_NUMBERS = "line_numbers";

    /* ---------------------------------------------------------------------------------------- */
    /* Start                                                                                     */
    /* ---------------------------------------------------------------------------------------- */

    /** Open the editor for the passed file, the file does not have to exist yet. */
    public static void start(@NonNull Activity activity, @NonNull String path) {
        Intent intent = new Intent(activity, MioEditorActivity.class);
        intent.putExtra(EXTRA_PATH, path);
        activity.startActivity(intent);
    }

    /** Open the editor from any context, used by the panel without the file picker. */
    public static void startFromContext(@NonNull Context context, @NonNull String path) {
        Intent intent = new Intent(context, MioEditorActivity.class);
        intent.putExtra(EXTRA_PATH, path);
        if (!(context instanceof Activity)) intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** @return Returns {@code true} if the path looks like a text file that the editor can open. */
    public static boolean isEditable(@NonNull String path) {
        String lowerCase = path.toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".sh") || lowerCase.endsWith(".txt") || lowerCase.endsWith(".md")
            || lowerCase.endsWith(".properties") || lowerCase.endsWith(".conf")
            || lowerCase.endsWith(".json") || lowerCase.endsWith(".yml") || lowerCase.endsWith(".yaml")
            || lowerCase.endsWith(".java") || lowerCase.endsWith(".py") || lowerCase.endsWith(".js")
            || lowerCase.endsWith(".c") || lowerCase.endsWith(".h") || lowerCase.endsWith(".log")
            || !lowerCase.contains(".");
    }

    /** The package scripts and the scripts in the home folder, used by the file chooser. */
    @NonNull
    public static List<File> getScriptFiles() {
        List<File> files = new ArrayList<>();
        for (MioPackages.Package pkg : MioPackages.getPackages()) {
            for (MioPackages.Script script : pkg.scripts) {
                files.add(script.file);
            }
        }

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File[] homeFiles = home.listFiles((dir, name) -> name.endsWith(".sh"));
        if (homeFiles != null) {
            for (File file : homeFiles) files.add(file);
        }
        return files;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Lifecycle                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "Dosya yolu verilmedi.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mFile = new File(path.trim());
        setContentView(buildLayout());
        loadFile();
    }

    @Override
    public void onBackPressed() {
        if (isChanged()) {
            new AlertDialog.Builder(this)
                .setTitle("Kaydedilmedi")
                .setMessage("Değişiklikler kaydedilsin mi?")
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    if (save()) finish();
                })
                .setNegativeButton("Kaydetme", (dialog, which) -> finish())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
            return;
        }
        super.onBackPressed();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Layout                                                                                    */
    /* ---------------------------------------------------------------------------------------- */

    @NonNull
    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);

        // ------------------------------------------------------------------------ title -----
        mTitle = new TextView(this);
        mTitle.setTextSize(15);
        mTitle.setTextColor(0xFFFFFFFF);
        mTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTitle.setPadding(dp(12), dp(10), dp(12), dp(6));
        mTitle.setText(mFile.getName() + (mFile.exists() ? "" : " (yeni)"));
        root.addView(mTitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ----------------------------------------------------------------------- actions -----
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(8), 0, dp(8), dp(4));
        addAction(actionRow, "Kaydet", v -> save());
        mRunButton = addAction(actionRow, getRunLabel(), v -> saveAndRun());
        addAction(actionRow, "Kapat", v -> onBackPressed());
        root.addView(actionRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ------------------------------------------------------------------------ editor -----
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFF0B0F13);

        // The editor and the line numbers are in the same scroll view, so that they always scroll
        // together; soft wrapping is disabled, so that a line of the file is exactly one line of
        // the gutter and the numbers stay next to their line.
        LinearLayout editorRow = new LinearLayout(this);
        editorRow.setOrientation(LinearLayout.HORIZONTAL);
        editorRow.setBackgroundColor(0xFF0B0F13);

        mEditor = new EditText(this);
        mEditor.setTypeface(Typeface.MONOSPACE);
        mEditor.setTextSize(13);
        mEditor.setTextColor(0xFFDCE6EF);
        mEditor.setBackgroundColor(0xFF0B0F13);
        mEditor.setGravity(Gravity.TOP | Gravity.START);
        mEditor.setPadding(dp(10), dp(8), dp(10), dp(8));
        mEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mEditor.setHorizontallyScrolling(true);
        mEditor.setSingleLine(false);
        mEditor.setTextIsSelectable(true);
        mEditor.setIncludeFontPadding(false);

        mLineNumbers = new TextView(this);
        mLineNumbers.setTypeface(Typeface.MONOSPACE);
        mLineNumbers.setTextSize(13);
        mLineNumbers.setTextColor(0xFF5A6B7A);
        mLineNumbers.setBackgroundColor(0xFF11181F);
        mLineNumbers.setGravity(Gravity.TOP | Gravity.END);
        mLineNumbers.setPadding(dp(6), dp(8), dp(10), dp(8));
        mLineNumbers.setIncludeFontPadding(false);
        mLineNumbers.setText("1");
        // The gutter has to use the same line height as the editor, otherwise the numbers drift
        // away from their lines after a few dozen lines
        mLineNumbers.post(() -> alignGutterLineHeight());

        editorRow.addView(mLineNumbers, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        editorRow.addView(mEditor, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scrollView.addView(editorRow, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mShowLineNumbers = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_LINE_NUMBERS, true);
        mLineNumbers.setVisibility(mShowLineNumbers ? View.VISIBLE : View.GONE);

        mEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                updateLineNumbers();
            }
        });

        root.addView(scrollView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --------------------------------------------------------------------- code keys -----
        root.addView(buildCodeKeyRow(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** Make one line of the gutter exactly as tall as one line of the editor. */
    private void alignGutterLineHeight() {
        if (mEditor == null || mLineNumbers == null) return;
        int editorLineHeight = mEditor.getLineHeight();
        int gutterLineHeight = mLineNumbers.getLineHeight();
        if (editorLineHeight <= 0 || gutterLineHeight <= 0) return;
        float extra = editorLineHeight - gutterLineHeight;
        mLineNumbers.setLineSpacing(extra > 0 ? extra : 0f, 1f);
    }

    /** Rewrite the numbers at the left of the editor, one number per line of the text. */
    private void updateLineNumbers() {
        if (mEditor == null || mLineNumbers == null || !mShowLineNumbers) return;

        String text = mEditor.getText().toString();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }

        StringBuilder numbers = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) {
            numbers.append(i);
            if (i < lines) numbers.append('\n');
        }
        if (!numbers.toString().equals(mLineNumbers.getText().toString())) {
            mLineNumbers.setText(numbers);
        }
    }

    /** Show or hide the line numbers and remember the choice. */
    private void toggleLineNumbers() {
        mShowLineNumbers = !mShowLineNumbers;
        if (mLineNumbers != null) {
            mLineNumbers.setVisibility(mShowLineNumbers ? View.VISIBLE : View.GONE);
            if (mShowLineNumbers) updateLineNumbers();
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_LINE_NUMBERS, mShowLineNumbers).apply();
        Toast.makeText(this, mShowLineNumbers ? "Satır numaraları açık" : "Satır numaraları kapalı",
            Toast.LENGTH_SHORT).show();
    }

    /** The row of the code keys that are hard to type on a phone keyboard. */
    @NonNull
    private View buildCodeKeyRow() {
        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);
        horizontalScrollView.setBackgroundColor(0xFF161C22);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(2), dp(4), dp(2));

        // The first key of the row toggles the line numbers
        Button lineNumbers = new Button(this);
        lineNumbers.setText("1·2·3");
        lineNumbers.setTextSize(13);
        lineNumbers.setAllCaps(false);
        lineNumbers.setMinWidth(0);
        lineNumbers.setMinimumWidth(0);
        lineNumbers.setPadding(dp(8), 0, dp(8), 0);
        lineNumbers.setOnClickListener(v -> toggleLineNumbers());
        LinearLayout.LayoutParams gutterParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gutterParams.setMargins(dp(2), 0, dp(2), 0);
        row.addView(lineNumbers, gutterParams);

        for (String key : CODE_KEYS) {
            Button button = new Button(this);
            button.setText(key);
            button.setTextSize(13);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(10), 0, dp(10), 0);
            button.setOnClickListener(v -> insertKey(key));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(button, params);
        }

        horizontalScrollView.addView(row, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return horizontalScrollView;
    }

    @NonNull
    private Button addAction(@NonNull LinearLayout row, @NonNull String text,
                             @NonNull View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, params);
        return button;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Editing                                                                                   */
    /* ---------------------------------------------------------------------------------------- */

    /** Insert the code key at the cursor, `Tab` inserts four spaces like the shell scripts use. */
    private void insertKey(@NonNull String key) {
        if (mEditor == null) return;

        String insert = key.equals("Tab") ? "    " : key;
        int start = Math.max(0, mEditor.getSelectionStart());
        int end = Math.max(start, mEditor.getSelectionEnd());
        mEditor.getText().replace(start, end, insert);
        if (start + insert.length() <= mEditor.getText().length()) {
            mEditor.setSelection(start + insert.length());
        }
        mEditor.requestFocus();
        updateTitle();
        updateRunLabel();
    }

    /**
     * The label of the run button, which tells what the button does: a shell script is run, a file
     * with `alias` lines (or a shell configuration file) is **sourced**, a source file is run with
     * its interpreter, and a compiled file is built first.
     */
    @NonNull
    private String getRunLabel() {
        String name = mFile == null ? "" : mFile.getName();
        String lowerCase = name.toLowerCase(Locale.ROOT);

        if (lowerCase.endsWith(".py")) return "Çalıştır (python)";
        if (lowerCase.endsWith(".js") || lowerCase.endsWith(".mjs")) return "Çalıştır (node)";
        if (lowerCase.endsWith(".php")) return "Çalıştır (php)";
        if (lowerCase.endsWith(".c") || lowerCase.endsWith(".cpp") || lowerCase.endsWith(".cc"))
            return "Derle + çalıştır";
        if (lowerCase.endsWith(".java")) return "Derle + çalıştır";
        if (lowerCase.endsWith(".sh")) return "Çalıştır";
        if (isShellConfigFile(lowerCase) || containsAlias()) return "source " + name;
        return "Çalıştır";
    }

    /** Update the run button label after the text or the file changed. */
    private void updateRunLabel() {
        if (mRunButton == null) return;
        mRunButton.setText(getRunLabel());
    }

    private boolean isChanged() {
        return mEditor != null && !mEditor.getText().toString().equals(mOpenedContent);
    }

    private void updateTitle() {
        if (mTitle == null || mFile == null) return;
        mTitle.setText(mFile.getName() + (mFile.exists() ? "" : " (yeni)") + (isChanged() ? " •" : ""));
    }

    /* ---------------------------------------------------------------------------------------- */
    /* File                                                                                      */
    /* ---------------------------------------------------------------------------------------- */

    private void loadFile() {
        StringBuilder content = new StringBuilder();
        String problem = null;

        if (mFile.isFile()) {
            if (mFile.length() > MAX_FILE_SIZE) {
                problem = "Dosya çok büyük (" + mFile.length() + " bayt), editör betikler için.";
            } else {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(mFile), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line).append('\n');
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG,
                        "Failed to read \"" + mFile.getAbsolutePath() + "\"", e);
                    problem = "Dosya okunamadı: " + e;
                }
            }
        }

        mOpenedContent = content.toString();
        mEditor.setText(mOpenedContent);
        mEditor.setSelection(0);
        updateTitle();
        updateRunLabel();
        if (problem != null) showMessage("Uyarı", problem);
    }

    /** Write the text to the file, returns {@code true} if it was saved. */
    private boolean save() {
        if (mEditor == null || mFile == null) return false;

        File directory = mFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            showMessage("Kaydedilemedi", "Klasör oluşturulamadı: " + directory.getAbsolutePath());
            return false;
        }

        String content = mEditor.getText().toString();
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to write \"" + mFile.getAbsolutePath() + "\"", e);
            showMessage("Kaydedilemedi", String.valueOf(e));
            return false;
        }

        // A script has to stay executable, otherwise `sh betik.sh` is the only way to run it
        if (mFile.getName().endsWith(".sh") && !mFile.setExecutable(true, false)) {
            Logger.logWarn(LOG_TAG, "Failed to set the execute permission of \"" + mFile.getAbsolutePath() + "\"");
        }

        mOpenedContent = content;
        updateTitle();
        Toast.makeText(this, "Kaydedildi: " + mFile.getName(), Toast.LENGTH_SHORT).show();
        return true;
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Run                                                                                       */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * Save the file and run it with the interpreter of its type, the output is shown in a dialog.
     *
     * A shell script is run with `sh`, a shell configuration file (`.bashrc`, `.profile`, ...) is
     * **sourced** so that its `alias` lines take effect in the shell, and a source file is run with
     * its interpreter (`python`, `python3`, `node`, `php`), a C/C++ file is compiled with `cc` and
     * then run, and a Java file is compiled with `javac` and then run with `java`.
     */
    private void saveAndRun() {
        final String command = getRunCommand();
        if (command == null) {
            showMessage("Çalıştırılamaz",
                "Bu dosya türü çalıştırılamıyor.\nDosya: " + mFile.getName()
                    + "\n\nDesteklenen: .sh, .bashrc/.profile, .py, .js, .php, .c, .cpp, .java");
            return;
        }
        if (!save()) return;

        Toast.makeText(this, "Çalıştırılıyor: " + mFile.getName(), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String output = runShellCommand(command);
            runOnUiThread(() -> showOutput(output));
        }, "mio-editor-run").start();
    }

    /**
     * @return Returns the shell command that runs the file, or {@code null} if the type is not
     * supported.
     */
    @Nullable
    private String getRunCommand() {
        String name = mFile.getName();
        String lowerCase = name.toLowerCase(Locale.ROOT);
        String path = MioPackages.quote(mFile.getAbsolutePath());

        if (lowerCase.endsWith(".py")) {
            return "command -v python >/dev/null 2>&1 && python " + path
                + " || python3 " + path;
        }
        if (lowerCase.endsWith(".js") || lowerCase.endsWith(".mjs")) {
            return "node " + path;
        }
        if (lowerCase.endsWith(".php")) {
            return "php " + path;
        }
        if (lowerCase.endsWith(".c") || lowerCase.endsWith(".cpp") || lowerCase.endsWith(".cc")) {
            String binary = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/mio-run-"
                + Math.abs(name.hashCode());
            return "cc -O0 -o " + binary + " " + path + " && " + binary
                + "; status=$?; rm -f " + binary + "; exit $status";
        }
        if (lowerCase.endsWith(".java")) {
            String className = name.substring(0, name.length() - ".java".length());
            String directory = mFile.getParentFile() == null
                ? TermuxConstants.TERMUX_HOME_DIR_PATH : mFile.getParentFile().getAbsolutePath();
            return "cd " + MioPackages.quote(directory) + " && javac " + MioPackages.quote(name)
                + " && java " + MioPackages.quote(className);
        }
        if (lowerCase.endsWith(".sh")) {
            return "sh " + path;
        }
        if (isShellConfigFile(lowerCase) || containsAlias()) {
            return ". " + path;
        }
        return null;
    }

    /** @return Returns {@code true} for `~/.bashrc`, `~/.profile` and the like. */
    private static boolean isShellConfigFile(@NonNull String lowerCaseName) {
        return lowerCaseName.endsWith(".bashrc") || lowerCaseName.endsWith(".zshrc")
            || lowerCaseName.endsWith(".profile") || lowerCaseName.endsWith(".bash_profile")
            || lowerCaseName.endsWith(".bash_aliases") || lowerCaseName.endsWith("bash.bashrc");
    }

    /** @return Returns {@code true} if the file contains an `alias` line. */
    private boolean containsAlias() {
        if (mEditor == null) return false;
        String text = mEditor.getText().toString();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("alias ") || trimmed.startsWith("alias=")) return true;
        }
        return false;
    }

    /**
     * Run the command with `sh -c` in the folder of the file, with the environment of the bootstrap,
     * so that `python`, `node` and the other commands of the terminal are found.
     */
    @NonNull
    private String runShellCommand(@NonNull String command) {
        StringBuilder output = new StringBuilder();
        try {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh");
            commandLine.add("-c");
            commandLine.add(command);

            ProcessBuilder builder = new ProcessBuilder(commandLine);
            File directory = mFile.getParentFile();
            if (directory != null) builder.directory(directory);
            builder.redirectErrorStream(true);

            builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            builder.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            builder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            builder.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":"
                + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets:/system/bin:/system/xbin");
            builder.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            builder.environment().put("LANG", "en_US.UTF-8");
            builder.environment().put("TERM", "xterm-256color");

            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    // Do not keep an unlimited output of a runaway script
                    if (output.length() > 20000) {
                        output.append("... (çıktı kesildi)\n");
                        break;
                    }
                }
            }

            int exitCode = process.waitFor();
            output.append("\n[çıkış kodu: ").append(exitCode).append(']');
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run \"" + mFile.getAbsolutePath() + "\"", e);
            output.append("Çalıştırılamadı: ").append(e);
        }
        return output.toString();
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Helpers                                                                                   */
    /* ---------------------------------------------------------------------------------------- */

    private void showOutput(@NonNull String output) {
        String text = output.trim().isEmpty() ? "(çıktı yok)" : output;

        // A sourced file changes the shell it runs in, so say how to apply it in the terminal
        String name = mFile == null ? "" : mFile.getName();
        String lowerCase = name.toLowerCase(Locale.ROOT);
        if (isShellConfigFile(lowerCase) || containsAlias()) {
            text = text + "\n\nNot: alias'lar sadece çalıştırılan kabukta geçerliydi.\n"
                + "Terminalde geçerli olması için: source ~/" + name;
        }
        new AlertDialog.Builder(this)
            .setTitle(mFile.getName() + " — çıktı")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showMessage(@NonNull String title, @NonNull String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
