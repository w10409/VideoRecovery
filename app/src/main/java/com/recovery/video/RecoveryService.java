package com.recovery.video;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecoveryService extends Service {
    private static final String TAG = "RecoveryService";
    private static final String CHANNEL_ID = "VideoRecoveryChannel";
    private static final String LOG_FILE_NAME = "recovery_log.txt";

    private NotificationManager notificationManager;
    private boolean isScanning = false;
    private BroadcastReceiver screenOffReceiver;
    private File logFile;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        initLogFile();
    }

    private void initLogFile() {
        try {
            File logDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Recovered");
            if (!logDir.exists()) logDir.mkdirs();
            logFile = new File(logDir, LOG_FILE_NAME);
            writeLog("=== Recovery Service Started ===");
            writeLog("Storage: " + Environment.getExternalStorageDirectory().getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to init log file", e);
        }
    }

    private void writeLog(String msg) {
        if (logFile == null) return;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String logEntry = sdf.format(new Date()) + " " + msg + "\n";
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(logEntry.getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write log", e);
        }
    }

    private void writeLog(String msg, Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        writeLog(msg + ": " + sw.toString());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isScanning) {
            isScanning = true;
            startForeground(1, buildNotification("正在扫描...", 0));
            new Thread(this::scanForDeletedVideos).start();
        }
        return START_STICKY;
    }

    private void scanForDeletedVideos() {
        try {
            writeLog("=== Starting proper deleted video recovery ===");

            // Step 1: Get existing videos from MediaStore (these are NOT deleted)
            Set<String> existingVideos = getExistingVideos();
            writeLog("MediaStore known videos: " + (existingVideos != null ? existingVideos.size() : 0));

            // Step 2: Find candidate deleted video locations
            // These are places where deleted/pending files might linger
            Set<String> candidateFiles = new HashSet<>();
            
            // 2a. Scan recycle bin directories
            scanRecycleBinDirectories(candidateFiles);
            
            // 2b. Scan .pending stub files (partially downloaded/cloud-synced files)
            scanPendingFiles(candidateFiles);

            // Step 3: Filter candidates - only keep files that are:
            // - NOT in MediaStore (truly missing from the system)
            // - OR are in recycle bin / pending locations
            Set<String> trulyDeletedFiles = new HashSet<>();
            for (String path : candidateFiles) {
                File f = new File(path);
                
                // Skip if file doesn't exist or is empty
                if (!f.exists() || f.length() == 0) {
                    writeLog("Skipping (does not exist or empty): " + path);
                    continue;
                }
                
                // Skip if it's in MediaStore (not deleted)
                if (existingVideos != null && existingVideos.contains(path)) {
                    writeLog("Skipping (exists in MediaStore): " + path);
                    continue;
                }
                
                // This is a candidate for recovery
                trulyDeletedFiles.add(path);
                writeLog("Candidate deleted file: " + path + " (" + f.length() + " bytes)");
            }

            writeLog("Truly deleted videos (candidates for recovery): " + trulyDeletedFiles.size());

            // Step 4: Recover the candidates
            List<String> recoveredFiles = new ArrayList<>();
            int total = trulyDeletedFiles.size();
            int count = 0;

            for (String file : trulyDeletedFiles) {
                count++;
                int progress = (count * 100) / Math.max(total, 1);
                updateNotification("已恢复 " + recoveredFiles.size() + " 个文件", progress);

                if (recoverFile(file)) {
                    recoveredFiles.add(file);
                    writeLog("Successfully recovered: " + file);
                }
            }

            saveRecoveredFiles(recoveredFiles);
            int finalCount = recoveredFiles.size();
            writeLog("=== Recovery completed: " + finalCount + " files recovered ===");
            showCompletionNotification(finalCount);

        } catch (Exception e) {
            writeLog("Scan error", e);
            showCompletionNotification(-1);
        } finally {
            isScanning = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private Set<String> getExistingVideos() {
        Set<String> videos = new HashSet<>();
        try {
            String[] projection = { MediaStore.Video.Media.DATA };
            Cursor cursor = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null
            );
            if (cursor != null) {
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                while (cursor.moveToNext()) {
                    String path = cursor.getString(dataCol);
                    if (path != null) videos.add(path);
                }
                cursor.close();
            }
        } catch (Exception e) {
            writeLog("Error getting existing videos", e);
        }
        return videos;
    }

    private Set<String> scanAllStorageFiles() {
        Set<String> files = new HashSet<>();

        // Get external storage directory in a compatible way
        File storageRoot = Environment.getExternalStorageDirectory();
        writeLog("Storage root: " + storageRoot.getAbsolutePath());
        if (storageRoot.exists() && storageRoot.canRead()) {
            scanDirectory(storageRoot, files, 0);
        }

        // Also scan external storage directories
        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File dir : externalDirs) {
                if (dir != null) {
                    File parent = dir.getParentFile();
                    if (parent != null && parent.exists() && parent.canRead()) {
                        writeLog("Scanning external: " + parent.getAbsolutePath());
                        scanDirectory(parent, files, 0);
                    }
                }
            }
        }

        return files;
    }

    private void scanRecycleBinDirectories(Set<String> candidateFiles) {
        // Huawei and other Android recycle bin locations
        File[] recycleDirs = {
            new File("/storage/emulated/0/.RecycleBinHW"),
            new File("/storage/emulated/0/.RecycleBin"),
            new File("/storage/emulated/0/.Trash"),
            new File("/storage/emulated/0/DCIM/.RecycleBin"),
        };

        for (File dir : recycleDirs) {
            if (dir.exists() && dir.canRead()) {
                writeLog("Scanning recycle bin: " + dir.getAbsolutePath());
                scanDirectoryDeep(dir, candidateFiles, 0);
            }
        }
    }

    private void scanPendingFiles(Set<String> candidateFiles) {
        // .pending files are cloud sync stubs or partially downloaded files
        // They often exist in DCIM/Camera or Download directories
        File[] pendingDirs = {
            new File("/storage/emulated/0/DCIM/Camera"),
            new File("/storage/emulated/0/Download"),
            new File("/storage/emulated/0/Movies"),
        };

        for (File dir : pendingDirs) {
            if (dir.exists() && dir.canRead()) {
                scanForPendingStubs(dir, candidateFiles, 0);
            }
        }
    }

    private void scanForPendingStubs(File dir, Set<String> files, int depth) {
        if (depth > 5 || files.size() > 1000) return;
        if (dir == null || !dir.exists() || !dir.canRead()) return;

        // Skip the Recovered output directory to avoid re-processing previous recoveries
        if (dir.getAbsolutePath().contains("/Recovered")) return;

        File[] list = dir.listFiles();
        if (list == null) return;

        for (File file : list) {
            try {
                String name = file.getName();
                if (file.isDirectory()) {
                    if (!name.equals(".") && !name.equals("..")) {
                        scanForPendingStubs(file, files, depth + 1);
                    }
                } else if (name.contains(".pending") || name.startsWith(".pending")) {
                    // .pending files are cloud sync stubs - SKIP THEM (they are 0 bytes)
                    writeLog("Skipping .pending stub: " + file.getAbsolutePath());
                } else if (isVideoFile(name)) {
                    files.add(file.getAbsolutePath());
                }
            } catch (Exception e) {
                // Skip files we can't access
            }
        }
    }

    private void scanDirectoryDeep(File dir, Set<String> files, int depth) {
        if (depth > 8 || files.size() > 10000) return;
        if (dir == null || !dir.exists() || !dir.canRead()) return;

        File[] list = dir.listFiles();
        if (list == null) return;

        for (File file : list) {
            try {
                String name = file.getName();
                if (file.isDirectory()) {
                    if (!name.equals(".") && !name.equals("..")) {
                        scanDirectoryDeep(file, files, depth + 1);
                    }
                } else if (isVideoFile(name)) {
                    files.add(file.getAbsolutePath());
                }
            } catch (Exception e) {
                // Skip files we can't access
            }
        }
    }

    private void scanDirectory(File dir, Set<String> files, int depth) {
        if (depth > 10 || files.size() > 200000) return;
        if (dir == null || !dir.exists() || !dir.canRead()) return;

        File[] list = dir.listFiles();
        if (list == null) return;

        for (File file : list) {
            try {
                String name = file.getName();
                if (file.isDirectory()) {
                    if (!name.equals(".") && !name.equals("..") && !name.startsWith(".")) {
                        scanDirectory(file, files, depth + 1);
                    }
                } else if (isVideoFile(name)) {
                    files.add(file.getAbsolutePath());
                }
            } catch (Exception e) {
                // Skip files we can't access
            }
        }
    }

    private boolean isVideoFile(String name) {
        name = name.toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".3gp") ||
               name.endsWith(".mkv") || name.endsWith(".avi") ||
               name.endsWith(".mov") || name.endsWith(".webm");
    }

    private boolean recoverFile(String path) {
        try {
            File source = new File(path);
            if (!source.exists() || !source.canRead()) return false;

            File destDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Recovered");
            if (!destDir.exists()) destDir.mkdirs();

            String originalName = new File(path).getName();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String ext = getFileExtension(originalName);
            File dest = new File(destDir, "recovered_" + timestamp + "_" + originalName);

            copyFile(source, dest);
            addToMediaStore(dest);
            return true;
        } catch (Exception e) {
            writeLog("Failed to recover: " + path, e);
            return false;
        }
    }

    private String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot) : ".mp4";
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
    }

    private void addToMediaStore(File file) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        sendBroadcast(intent);
    }

    private void saveRecoveredFiles(List<String> files) {
        File cacheDir = getCacheDir();
        File listFile = new File(cacheDir, "recovered_files.txt");
        writeLog("Saving recovered files list: " + files.size() + " files");
        try (FileOutputStream fos = new FileOutputStream(listFile)) {
            for (String f : files) {
                fos.write((f + "\n").getBytes());
            }
        } catch (IOException e) {
            writeLog("Failed to save list", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "视频恢复",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("扫描和恢复已删除的视频文件");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, int progress) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频恢复")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true);

        if (progress > 0) {
            builder.setProgress(100, progress, false);
        }

        return builder.build();
    }

    private void updateNotification(String text, int progress) {
        notificationManager.notify(1, buildNotification(text, progress));
    }

    private void showCompletionNotification(int count) {
        String logPath = logFile != null ? logFile.getAbsolutePath() : "未知";
        String text = count >= 0
            ? "完成！恢复了 " + count + " 个视频文件\n日志: " + logPath
            : "扫描完成，但恢复失败\n日志: " + logPath;
        writeLog("=== Recovery Completed: " + count + " files recovered ===");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频恢复")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true);
        notificationManager.notify(1, builder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenOffReceiver != null) {
            unregisterReceiver(screenOffReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
