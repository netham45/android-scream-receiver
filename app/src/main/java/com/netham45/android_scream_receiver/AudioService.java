package com.netham45.android_scream_receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class AudioService extends Service {

    private static final String TAG = "AndroidScreamReceiverAudioService";
    private static final String CHANNEL_ID = "AndroidScreamReceiverAudioChannel";
    private static final int NOTIFICATION_ID_EXIT = 1; // ID for the simple Exit notification (used for startForeground)
    private static final int NOTIFICATION_ID_MEDIA = 2; // ID for the Media Controls notification
    private static final int NETWORK_PORT = 4010;
    private static final int SOCKET_TIMEOUT_ACTIVE_MS = 10; // Reduced timeout for active streaming
    private static final int SOCKET_TIMEOUT_SLEEP_MS = 1000; // Longer timeout when sleeping
    private static final long INACTIVITY_TIMEOUT_MS = 5000; // 5 seconds

    private AudioTrack audioTrack;
    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock; // Keep WiFi active
    private DatagramSocket socket;
    private Thread networkThread;
    private volatile boolean isRunning = true;
    private volatile boolean isSleeping = false;
    private Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable inactivityRunnable;

    // Store current audio parameters to detect changes
    private int currentSampleRate = 0;
    private int currentChannelConfig = 0;
    private int currentAudioFormat = 0;

    // --- Service Lifecycle ---

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service creating.");
        initializeWakeLocks();
        createNotificationChannel();
        initializeMediaSession();
        // initializeAudioTrack(); // Delay initialization until first packet
        startNetworkListener();
        resetInactivityTimer(); // Start the timer initially
        Log.d(TAG, "onCreate: Service created successfully.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: Service starting.");

        // Handle potential actions from notification/media controls
        if (intent != null && intent.getAction() != null) {
            handleIntentAction(intent.getAction());
            return START_STICKY; // Remain running until explicitly stopped
        }

        // Make the service run in the foreground using the EXIT notification
        Notification exitNotification = createExitNotification("Receiving Audio");
        startForeground(NOTIFICATION_ID_EXIT, exitNotification);

        // Also show the media notification
        //Notification mediaNotification = createMediaNotification("Receiving Audio", PlaybackStateCompat.STATE_PLAYING);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
          //  manager.notify(NOTIFICATION_ID_MEDIA, mediaNotification);
        } else {
            Log.e(TAG, "Failed to get NotificationManager to show media notification.");
        }

        acquireWakeLocks(); // Acquire locks when starting foreground

        Log.d(TAG, "onStartCommand: Service started in foreground with two notifications.");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Service destroying.");
        isRunning = false;
        releaseWakeLocks();
        stopNetworkListener();
        releaseAudioTrack();
        releaseMediaSession();
        stopForeground(true); // Remove EXIT notification associated with startForeground
        // Explicitly cancel the MEDIA notification
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID_MEDIA);
        }
        inactivityHandler.removeCallbacks(inactivityRunnable); // Clean up handler
        Log.d(TAG, "onDestroy: Service destroyed, notifications removed.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Not used for started services
        return null;
    }

    // --- Initialization ---

    private void initializeWakeLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // Partial wake lock: Keeps CPU running, screen/keyboard can turn off
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidScreamReceiver::WakeLock");
        wakeLock.setReferenceCounted(false); // Manage acquire/release manually

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // High performance Wi-Fi lock
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndroidScreamReceiver::WifiLock");
        wifiLock.setReferenceCounted(false);
        Log.d(TAG, "WakeLocks initialized.");
    }

     private void acquireWakeLocks() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d(TAG, "Partial WakeLock acquired.");
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
            Log.d(TAG, "WifiLock acquired.");
        }
    }

    private void releaseWakeLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Partial WakeLock released.");
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            Log.d(TAG, "WifiLock released.");
        }
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Android Scream Receiver Audio Service",
                    NotificationManager.IMPORTANCE_DEFAULT // Keep default importance for visibility
            );
            // Disable sound for this channel
            serviceChannel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created.");
            } else {
                 Log.e(TAG, "Failed to get NotificationManager.");
            }
        }
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);

        // Set flags for media button handling
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                              MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // Set initial playback state (Paused initially, maybe?)
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        mediaSession.setPlaybackState(stateBuilder.build()); // Start as stopped/paused

        // Set the callback for media button events
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d(TAG, "MediaSession: Play requested.");
                // Resume playback / wake up from sleep
                wakeUpFromInactivity(); // This already calls updateNotifications
                // updateMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING); // Called within wakeUpFromInactivity
                // updateNotifications("Receiving Audio", PlaybackStateCompat.STATE_PLAYING); // Called within wakeUpFromInactivity
                // You might need to re-acquire wakelocks if released during sleep
                 acquireWakeLocks(); // Ensure locks are held
            }

            @Override
            public void onPause() {
                Log.d(TAG, "MediaSession: Pause requested.");
                // Pause playback logic if applicable (or just update state)
                updateMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                updateNotifications("Paused", PlaybackStateCompat.STATE_PAUSED); // Update both notifications
                // Consider releasing wakelocks if paused manually
                // releaseWakeLocks(); // Optional: Release locks on manual pause
            }

            @Override
            public void onStop() {
                Log.d(TAG, "MediaSession: Stop requested.");
                stopSelf(); // Stop the service entirely
            }

            @Override
            public void onSkipToNext() {
                Log.d(TAG, "MediaSession: Skip Next requested.");
                // Implement skip next logic if needed
            }

            @Override
            public void onSkipToPrevious() {
                 Log.d(TAG, "MediaSession: Skip Previous requested.");
                // Implement skip previous logic if needed
            }

             @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                 Log.d(TAG, "MediaSession: Media button event received.");
                // Let the system handle the event based on the callback methods
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });

        // Set metadata (Title is important for lock screen)
        updateMediaMetadata("Android Scream Receiver Stream", "Receiving audio..."); // Initial title

        mediaSession.setActive(true);
        Log.d(TAG, "MediaSession initialized and active.");
    }

     // Initializes or re-initializes the AudioTrack with the given parameters
     private boolean initializeAudioTrack(int sampleRate, int channelConfig, int audioFormat) {
        Log.d(TAG, "Initializing AudioTrack with Rate: " + sampleRate + ", Channels: " + channelConfig + ", Format: " + audioFormat);

        // Release existing track if it exists
        releaseAudioTrack();

        // Validate parameters before proceeding
        if (sampleRate <= 0 || channelConfig == 0 || audioFormat == 0) {
             Log.e(TAG, "Invalid audio parameters received for AudioTrack initialization.");
             return false;
        }

        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
             Log.e(TAG, "Invalid audio parameters for getMinBufferSize.");
             // Handle error appropriately - maybe stop service?
             // stopSelf(); // Maybe too drastic? Log the error for now.
             return false; // Indicate failure
        }

        int bufferSize = minBufferSize;
        Log.d(TAG, "Calculated minBufferSize: " + minBufferSize + ", Using bufferSize: " + bufferSize);

        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM) // Streaming mode
                    .build();

            // Start playback immediately (it will wait for data)
            // Start playback immediately (it will wait for data)
            audioTrack.play();
            Log.i(TAG, "AudioTrack initialized and playing. Buffer size: " + bufferSize + " bytes");
            return true; // Indicate success

        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to create AudioTrack: " + e.getMessage(), e);
            audioTrack = null; // Ensure track is null on failure
            // Handle error - stop service?
            // stopSelf(); // Maybe too drastic?
            return false; // Indicate failure
        }
    }


    // --- Media Session & Notifications ---

    // Creates the simple notification with just status and Exit button (used for startForeground)
    private Notification createExitNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class); // Open app on tap
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Exit Action (reuse stopIntent logic)
        Intent stopIntent = new Intent(this, AudioService.class);
        stopIntent.setAction("ACTION_STOP");
        // Use a different request code for the PendingIntent if needed, although action should differentiate
        PendingIntent exitPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Scream Receiver Service") // Different title maybe?
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_notification) // Use custom drawable icon
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes the notification non-dismissable
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent); // Exit action

        return builder.build();
    }

    // Creates the notification with MediaStyle controls
    private Notification createMediaNotification(String statusText, int playbackState) {
        // Intent to open app - can be null if not needed for media notification
        // Intent notificationIntent = new Intent(this, MainActivity.class);
        // PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Stop Action
        Intent stopIntent = new Intent(this, AudioService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Play/Pause Action (Example - adapt based on current state)
        // You'll likely want to toggle this action based on the actual playback state
        Intent playPauseIntent = new Intent(this, AudioService.class);
        PendingIntent playPausePendingIntent;
        int playPauseIcon;
        String playPauseTitle;

        if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            playPauseIntent.setAction("ACTION_PAUSE");
            playPausePendingIntent = PendingIntent.getService(this, 2, playPauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            playPauseIcon = android.R.drawable.ic_media_pause; // System pause icon
            playPauseTitle = "Pause";
        } else {
            playPauseIntent.setAction("ACTION_PLAY");
            playPausePendingIntent = PendingIntent.getService(this, 2, playPauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            playPauseIcon = android.R.drawable.ic_media_play; // System play icon
            playPauseTitle = "Play";
        }


        // Build the media notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Scream Receiver") // Or use metadata title
                .setContentText(statusText)     // Or use metadata artist/album
                .setSmallIcon(R.drawable.ic_notification) // Use custom drawable icon
                // .setContentIntent(pendingIntent) // Optional: action on tapping notification body
                .setOngoing(true) // Keep it persistent while service runs
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
                // Add media control actions
                .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent) // Use "Stop" for media context
                // Apply MediaStyle
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1) // Show Play/Pause (index 0) and Stop (index 1) in compact view
                 );

        return builder.build();
    }

     // Updates both notifications
     private void updateNotifications(String statusText, int playbackState) {
        Notification exitNotification = createExitNotification(statusText);
        //Notification mediaNotification = createMediaNotification(statusText, playbackState);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_EXIT, exitNotification); // Update Exit notification
            //manager.notify(NOTIFICATION_ID_MEDIA, mediaNotification); // Update Media notification
            Log.d(TAG, "Notifications updated: " + statusText);
        } else {
             Log.e(TAG, "Failed to get NotificationManager for updates.");
        }
    }

    private void updateMediaPlaybackState(int state) {
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f); // Set state, position unknown, speed 1x
        mediaSession.setPlaybackState(stateBuilder.build());
        Log.d(TAG, "MediaSession state updated to: " + state);
    }

     private void updateMediaMetadata(String title, String artist) {
        mediaSession.setMetadata(new android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                // Add other metadata like album art if available
                .build());
         Log.d(TAG, "MediaSession metadata updated. Title: " + title);
    }


    // --- Network Handling ---

    private void startNetworkListener() {
        if (networkThread != null && networkThread.isAlive()) {
            Log.w(TAG, "Network thread already running.");
            return;
        }

        networkThread = new Thread(() -> {
            // Set thread priority higher for network/audio processing
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            Log.d(TAG, "Network thread priority set to AUDIO.");

            byte[] buffer = new byte[4096]; // Keep receive buffer reasonable, OS buffer is more critical
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                socket = new DatagramSocket(NETWORK_PORT);
                // Increase socket receive buffer size
                int desiredSocketBufferSize = 512 * 1024; // 512 KB example
                socket.setReceiveBufferSize(desiredSocketBufferSize);
                int actualSocketBufferSize = socket.getReceiveBufferSize();
                Log.i(TAG, "UDP Socket created on port " + NETWORK_PORT + ". Requested SO_RCVBUF: " + desiredSocketBufferSize + ", Actual: " + actualSocketBufferSize);


                while (isRunning) {
                    try {
                        // Adjust timeout based on sleep state
                        socket.setSoTimeout(isSleeping ? SOCKET_TIMEOUT_SLEEP_MS : SOCKET_TIMEOUT_ACTIVE_MS);
                        socket.receive(packet);

                        // --- Packet Received ---
                        if (isSleeping) {
                            wakeUpFromInactivity(); // Wake up if we were sleeping
                        }
                        resetInactivityTimer(); // Reset timer on receiving data

                        int bytesRead = packet.getLength();
                        if (bytesRead >= 5) { // Need at least 5 bytes for header
                            byte[] receivedData = packet.getData(); // Get the raw buffer
                            int offset = packet.getOffset(); // Get the starting offset in the buffer

                            // Extract header (first 5 bytes)
                            byte[] header = new byte[5];
                            System.arraycopy(receivedData, offset, header, 0, 5);

                            // Extract PCM data (remaining bytes)
                            int pcmDataLength = bytesRead - 5;
                            byte[] pcmData = new byte[pcmDataLength];
                            System.arraycopy(receivedData, offset + 5, pcmData, 0, pcmDataLength);

                            // Handle the packet (header parsing, AudioTrack config, playback)
                            handleAudioPacket(header, pcmData);

                        } else {
                            Log.w(TAG, "Received packet too small (" + bytesRead + " bytes), expected >= 5.");
                        }


                    } catch (SocketTimeoutException e) {
                        // Timeout occurred - expected behavior, especially when sleeping
                        if (!isSleeping) {
                             //Log.v(TAG, "Socket timeout while active.");
                            // If timeout happens while active, the inactivity timer will handle sleep transition
                        } else {
                             //Log.v(TAG, "Socket timeout while sleeping (checking for data).");
                            // Continue loop to check again after SOCKET_TIMEOUT_SLEEP_MS
                        }
                    } catch (IOException e) {
                        if (isRunning) { // Avoid logging errors if we are shutting down
                            Log.e(TAG, "Network receive error: " + e.getMessage(), e);
                            // Consider adding a small delay before retrying to avoid busy-looping on errors
                            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                    }
                }

            } catch (SocketException e) {
                Log.e(TAG, "Failed to create or bind socket on port " + NETWORK_PORT + ": " + e.getMessage(), e);
                // Consider stopping the service if the socket fails critically
                stopSelf();
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    Log.i(TAG, "UDP Socket closed.");
                }
            }
            Log.i(TAG, "Network listener thread finished.");
        });

        networkThread.start();
        Log.d(TAG, "Network listener thread started.");
    }

    private void stopNetworkListener() {
        isRunning = false; // Signal thread to stop
        if (socket != null) {
            socket.close(); // Interrupts blocking receive() call
        }
        if (networkThread != null) {
            try {
                networkThread.join(1000); // Wait for thread to finish
                Log.d(TAG, "Network thread joined.");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for network thread to finish.");
                Thread.currentThread().interrupt();
            }
            networkThread = null;
        }
    }

    // --- Audio Playback & Packet Handling ---

    // Parses header, configures AudioTrack if needed, and plays PCM data
    private void handleAudioPacket(byte[] header, byte[] pcmData) {
        // --- Parse Header ---
        // Byte 0: Sample Rate
        int baseRate = ((header[0] & 0x80) == 0) ? 48000 : 44100; // Bit 7: 0=48k, 1=44.1k
        int multiplier = (header[0] & 0x7F); // Bits 0-6
        int sampleRate = baseRate * (multiplier); // Multiplier is 0-based

        // Byte 1: Sample Width (Bits) -> AudioFormat Encoding
        int bitDepth = header[1] & 0xFF; // Treat as unsigned byte
        int audioFormatEncoding;
        switch (bitDepth) {
            case 8:
                audioFormatEncoding = AudioFormat.ENCODING_PCM_8BIT;
                break;
            case 16:
                audioFormatEncoding = AudioFormat.ENCODING_PCM_16BIT;
                break;
            case 24: // Android uses ENCODING_PCM_FLOAT for 24/32 bit usually, check AudioTrack support
                audioFormatEncoding = AudioFormat.ENCODING_PCM_FLOAT; // Or ENCODING_PCM_24BIT_PACKED if supported/needed
                 Log.w(TAG, "Using ENCODING_PCM_FLOAT for 24-bit depth. Verify compatibility.");
                break;
            case 32:
                audioFormatEncoding = AudioFormat.ENCODING_PCM_FLOAT;
                break;
            default:
                Log.e(TAG, "Unsupported bit depth: " + bitDepth);
                return; // Cannot process this packet
        }

        // Byte 2: Number of Channels -> AudioFormat Channel Config
        int numChannels = header[2] & 0xFF;
        int channelConfig;
        // Basic mapping - might need refinement based on channel mask
        switch (numChannels) {
            case 1:
                channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                break;
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4: // Example: Quad
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6: // Example: 5.1
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8: // Example: 7.1
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                     channelConfig = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
                 } else {
                      Log.w(TAG, "7.1 surround not directly supported below Android M, using default.");
                      channelConfig = AudioFormat.CHANNEL_OUT_DEFAULT; // Fallback
                 }
                break;
            default:
                 Log.w(TAG, "Unsupported channel count: " + numChannels + ". Using default.");
                 channelConfig = AudioFormat.CHANNEL_OUT_DEFAULT; // Fallback
                 break;
        }

        // Bytes 3-4: Channel Mask (DWORD, little-endian)
        // We use the numChannels mapping above for simplicity, but the mask could be used
        // for more precise mapping if needed (e.g., AudioFormat.Builder.setChannelMask)
        // int channelMask = ((header[4] & 0xFF) << 8) | (header[3] & 0xFF);
        // Log.v(TAG, "Parsed Channel Mask: " + channelMask); // Verbose

        // --- Check if AudioTrack needs reconfiguration ---
        boolean needsReconfig = (audioTrack == null ||
                                 sampleRate != currentSampleRate ||
                                 channelConfig != currentChannelConfig ||
                                 audioFormatEncoding != currentAudioFormat);

        if (needsReconfig) {
            Log.i(TAG, "Audio format change detected or first packet. Reconfiguring AudioTrack.");
            Log.i(TAG, "New Format - Rate: " + sampleRate + ", Channels: " + numChannels + " (Config: " + channelConfig + "), Depth: " + bitDepth + " (Format: " + audioFormatEncoding + ")");
            if (initializeAudioTrack(sampleRate, channelConfig, audioFormatEncoding)) {
                // Update current parameters only on successful initialization
                currentSampleRate = sampleRate;
                currentChannelConfig = channelConfig;
                currentAudioFormat = audioFormatEncoding;
            } else {
                Log.e(TAG, "Failed to reconfigure AudioTrack. Skipping packet.");
                return; // Cannot play if reconfig failed
            }
        }

        // --- Play PCM Data ---
        processAndPlayAudio(pcmData);
    }


    private void processAndPlayAudio(byte[] pcmData) {
        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            int bytesWritten = audioTrack.write(pcmData, 0, pcmData.length);
            if (bytesWritten < 0) {
                Log.e(TAG, "AudioTrack write error: " + bytesWritten + " (Format: " + currentAudioFormat + ")");
                // Handle error (e.g., buffer underrun, invalid data, format mismatch?)
            } else if (bytesWritten < pcmData.length) {
                 Log.w(TAG, "AudioTrack couldn't write all data. Wrote " + bytesWritten + "/" + pcmData.length);
            } else {
                 // Log.v(TAG, "Wrote " + bytesWritten + " bytes to AudioTrack."); // Very verbose, disable normally
            }
        } else {
            Log.w(TAG, "AudioTrack not ready or not playing, discarding data.");
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    audioTrack.stop();
                } catch (IllegalStateException e) {
                     Log.e(TAG, "Error stopping AudioTrack: " + e.getMessage());
                }
            }
            audioTrack.release();
            audioTrack = null;
            Log.d(TAG, "AudioTrack released.");
        }
    }

     private void releaseMediaSession() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
            Log.d(TAG, "MediaSession released.");
        }
    }


    // --- Sleep/Inactivity Logic ---

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable); // Remove any existing callbacks
        inactivityRunnable = () -> {
            Log.i(TAG, "Inactivity timeout reached. Entering sleep mode.");
            goToSleep();
        };
        inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS);
         Log.v(TAG, "Inactivity timer reset (" + INACTIVITY_TIMEOUT_MS + "ms).");
    }

    private void goToSleep() {
        if (!isSleeping) {
            isSleeping = true;
            Log.i(TAG, "Entering sleep state. Releasing WakeLock, reducing check frequency.");
            // Pause audio playback if desired during sleep
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                 // audioTrack.pause(); // Optional: Pause audio track
                 Log.d(TAG,"AudioTrack continues playing during sleep check phase.");
            }
            updateMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED); // Reflect state in media session
            updateNotifications("Sleeping (checking network)", PlaybackStateCompat.STATE_PAUSED); // Update both notifications
            releaseWakeLocks(); // Release CPU and WiFi locks to save power
            // Network thread will automatically use longer timeout now
        }
    }

    private void wakeUpFromInactivity() {
        if (isSleeping) {
            isSleeping = false;
            Log.i(TAG, "Waking up from sleep state. Acquiring WakeLock, increasing check frequency.");
            acquireWakeLocks(); // Re-acquire locks
            // Resume audio playback if it was paused
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
                // audioTrack.play();
            }
             updateMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING); // Reflect state
             updateNotifications("Receiving Audio", PlaybackStateCompat.STATE_PLAYING); // Update both notifications
            // Network thread will automatically use shorter timeout now
            resetInactivityTimer(); // Start the timer again now that we are active
        }
    }

    // --- Intent Action Handling ---
    private void handleIntentAction(String action) {
        Log.d(TAG, "Handling action: " + action);
        switch (action) {
            case "ACTION_PLAY":
                // Corresponds to MediaSessionCompat.Callback.onPlay()
                if (mediaSession != null) mediaSession.getController().getTransportControls().play();
                break;
            case "ACTION_PAUSE":
                 // Corresponds to MediaSessionCompat.Callback.onPause()
                if (mediaSession != null) mediaSession.getController().getTransportControls().pause();
                break;
            case "ACTION_STOP":
                 // Corresponds to MediaSessionCompat.Callback.onStop()
                if (mediaSession != null) mediaSession.getController().getTransportControls().stop();
                break;
            // Add cases for NEXT, PREVIOUS if needed
            default:
                Log.w(TAG, "Unknown action received: " + action);
        }
    }
}
