package org.schabi.newpipe.player.helper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import java.io.File;

/** A real, separately installed focus owner. Only packaged in the instrumentation APK. */
public final class AudioFocusOwnerActivity extends Activity {
    static final String CONTROL = "org.schabi.newpipe.test.focus.CONTROL";
    static final String STATUS = "org.schabi.newpipe.test.focus.STATUS";
    static final String TOKEN = "token";
    static final String REPLY_PACKAGE = "reply_package";
    static final String GAIN_TYPE = "gain_type";
    static final String EVENT = "event";
    static final String RESULT = "result";
    static final String OWNER_UID = "owner_uid";
    static final String ERROR = "error";
    static final String REQUESTED = "requested";
    static final String FOCUS_CHANGED = "focus_changed";
    static final String RELEASED = "released";
    static final String FAILED = "failed";
    private static final String TAG = "AudioFocusReactor";

    private AudioManager manager;
    private Object modernRequest;
    private int gainType;
    private boolean configured;
    private MediaPlayer media;
    private File wav;
    private String token;
    private String replyPackage;
    private boolean started;
    private boolean registered;
    private boolean released;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        Log.d(TAG, "external fixture focus callback=" + change + " uid=" + Process.myUid());
        sendStatus(FOCUS_CHANGED, change, null);
    };

    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (CONTROL.equals(intent.getAction()) && token.equals(intent.getStringExtra(TOKEN))) {
                releaseOwner();
                finish();
            }
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        token = getIntent().getStringExtra(TOKEN);
        replyPackage = getIntent().getStringExtra(REPLY_PACKAGE);
        if (token == null || replyPackage == null) {
            finish();
            return;
        }
        final TextView label = new TextView(this);
        label.setText("Audio focus test: generated silent WAV only");
        setContentView(label);
        manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        gainType = getIntent().getIntExtra(GAIN_TYPE, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if (Build.VERSION.SDK_INT >= 26) {
            modernRequest = Api26.createRequest(gainType, focusListener);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(control, new IntentFilter(CONTROL), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(control, new IntentFilter(CONTROL));
        }
        configured = true;
        registered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (configured && !started) {
            started = true;
            // The activity must actually be resumed for the API 35 foreground requirement.
            getWindow().getDecorView().post(this::acquireAndPlay);
        }
    }

    private void acquireAndPlay() {
        if (isFinishing() || released) {
            return;
        }
        try {
            final int result = Build.VERSION.SDK_INT >= 26
                    ? Api26.request(manager, modernRequest)
                    : manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, gainType);
            Log.d(TAG, "external fixture request type=" + getIntent().getIntExtra(GAIN_TYPE, 0)
                    + " result=" + result + " uid=" + Process.myUid());
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                sendStatus(REQUESTED, result, null);
                releaseOwner();
                finish();
                return;
            }
            wav = AudioFocusWav.create(getCacheDir());
            media = new MediaPlayer();
            media.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            media.setDataSource(wav.getAbsolutePath());
            media.setLooping(true);
            media.prepare();
            media.start();
            sendStatus(REQUESTED, result, null);
        } catch (final Exception error) {
            Log.e(TAG, "external fixture failed", error);
            sendStatus(FAILED, AudioManager.AUDIOFOCUS_REQUEST_FAILED, error.toString());
            releaseOwner();
            finish();
        }
    }

    private void releaseOwner() {
        if (released) {
            return;
        }
        released = true;
        if (media != null) {
            media.release();
            media = null;
        }
        if (manager != null && configured) {
            final int result = Build.VERSION.SDK_INT >= 26
                    ? Api26.abandon(manager, modernRequest)
                    : manager.abandonAudioFocus(focusListener);
            Log.d(TAG, "external fixture abandon result=" + result + " uid=" + Process.myUid());
        }
        if (wav != null) {
            wav.delete();
        }
    }

    private void sendStatus(final String event, final int result, final String error) {
        if (replyPackage != null && token != null) {
            sendBroadcast(new Intent(STATUS).setPackage(replyPackage).putExtra(TOKEN, token)
                    .putExtra(EVENT, event).putExtra(RESULT, result)
                    .putExtra(OWNER_UID, Process.myUid()).putExtra(ERROR, error));
        }
    }

    @Override
    protected void onDestroy() {
        releaseOwner();
        if (registered) {
            unregisterReceiver(control);
        }
        // Signals both focus abandonment and the activity leaving the foreground.
        sendStatus(RELEASED, AudioManager.AUDIOFOCUS_REQUEST_GRANTED, null);
        super.onDestroy();
    }

    // The standalone test APK activity must not depend on classes supplied by the target APK.
    @android.annotation.TargetApi(26)
    private static final class Api26 {
        private Api26() {
        }

        private static Object createRequest(final int gain,
                final AudioManager.OnAudioFocusChangeListener listener) {
            return new android.media.AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(listener).build();
        }

        private static int request(final AudioManager audioManager, final Object focusRequest) {
            return audioManager.requestAudioFocus((android.media.AudioFocusRequest) focusRequest);
        }

        private static int abandon(final AudioManager audioManager, final Object focusRequest) {
            return audioManager.abandonAudioFocusRequest(
                    (android.media.AudioFocusRequest) focusRequest);
        }
    }

}
