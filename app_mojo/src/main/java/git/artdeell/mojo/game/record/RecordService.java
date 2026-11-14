package git.artdeell.mojo.game.record;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.io.File;

import git.artdeell.mojo.R;

/**
 * MediaProjection-based recorder: a VirtualDisplay mirrors the whole screen straight
 * into the encoder's input surface, so no pixel readback or YUV conversion is needed.
 * Runs as a mediaProjection foreground service in the game process.
 */
public class RecordService extends Service {

    public static final String ACTION_STOP = "git.artdeell.mojo.game.record.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI = "dpi";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_GALLERY = "gallery";

    private static final String TAG = "RecordService";
    private static final int NOTIFICATION_ID = 4711; // placeholder replaced below
    private static volatile boolean sStopRequested;
    private static Thread sWorker;
    public static volatile boolean sRecording;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void requestStop() {
        sStopRequested = true;
    }

    public static void stopBlocking() {
        sStopRequested = true;
        Thread worker = sWorker;
        if (worker != null) {
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0);
        sStopRequested = false;
        sRecording = true;
        final Intent config = intent;
        sWorker = new Thread(() -> record(config), "CopperProjectionRecorder");
        sWorker.start();
        return START_NOT_STICKY;
    }

    private void record(Intent config) {
        File output = new File(config.getStringExtra(EXTRA_OUTPUT));
        boolean gallery = config.getBooleanExtra(EXTRA_GALLERY, true);
        int width = config.getIntExtra(EXTRA_WIDTH, 1280);
        int height = config.getIntExtra(EXTRA_HEIGHT, 720);
        int dpi = config.getIntExtra(EXTRA_DPI, 320);
        int fps = config.getIntExtra(EXTRA_FPS, 30);
        int bitrate = config.getIntExtra(EXTRA_BITRATE, 8_000_000);
        MediaProjection projection = null;
        VirtualDisplay virtualDisplay = null;
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        Surface surface = null;
        boolean finished = false;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(config.getIntExtra(EXTRA_RESULT_CODE, -1),
                    config.getParcelableExtra(EXTRA_DATA));
                    
            // Android 14+ refuses to start capture without a registered callback
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // User revoked capture from the system privacy chip / notification
                    Log.w(TAG, "MediaProjection stopped by the system");
                    requestStop();
                }
            }, new Handler(Looper.getMainLooper()));        

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();

            virtualDisplay = projection.createVirtualDisplay("CopperRecorder",
                    width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
                    
            if (virtualDisplay == null) throw new IOException("VirtualDisplay creation failed");

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            boolean started = false;
            boolean eos = false;
            while (!sStopRequested && !eos) {
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    started = true;
                    continue;
                }
                if (out >= 0) {
                    java.nio.ByteBuffer buffer = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (started && buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                }
            }
            if (started) muxer.stop();
            finished = true;
        } catch (Throwable t) {
            Log.e(TAG, "Projection recording failed", t);
            failToast(t);
        } finally {
            if (virtualDisplay != null) virtualDisplay.release();
            if (projection != null) projection.stop();
            if (surface != null) surface.release();
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {}
                codec.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {}
            }
            if (finished) GameRecorder.publishRecording(this, output, gallery);
            else output.delete();
            sRecording = false;
            stopForeground(true);
            stopSelf();
        }
    }
    
    private void failToast(Throwable t) {
        final String message = t.getClass().getSimpleName() + ": " + t.getMessage();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, getString(R.string.recorder_failed) + " — " + message, Toast.LENGTH_LONG).show());
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("copper_recorder",
                    getString(R.string.recorder_running), NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Intent stopIntent = new Intent(this, RecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "copper_recorder")
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.recorder_running))
                .setSmallIcon(R.drawable.ic_px_record)
                .setOngoing(true)
                .addAction(0, getString(R.string.recorder_stop_action), stopPending)
                .build();
    }
}