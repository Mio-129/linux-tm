package com.termux.app.mio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
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
    private TextView mTitle;
    private File mFile;
    private String mOpenedContent = "";

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

        File home = new File("/data/data/com.termux/files/home");
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
        addAction(actionRow, "Çalıştır", v -> saveAndRun());
        addAction(actionRow, "Kapat", v -> onBackPressed());
        root.addView(actionRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ------------------------------------------------------------------------ editor -----
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFF0B0F13);

        mEditor = new EditText(this);
        mEditor.setTypeface(Typeface.MONOSPACE);
        mEditor.setTextSize(13);
        mEditor.setTextColor(0xFFDCE6EF);
        mEditor.setBackgroundColor(0xFF0B0F13);
        mEditor.setGravity(Gravity.TOP | Gravity.START);
        mEditor.setPadding(dp(10), dp(8), dp(10), dp(8));
        mEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mEditor.setHorizontallyScrolling(false);
        mEditor.setSingleLine(false);
        mEditor.setTextIsSelectable(true);
        scrollView.addView(mEditor, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --------------------------------------------------------------------- code keys -----
        root.addView(buildCodeKeyRow(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** The row of the code keys that are hard to type on a phone keyboard. */
    @NonNull
    private View buildCodeKeyRow() {
        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);
        horizontalScrollView.setBackgroundColor(0xFF161C22);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(2), dp(4), dp(2));

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

    private void addAction(@NonNull LinearLayout row, @NonNull String text,
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

    /** Save the file and run it with `sh`, the output is shown in a dialog. */
    private void saveAndRun() {
        if (!mFile.getName().endsWith(".sh")) {
            showMessage("Çalıştırılamaz", "Sadece .sh betikleri çalıştırılabilir.\nDosya: " + mFile.getName());
            return;
        }
        if (!save()) return;

        Toast.makeText(this, "Çalıştırılıyor: " + mFile.getName(), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String output = runScript();
            runOnUiThread(() -> showOutput(output));
        }, "mio-editor-run").start();
    }

    @NonNull
    private String runScript() {
        StringBuilder output = new StringBuilder();
        try {
            List<String> command = new ArrayList<>();
            command.add("/data/data/com.termux/files/usr/bin/sh");
            command.add(mFile.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            File directory = mFile.getParentFile();
            if (directory != null) builder.directory(directory);
            builder.redirectErrorStream(true);

            builder.environment().put("PREFIX", "/data/data/com.termux/files/usr");
            builder.environment().put("HOME", "/data/data/com.termux/files/home");
            builder.environment().put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
            builder.environment().put("PATH", "/data/data/com.termux/files/usr/bin:"
                + "/data/data/com.termux/files/usr/bin/applets:/system/bin:/system/xbin");
            builder.environment().put("LD_LIBRARY_PATH", "/data/data/com.termux/files/usr/lib");
            builder.environment().put("LANG", "en_US.UTF-8");

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
