package com.zeroaxis.agent;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class FileManagerActivity extends AppCompatActivity {

    private RecyclerView rvFiles;
    private TextView tvPath, tvEmpty;
    private Button btnBack, btnNewFolder, btnNewFile, btnUpFolder;
    private String username;
    private File currentDir;
    private File rootDir;
    private FileAdapter adapter;
    private List<File> fileList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build layout programmatically (no XML needed)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1a1a2e);
        root.setPadding(0, 48, 0, 0);

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(16, 8, 16, 8);
        topBar.setBackgroundColor(0xFF16213e);

        btnUpFolder = new Button(this);
        btnUpFolder.setText("↑ Up");
        btnUpFolder.setBackgroundColor(0xFF0f3460);
        btnUpFolder.setTextColor(0xFFFFFFFF);
        btnUpFolder.setPadding(24, 8, 24, 8);

        tvPath = new TextView(this);
        tvPath.setTextColor(0xFFaaaaaa);
        tvPath.setTextSize(12);
        tvPath.setPadding(16, 0, 0, 0);
        tvPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tvPath.setSingleLine(true);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvPath.setLayoutParams(pathParams);

        topBar.addView(btnUpFolder);
        topBar.addView(tvPath);
        root.addView(topBar);

        // Action buttons
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(16, 8, 16, 8);
        actionBar.setBackgroundColor(0xFF0d0d1a);

        btnNewFolder = new Button(this);
        btnNewFolder.setText("+ Folder");
        btnNewFolder.setBackgroundColor(0xFF533483);
        btnNewFolder.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(0, 0, 8, 0);
        btnNewFolder.setLayoutParams(btnParams);

        btnNewFile = new Button(this);
        btnNewFile.setText("+ File");
        btnNewFile.setBackgroundColor(0xFF2d6a4f);
        btnNewFile.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams2.setMargins(8, 0, 0, 0);
        btnNewFile.setLayoutParams(btnParams2);

        actionBar.addView(btnNewFolder);
        actionBar.addView(btnNewFile);
        root.addView(actionBar);

        // Empty state
        tvEmpty = new TextView(this);
        tvEmpty.setText("This folder is empty");
        tvEmpty.setTextColor(0xFF666666);
        tvEmpty.setTextSize(16);
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(0, 80, 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        // File list
        rvFiles = new RecyclerView(this);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout.LayoutParams rvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        rvFiles.setLayoutParams(rvParams);
        root.addView(rvFiles);

        setContentView(root);

        username = getIntent().getStringExtra("username");
        rootDir = AgentService.getUserFolder(this, username);
        if (!rootDir.exists()) rootDir.mkdirs();
        currentDir = rootDir;

        adapter = new FileAdapter(fileList);
        rvFiles.setAdapter(adapter);

        btnUpFolder.setOnClickListener(v -> navigateUp());
        btnNewFolder.setOnClickListener(v -> promptCreateFolder());
        btnNewFile.setOnClickListener(v -> promptCreateFile());

        setTitle("📁 " + username + "'s Files");
        loadDirectory(currentDir);
    }

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
            Collections.addAll(fileList, files);
        }

        adapter.notifyDataSetChanged();

        // Path relative to root
        String rel = currentDir.getAbsolutePath().replace(rootDir.getAbsolutePath(), "");
        tvPath.setText(rel.isEmpty() ? "/" : rel);

        tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
        rvFiles.setVisibility(fileList.isEmpty() ? View.GONE : View.VISIBLE);

        // Can't go above root
        btnUpFolder.setEnabled(!currentDir.getAbsolutePath().equals(rootDir.getAbsolutePath()));
    }

    private void navigateUp() {
        if (currentDir.getAbsolutePath().equals(rootDir.getAbsolutePath())) return;
        loadDirectory(currentDir.getParentFile());
    }

    private void promptCreateFolder() {
        EditText et = new EditText(this);
        et.setHint("Folder name");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("New Folder")
                .setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!name.isEmpty()) {
                        File f = new File(currentDir, name);
                        if (f.mkdirs()) {
                            loadDirectory(currentDir);
                        } else {
                            Toast.makeText(this, "Could not create folder", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCreateFile() {
        EditText et = new EditText(this);
        et.setHint("File name (e.g. notes.txt)");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("New File")
                .setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!name.isEmpty()) {
                        File f = new File(currentDir, name);
                        try {
                            if (f.createNewFile()) {
                                loadDirectory(currentDir);
                            } else {
                                Toast.makeText(this, "File already exists", Toast.LENGTH_SHORT).show();
                            }
                        } catch (IOException e) {
                            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFileOptions(File file) {
        String[] options;
        if (file.isDirectory()) {
            options = new String[]{"Open", "Rename", "Delete"};
        } else {
            options = new String[]{"Open", "Rename", "Delete", "Share"};
        }

        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(options, (d, which) -> {
                    if (file.isDirectory()) {
                        switch (which) {
                            case 0: loadDirectory(file); break;
                            case 1: promptRename(file); break;
                            case 2: promptDelete(file); break;
                        }
                    } else {
                        switch (which) {
                            case 0: openFile(file); break;
                            case 1: promptRename(file); break;
                            case 2: promptDelete(file); break;
                            case 3: shareFile(file); break;
                        }
                    }
                })
                .show();
    }

    private void promptRename(File file) {
        EditText et = new EditText(this);
        et.setText(file.getName());
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(et)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(file.getName())) {
                        File dest = new File(file.getParentFile(), newName);
                        if (file.renameTo(dest)) {
                            loadDirectory(currentDir);
                        } else {
                            Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptDelete(File file) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + file.getName() + "?")
                .setMessage(file.isDirectory() ? "This will delete the folder and all its contents." : "This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (deleteRecursive(file)) {
                        loadDirectory(currentDir);
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", file);
            String mime = getMimeType(file.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            // If it's a text file, show inline editor
            if (file.getName().endsWith(".txt") || file.getName().endsWith(".md")
                    || file.getName().endsWith(".csv") || file.getName().endsWith(".log")) {
                openTextEditor(file);
            } else {
                Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openTextEditor(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();

            EditText et = new EditText(this);
            et.setText(sb.toString());
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            et.setGravity(android.view.Gravity.TOP);
            et.setMinLines(10);
            et.setPadding(16, 16, 16, 16);

            new AlertDialog.Builder(this)
                    .setTitle("✏ " + file.getName())
                    .setView(et)
                    .setPositiveButton("Save", (d, w) -> {
                        try {
                            FileWriter fw = new FileWriter(file, false);
                            fw.write(et.getText().toString());
                            fw.close();
                            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                        } catch (IOException ex) {
                            Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show();
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
            startActivity(Intent.createChooser(intent, "Share"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String filename) {
        if (filename.endsWith(".pdf"))  return "application/pdf";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".png"))  return "image/png";
        if (filename.endsWith(".mp4"))  return "video/mp4";
        if (filename.endsWith(".mp3"))  return "audio/mpeg";
        if (filename.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (filename.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (filename.endsWith(".txt") || filename.endsWith(".md") || filename.endsWith(".log") || filename.endsWith(".csv"))
            return "text/plain";
        return "*/*";
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        List<File> files;
        FileAdapter(List<File> files) { this.files = files; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(24, 20, 24, 20);
            row.setClickable(true);
            row.setFocusable(true);

            // Ripple / press feedback
            int[] attrs = {android.R.attr.selectableItemBackground};
            android.content.res.TypedArray ta = parent.getContext().obtainStyledAttributes(attrs);
            row.setBackground(ta.getDrawable(0));
            ta.recycle();

            TextView icon = new TextView(parent.getContext());
            icon.setTextSize(24);
            icon.setTag("icon");
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            iconParams.setMargins(0, 0, 24, 0);
            icon.setLayoutParams(iconParams);

            LinearLayout textBlock = new LinearLayout(parent.getContext());
            textBlock.setOrientation(LinearLayout.VERTICAL);
            textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(parent.getContext());
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(15);
            name.setTag("name");

            TextView meta = new TextView(parent.getContext());
            meta.setTextColor(0xFF888888);
            meta.setTextSize(11);
            meta.setTag("meta");

            textBlock.addView(name);
            textBlock.addView(meta);
            row.addView(icon);
            row.addView(textBlock);

            // Divider
            LinearLayout wrapper = new LinearLayout(parent.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            wrapper.addView(row);

            View divider = new View(parent.getContext());
            divider.setBackgroundColor(0x22FFFFFF);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            wrapper.addView(divider);

            return new VH(wrapper, row, icon, name, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            File f = files.get(pos);
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());

            h.icon.setText(f.isDirectory() ? "📁" : getFileEmoji(f.getName()));
            h.name.setText(f.getName());

            if (f.isDirectory()) {
                String[] children = f.list();
                int count = children != null ? children.length : 0;
                h.meta.setText(count + " item" + (count == 1 ? "" : "s"));
            } else {
                h.meta.setText(formatSize(f.length()) + "  ·  " + sdf.format(new Date(f.lastModified())));
            }

            h.row.setOnClickListener(v -> {
                if (f.isDirectory()) loadDirectory(f);
                else showFileOptions(f);
            });
            h.row.setOnLongClickListener(v -> {
                showFileOptions(f);
                return true;
            });
        }

        @Override
        public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            LinearLayout row;
            TextView icon, name, meta;
            VH(View outer, LinearLayout row, TextView icon, TextView name, TextView meta) {
                super(outer);
                this.row = row;
                this.icon = icon;
                this.name = name;
                this.meta = meta;
            }
        }
    }

    private String getFileEmoji(String name) {
        if (name.endsWith(".pdf"))  return "📄";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) return "🖼";
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi"))  return "🎬";
        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav"))  return "🎵";
        if (name.endsWith(".txt") || name.endsWith(".md"))  return "📝";
        if (name.endsWith(".docx") || name.endsWith(".doc")) return "📘";
        if (name.endsWith(".xlsx") || name.endsWith(".csv")) return "📊";
        if (name.endsWith(".zip") || name.endsWith(".rar")) return "🗜";
        return "📎";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}