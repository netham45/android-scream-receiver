package com.netham45.android_scream_receiver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final String TAG = "AndroidScreamReceiverMainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Checking notification permission.");

        // Check for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission already granted.");
                startAudioServiceAndFinish();
            } else {
                Log.d(TAG, "Requesting notification permission.");
                // Request the permission
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
                // We will start the service in onRequestPermissionsResult
            }
        } else {
            // No runtime permission needed for notifications below Android 13
            Log.d(TAG, "Notification permission not required (below Android 13).");
            startAudioServiceAndFinish();
        }

        // Optional: Add a simple UI here if needed later to show status
        // setContentView(R.layout.activity_main);
        // Note: If adding a UI, don't finish immediately in startAudioServiceAndFinish()
    }

    private void startAudioServiceAndFinish() {
        Log.d(TAG, "Starting AudioService.");
        Intent serviceIntent = new Intent(this, AudioService.class);
        // Use startForegroundService for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent); // Fallback for older versions
        }

        // Finish the activity so it doesn't stay in the foreground
        // If you add a UI, you might remove this finish() call
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted by user.");
                startAudioServiceAndFinish();
            } else {
                Log.w(TAG, "Notification permission denied by user.");
                // Handle permission denial (e.g., show a message)
                Toast.makeText(this, "Notification permission denied. Service cannot show status.", Toast.LENGTH_LONG).show();
                // Finish the activity even if permission is denied, as the service might still run
                // but won't be able to show its foreground notification properly.
                finish();
            }
        }
    }
}
