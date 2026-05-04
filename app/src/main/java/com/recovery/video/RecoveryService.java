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
import android.os.FileObserver;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecoveryService extends Service {
    private static final String TAG = "RecoveryService";
    private static final String CHANNEL_ID = "VideoRecoveryChannel";

    private NotificationManager notificationManager;
    private boolean isScanning = false;
    private int progress = 0;

    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        registerScreenOffReceiver();
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

    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.d(TAG, "Screen off - keeping service alive");
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOffReceiver, filter);
    }

    private void scanForDeletedVideos() {
        try {
            Set<String> existingVideos = getExistingVideos();
            Set<String> allFiles = scanAllStorageFiles();

            Set<String> deletedFiles = new HashSet<>(allFiles);
            deletedFiles.removeAll(existingVideos);

            Log.d(TAG, "Found " + deletedFiles.size() + " potentially deleted videos");

            List<String> recoveredFiles = new ArrayList<>();
            for (String file : deletedFiles) {
                if (recoverFile(file)) {
                    recoveredFiles.add(file);
                }
                progress = (recoveredFiles.size() * 100) / deletedFiles.size();
                updateNotification("已恢复 " + recoveredFiles.size() + " 个文件", progress);
            }

            saveRecoveredFiles(recoveredFiles);
            showCompletionNotification(recoveredFiles.size());

        } catch (Exception e) {
            Log.e(TAG, "Scan error", e);
            showCompletionNotification(-1);
        } finally {
            isScanning = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private Set<String> getExistingVideos() {
        Set<String> videos = new HashSet<>();
        String[] projection = { MediaStore.Video.Media.DATA };
        Cursor cursor = getContentResolver().query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        );
        if (cursor != null) {
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            while (cursor.moveToNext()) {
                videos.add(cursor.getString(dataCol));
            }
            cursor.close();
        }
        return videos;
    }

    private Set<String> scanAllStorageFiles() {
        Set<String> files = new HashSet<>();
        scanDirectory(new File("/storage/emulated/0"), files, 0);
        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File dir : externalDirs) {
                if (dir != null && dir.getAbsolutePath().contains("Android")) {
                    File parent = dir.getParentFile();
                    if (parent != null) {
                        scanDirectory(parent, files, 0);
                    }
                }
            }
        }
        return files;
    }

    private void scanDirectory(File dir, Set<String> files, int depth) {
        if (depth > 8 || files.size() > 100000) return;
        if (dir == null || !dir.exists() || !dir.canRead()) return;

        File[] list = dir.listFiles();
        if (list == null) return;

        for (File file : list) {
            String name = file.getName().toLowerCase();
            if (file.isDirectory()) {
                if (!name.equals(".") && !name.equals("..")) {
                    scanDirectory(file, files, depth + 1);
                }
            } else if (isVideoFile(name)) {
                files.add(file.getAbsolutePath());
            }
        }
    }

    private boolean isVideoFile(String name) {
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

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String ext = getFileExtension(path);
            File dest = new File(destDir, "recovered_" + timestamp + ext);

            copyFile(source, dest);
            addToMediaStore(dest);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to recover: " + path, e);
            return false;
        }
    }

    private String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot) : ".mp4";
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst);
             FileChannel in = fis.getChannel();
             FileChannel out = fos.getChannel()) {
            in.transferTo(0, in.size(), out);
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
        try (FileOutputStream fos = new FileOutputStream(listFile)) {
            for (String f : files) {
                fos.write((f + "\n").getBytes());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save list", e);
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
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE);

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
        String text = count >= 0
            ? "完成！恢复了 " + count + " 个视频文件"
            : "扫描完成，但恢复失败";
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
