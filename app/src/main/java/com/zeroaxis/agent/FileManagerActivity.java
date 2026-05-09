package com.zeroaxis.agent;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class FileManagerActivity extends AppCompatActivity {

    // ── Colors matching the app theme ────────────────────────────────────────
    private static final int COLOR_BG          = 0xFFF5F7FA;  // @color/background
    private static final int COLOR_PRIMARY     = 0xFF2E86AB;  // @color/primary
    private static final int COLOR_WHITE       = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MAIN   = 0xFF212529;
    private static final int COLOR_TEXT_SEC    = 0xFF6C757D;  // @color/text_secondary
    private static final int COLOR_DIVIDER     = 0xFFDEE2E6;  // @color/spinner_border
    private static final int COLOR_RED         = 0xFFD32F2F;
    private static final int COLOR_GREEN       = 0xFF28A745;
    private static final int COLOR_SURFACE     = 0xFFFFFFFF;
    private static final int COLOR_TOOLBAR     = 0xFF2E86AB;

    private RecyclerView rvFiles;
    private TextView tvPath, tvEmpty, tvTitle;
    private ImageButton btnUpFolder;
    private String username;
    private File currentDir;
    private File rootDir;
    private FileAdapter adapter;
    private final List<File> fileList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        username = getIntent().getStringExtra("username");

        // ── Root dir: use app-scoped external storage (no runtime permission needed) ──
        // AgentService.getUserFolder uses getExternalStorageDirectory() which needs
        // MANAGE_EXTERNAL_STORAGE at runtime on Android 11+. We override here with
        // getExternalFilesDir which is always accessible to this app.
        File appExternal = getExternalFilesDir(null);
        if (appExternal == null) appExternal = getFilesDir();
        rootDir = new File(appExternal, "users/" + username);
        if (!rootDir.exists()) rootDir.mkdirs();
        currentDir = rootDir;

        buildUI();
        loadDirectory(currentDir);
    }

    // ── UI construction (matches app light theme, no extra XML needed) ────────
    private void buildUI() {
        // Root
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // ── Toolbar ──────────────────────────────────────────────────────────
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(COLOR_TOOLBAR);
        toolbar.setPadding(dp(8), dp(12), dp(8), dp(12));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        btnUpFolder = new ImageButton(this);
        btnUpFolder.setText("←");  // fallback text; works without drawable
        // Use a back arrow via text since we can't guarantee drawable availability
        TextView btnUpText = new TextView(this);
        btnUpText.setText("←");
        btnUpText.setTextColor(COLOR_WHITE);
        btnUpText.setTextSize(22);
        btnUpText.setPadding(dp(4), 0, dp(12), 0);
        btnUpText.setId(View.generateViewId());
        btnUpText.setTag("btnUp");

        tvTitle = new TextView(this);
        tvTitle.setTextColor(COLOR_WHITE);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setText("📁 My Files");
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        toolbar.addView(btnUpText);
        toolbar.addView(tvTitle);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Path bar ─────────────────────────────────────────────────────────
        LinearLayout pathBar = new LinearLayout(this);
        pathBar.setBackgroundColor(0xFFE9ECEF);
        pathBar.setPadding(dp(16), dp(6), dp(16), dp(6));
        pathBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView pathLabel = new TextView(this);
        pathLabel.setText("Location: ");
        pathLabel.setTextColor(COLOR_TEXT_SEC);
        pathLabel.setTextSize(12);

        tvPath = new TextView(this);
        tvPath.setTextColor(COLOR_PRIMARY);
        tvPath.setTextSize(12);
        tvPath.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPath.setSingleLine(true);
        tvPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tvPath.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pathBar.addView(pathLabel);
        pathBar.addView(tvPath);
        root.addView(pathBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Action buttons ────────────────────────────────────────────────────
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setBackgroundColor(COLOR_SURFACE);
        actionBar.setPadding(dp(12), dp(8), dp(12), dp(8));

        Button btnNewFolder = makeButton("+ New Folder", COLOR_PRIMARY);
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp1.setMargins(0, 0, dp(6), 0);
        btnNewFolder.setLayoutParams(bp1);

        Button btnNewFile = makeButton("+ New File", COLOR_GREEN);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp2.setMargins(dp(6), 0, 0, 0);
        btnNewFile.setLayoutParams(bp2);

        actionBar.addView(btnNewFolder);
        actionBar.addView(btnNewFile);
        root.addView(actionBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Divider ───────────────────────────────────────────────────────────
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        // ── Empty state ───────────────────────────────────────────────────────
        tvEmpty = new TextView(this);
        tvEmpty.setText("This folder is empty.\nTap '+ New Folder' or '+ New File' to get started.");
        tvEmpty.setTextColor(COLOR_TEXT_SEC);
        tvEmpty.setTextSize(15);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(64), dp(32), dp(0));
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── File list ─────────────────────────────────────────────────────────
        rvFiles = new RecyclerView(this);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvFiles.setBackgroundColor(COLOR_SURFACE);
        root.addView(rvFiles, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        adapter = new FileAdapter(fileList);
        rvFiles.setAdapter(adapter);

        // Listeners
        btnUpText.setOnClickListener(v -> navigateUp());
        btnNewFolder.setOnClickListener(v -> promptCreate(true));
        btnNewFile.setOnClickListener(v -> promptCreate(false));
    }

    // ── Directory loading ─────────────────────────────────────────────────────
    private void loadDirectory(File dir) {
        currentDir = dir;
        fileList.clear();

        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            fileList.addAll(Arrays.asList(files));
        }

        adapter.notifyDataSetChanged();

        // Update path display
        String rel = currentDir.getAbsolutePath().replace(rootDir.getAbsolutePath(), "");
        tvPath.setText(rel.isEmpty() ? "/" : rel);

        // Update title
        boolean atRoot = currentDir.getAbsolutePath().equals(rootDir.getAbsolutePath());
        tvTitle.setText(atRoot ? "📁 " + username + "'s Files" : "📁 " + currentDir.getName());

        // Up button enabled only when not at root
        View btnUp = getWindow().getDecorView().findViewWithTag("btnUp");
        if (btnUp != null) {
            btnUp.setAlpha(atRoot ? 0.3f : 1.0f);
            btnUp.setEnabled(!atRoot);
        }

        tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
        rvFiles.setVisibility(fileList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void navigateUp() {
        if (currentDir.getAbsolutePath().equals(rootDir.getAbsolutePath())) return;
        loadDirectory(currentDir.getParentFile());
    }

    // ── Create folder or file ─────────────────────────────────────────────────
    private void promptCreate(boolean isFolder) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText et = new EditText(this);
        et.setHint(isFolder ? "Folder name" : "File name (e.g. notes.txt)");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setBackground(null);
        et.setPadding(0, dp(8), 0, dp(8));
        layout.addView(et);

        new AlertDialog.Builder(this)
                .setTitle(isFolder ? "New Folder" : "New File")
                .setView(layout)
                .setPositiveButton(isFolder ? "Create Folder" : "Create File", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Name cannot be empty");
                        return;
                    }
                    // Sanitise: no path separators
                    if (name.contains("/") || name.contains("\\")) {
                        toast("Name cannot contain / or \\");
                        return;
                    }
                    File target = new File(currentDir, name);
                    boolean ok;
                    if (isFolder) {
                        ok = target.mkdirs();
                    } else {
                        try {
                            // Ensure parent exists (it does, but be safe)
                            ok = target.createNewFile();
                        } catch (IOException e) {
                            toast("Error: " + e.getMessage());
                            return;
                        }
                    }
                    if (ok) {
                        loadDirectory(currentDir);
                    } else if (target.exists()) {
                        toast((isFolder ? "Folder" : "File") + " already exists");
                    } else {
                        toast("Could not create " + (isFolder ? "folder" : "file") +
                                ". Check storage permissions.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        // Auto-show keyboard
        et.requestFocus();
    }

    // ── File options menu ─────────────────────────────────────────────────────
    private void showFileOptions(File file) {
        List<String> opts = new ArrayList<>();
        if (file.isDirectory()) {
            opts.add("📂  Open");
        } else {
            opts.add("📖  Open");
            if (isTextFile(file)) opts.add("✏️  Edit");
            opts.add("↗️  Share");
        }
        opts.add("✏️  Rename");
        opts.add("🗑  Delete");

        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String choice = opts.get(which);
                    if (choice.contains("Open")) {
                        if (file.isDirectory()) loadDirectory(file);
                        else openFile(file);
                    } else if (choice.contains("Edit")) {
                        openTextEditor(file);
                    } else if (choice.contains("Share")) {
                        shareFile(file);
                    } else if (choice.contains("Rename")) {
                        promptRename(file);
                    } else if (choice.contains("Delete")) {
                        promptDelete(file);
                    }
                })
                .show();
    }

    private void promptRename(File file) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText et = new EditText(this);
        et.setText(file.getName());
        et.setSelectAllOnFocus(true);
        et.setBackground(null);
        et.setPadding(0, dp(8), 0, dp(8));
        layout.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(layout)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(file.getName())) return;
                    if (newName.contains("/") || newName.contains("\\")) {
                        toast("Name cannot contain / or \\");
                        return;
                    }
                    File dest = new File(file.getParentFile(), newName);
                    if (dest.exists()) { toast("A file with that name already exists"); return; }
                    if (file.renameTo(dest)) loadDirectory(currentDir);
                    else toast("Rename failed");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptDelete(File file) {
        String msg = file.isDirectory()
                ? "Delete folder \"" + file.getName() + "\" and all its contents? This cannot be undone."
                : "Delete \"" + file.getName() + "\"? This cannot be undone.";
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    if (deleteRecursive(file)) loadDirectory(currentDir);
                    else toast("Delete failed");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    private void openFile(File file) {
        if (isTextFile(file)) { openTextEditor(file); return; }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with…"));
        } catch (Exception e) {
            toast("No app available to open this file type");
        }
    }

    private void openTextEditor(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }

            ScrollView sv = new ScrollView(this);
            EditText et = new EditText(this);
            et.setText(sb.toString());
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setTextColor(COLOR_TEXT_MAIN);
            et.setTextSize(13);
            et.setBackgroundColor(COLOR_WHITE);
            et.setPadding(dp(12), dp(12), dp(12), dp(12));
            et.setMinLines(12);
            sv.addView(et);

            LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
            sv.setLayoutParams(svParams);
            sv.setPadding(dp(16), dp(8), dp(16), dp(8));

            new AlertDialog.Builder(this)
                    .setTitle("✏️  " + file.getName())
                    .setView(sv)
                    .setPositiveButton("Save", (d, w) -> {
                        try (FileWriter fw = new FileWriter(file, false)) {
                            fw.write(et.getText().toString());
                            toast("Saved ✓");
                        } catch (IOException ex) {
                            toast("Save failed: " + ex.getMessage());
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Exception e) {
            toast("Could not open file: " + e.getMessage());
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file.getName()));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share via…"));
        } catch (Exception e) {
            toast("Share failed: " + e.getMessage());
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        final List<File> files;
        FileAdapter(List<File> files) { this.files = files; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(COLOR_WHITE);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setGravity(Gravity.CENTER_VERTICAL);

            // Ripple background
            int[] attrs = {android.R.attr.selectableItemBackground};
            android.content.res.TypedArray ta =
                    parent.getContext().obtainStyledAttributes(attrs);
            row.setBackground(ta.getDrawable(0));
            ta.recycle();

            // Icon
            TextView icon = new TextView(parent.getContext());
            icon.setTextSize(26);
            LinearLayout.LayoutParams iconLP =
                    new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLP.setMargins(0, 0, dp(12), 0);
            icon.setLayoutParams(iconLP);
            icon.setGravity(Gravity.CENTER);

            // Text block
            LinearLayout textBlock = new LinearLayout(parent.getContext());
            textBlock.setOrientation(LinearLayout.VERTICAL);
            textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(parent.getContext());
            name.setTextColor(COLOR_TEXT_MAIN);
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);

            TextView meta = new TextView(parent.getContext());
            meta.setTextColor(COLOR_TEXT_SEC);
            meta.setTextSize(12);

            textBlock.addView(name);
            textBlock.addView(meta);

            // Chevron
            TextView chevron = new TextView(parent.getContext());
            chevron.setText("›");
            chevron.setTextSize(22);
            chevron.setTextColor(COLOR_TEXT_SEC);
            chevron.setPadding(dp(8), 0, 0, 0);

            row.addView(icon);
            row.addView(textBlock);
            row.addView(chevron);

            RecyclerView.LayoutParams rvLP = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT);
            row.setLayoutParams(rvLP);

            return new VH(row, icon, name, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            File f = files.get(pos);
            SimpleDateFormat sdf =
                    new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault());

            h.icon.setText(f.isDirectory() ? "📁" : fileEmoji(f.getName()));
            h.name.setText(f.getName());

            if (f.isDirectory()) {
                String[] ch = f.list();
                int cnt = ch != null ? ch.length : 0;
                h.meta.setText(cnt + " item" + (cnt == 1 ? "" : "s"));
            } else {
                h.meta.setText(fmtSize(f.length()) + "   " +
                        sdf.format(new Date(f.lastModified())));
            }

            h.row.setOnClickListener(v -> {
                if (f.isDirectory()) loadDirectory(f);
                else showFileOptions(f);
            });
            h.row.setOnLongClickListener(v -> { showFileOptions(f); return true; });
        }

        @Override public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            LinearLayout row;
            TextView icon, name, meta;
            VH(LinearLayout r, TextView ic, TextView n, TextView m) {
                super(r);
                row = r; icon = ic; name = n; meta = m;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(COLOR_WHITE);
        b.setTextSize(13);
        b.setBackgroundColor(color);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private boolean isTextFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                || n.endsWith(".log") || n.endsWith(".json") || n.endsWith(".xml")
                || n.endsWith(".html") || n.endsWith(".htm");
    }

    private String fileEmoji(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf"))  return "📄";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp")) return "🖼️";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")
                || n.endsWith(".mov")) return "🎬";
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".ogg")) return "🎵";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "📝";
        if (n.endsWith(".docx") || n.endsWith(".doc")) return "📘";
        if (n.endsWith(".xlsx") || n.endsWith(".csv")) return "📊";
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")) return "🗜️";
        if (n.endsWith(".json") || n.endsWith(".xml")) return "🗂️";
        return "📎";
    }

    private String getMimeType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".mp4"))  return "video/mp4";
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".zip"))  return "application/zip";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")
                || n.endsWith(".csv") || n.endsWith(".json")) return "text/plain";
        return "*/*";
    }

    private String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        
    }
}