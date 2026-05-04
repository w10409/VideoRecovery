package com.recovery.video;

import android.os.Bundle;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSION = 100;

    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> checkPermissions()
        );

        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    startScan();
                } else {
                    showToast("需要权限才能扫描文件");
                }
            }
        );

        Button scanBtn = findViewById(R.id.scanButton);
        scanBtn.setOnClickListener(v -> requestPermissions());

        checkPermissions();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                manageStorageLauncher.launch(intent);
            } else {
                startScan();
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                updateStatus("已获得存储权限");
            }
        }
    }

    private void startScan() {
        Intent serviceIntent = new Intent(this, RecoveryService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        showToast("开始扫描...");
        finish();
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(String msg) {
        TextView status = findViewById(R.id.statusText);
        if (status != null) status.setText(msg);
    }
}
