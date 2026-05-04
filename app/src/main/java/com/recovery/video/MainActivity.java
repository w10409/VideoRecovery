package com.recovery.video;

import android.os.Bundle;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "VideoRecovery";
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "MainActivity onCreate");

        manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "manageStorageLauncher result: " + result.getResultCode());
                checkPermissionsAndStart();
            }
        );

        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                Log.d(TAG, "permissionLauncher granted: " + granted);
                if (granted) {
                    startScan();
                } else {
                    showToast("需要权限才能扫描文件");
                }
            }
        );

        Button scanBtn = findViewById(R.id.scanButton);
        scanBtn.setOnClickListener(v -> requestPermissions());

        checkPermissionsAndStart();
    }

    private void requestPermissions() {
        Log.d(TAG, "requestPermissions called");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d(TAG, "Requesting MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
            } else {
                Log.d(TAG, "Already have storage manager permission");
                startScan();
            }
        } else {
            Log.d(TAG, "Requesting READ_EXTERNAL_STORAGE");
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void checkPermissionsAndStart() {
        Log.d(TAG, "checkPermissionsAndStart called");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Log.d(TAG, "Has storage manager permission, starting scan");
                updateStatus("已获得存储权限");
                startScan();
            } else {
                Log.d(TAG, "No storage manager permission");
                updateStatus("请授予存储权限");
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Has read permission, starting scan");
                startScan();
            } else {
                Log.d(TAG, "No read permission");
                updateStatus("请授予存储权限");
            }
        }
    }

    private void startScan() {
        Log.d(TAG, "startScan called");
        try {
            Intent serviceIntent = new Intent(this, RecoveryService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            showToast("开始扫描...");
            updateStatus("正在扫描...");
        } catch (Exception e) {
            Log.e(TAG, "startScan failed", e);
            showToast("启动扫描失败: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(String msg) {
        TextView status = findViewById(R.id.statusText);
        if (status != null) status.setText(msg);
    }
}
