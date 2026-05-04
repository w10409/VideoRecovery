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
        Set<String> existingVideos = null;
        Set<String> allFiles = null;

        try {
            existingVideos = getExistingVideos();
            allFiles = scanAllStorageFiles();

            Set<String> deletedFiles = new HashSet<>(allFiles);
            if (existingVideos != null) {
                deletedFiles.removeAll(existingVideos);
            }

            Log.d(TAG, "All files found: " + allFiles.size() + ", Existing in MediaStore: " + (existingVideos != null ? existingVideos.size() : 0));
            writeLog("All files found: " + allFiles.size() + ", Existing in MediaStore: " + (existingVideos != null ? existingVideos.size() : 0));
            Log.d(TAG, "Potentially deleted videos: " + deletedFiles.size());
            writeLog("Potentially deleted videos: " + deletedFiles.size());

            // Also scan recycle bin directories
            scanRecycleBin(deletedFiles);

            List<String> recoveredFiles = new ArrayList<>();
            int total = deletedFiles.size();
            int count = 0;

            for (String file : deletedFiles) {
                count++;
                int progress = (count * 100) / Math.max(total, 1);
                updateNotification("已恢复 " + recoveredFiles.size() + " 个文件", progress);

                if (recoverFile(file)) {
                    recoveredFiles.add(file);
                    writeLog("Recovered: " + file);
                }
            }

            saveRecoveredFiles(recoveredFiles);
            int finalCount = recoveredFiles.size();
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

    private void scanRecycleBin(Set<String> deletedFiles) {
        // Huawei recycle bin: /storage/emulated/0/.RecycleBinHW/ or similar
        File[] mounts = {
            new File("/storage/emulated/0/.RecycleBinHW"),
            new File("/storage/emulated/0/.RecycleBin"),
            new File("/storage/emulated/0/.Trash"),
        };

        for (File mount : mounts) {
            if (mount.exists() && mount.canRead()) {
                writeLog("Scanning recycle bin: " + mount.getAbsolutePath());
                scanDirectory(mount, deletedFiles, 0);
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
