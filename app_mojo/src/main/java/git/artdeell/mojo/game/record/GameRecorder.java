package git.artdeell.mojo.game.record;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.view.PixelCopy;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

/**
 * Two capture engines behind one API:
 *  - "projection": MediaProjection + VirtualDisplay in RecordService (stable, needs the
 *    system consent dialog, records the whole screen).
 *  - "internal": permission-free readback of our own window/surface via PixelCopy
 *    (experimental; on some GPUs the game layer can't be read and frames come out blank).
 * Video only in both cases.
 */
public final class GameRecorder {

    public static final String PREF_TYPE = "recorder_type";
    public static final String PREF_QUALITY = "recorder_quality";
    public static final String PREF_FPS = "recorder_fps";
    public static final String PREF_GALLERY = "recorder_save_gallery";
    public static final String PREF_INCLUDE_UI = "recorder_include_ui";

    private static final String TAG = "GameRecorder";
    private static final String MIME = "video/avc";
    private static volatile GameRecorder sInstance;

    private final Activity mActivity;
    private final View mSurfaceView;
    private final int mFps;
    private final int mMaxHeight;
    private final boolean mGallery;
    private final boolean mIncludeUi;
    private final Thread mThread;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private volatile boolean mRunning = true;

    public static boolean isRecording() {
        return (sInstance != null && sInstance.mRunning) || RecordService.sRecording;
    }

    public static boolean isProjectionType(Context context) {
        return "projection".equals(PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_TYPE, "projection"));
    }

    // ── Projection engine entry ──────────────────────────────────────────────

    public static void startProjection(Activity activity, int resultCode, Intent data) {
        if (isRecording()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toastStatic(activity, R.string.recorder_unsupported);
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int maxHeight = parseInt(prefs.getString(PREF_QUALITY, "720"), 720);
        int h = Math.min(metrics.heightPixels, maxHeight);
        int w = (int) ((long) metrics.widthPixels * h / metrics.heightPixels);
        w -= w % 2;
        h -= h % 2;
        int fps = parseInt(prefs.getString(PREF_FPS, "30"), 30);
        boolean gallery = prefs.getBoolean(PREF_GALLERY, true);

        Intent intent = new Intent(activity, RecordService.class);
        intent.putExtra(RecordService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(RecordService.EXTRA_DATA, data);
        intent.putExtra(RecordService.EXTRA_WIDTH, w);
        intent.putExtra(RecordService.EXTRA_HEIGHT, h);
        intent.putExtra(RecordService.EXTRA_DPI, metrics.densityDpi);
        intent.putExtra(RecordService.EXTRA_FPS, fps);
        intent.putExtra(RecordService.EXTRA_BITRATE, bitrateFor(h, fps));
        intent.putExtra(RecordService.EXTRA_OUTPUT, prepareOutputFile(activity, gallery).getAbsolutePath());
        intent.putExtra(RecordService.EXTRA_GALLERY, gallery);
        ContextCompat.startForegroundService(activity, intent);
        RecordService.sRecording = true;
        toastStatic(activity, R.string.recorder_started);
    }

    // ── Internal engine entry ────────────────────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void startInternal(Activity activity, View renderSurface) {
        if (isRecording()) return;
        GameRecorder recorder = new GameRecorder(activity, renderSurface);
        sInstance = recorder;
        recorder.mThread.start();
        toastStatic(activity, R.string.recorder_started);
    }

    public static void stop() {
        GameRecorder recorder = sInstance;
        if (recorder != null) recorder.mRunning = false;
        RecordService.requestStop();
    }

    /** Signals stop and waits for finalization; used on every game-exit path. */
    public static void stopBlocking() {
        GameRecorder recorder = sInstance;
        if (recorder != null) {
            recorder.mRunning = false;
            try {
                recorder.mThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        RecordService.stopBlocking();
    }

    public static File getRecordingsDir() {
        return new File(Tools.DIR_GAME_HOME, "recordings");
    }

    private GameRecorder(Activity activity, View renderSurface) {
        mActivity = activity;
        mSurfaceView = renderSurface;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        mMaxHeight = parseInt(prefs.getString(PREF_QUALITY, "720"), 720);
        mFps = parseInt(prefs.getString(PREF_FPS, "30"), 30);
        mGallery = prefs.getBoolean(PREF_GALLERY, true);
        mIncludeUi = prefs.getBoolean(PREF_INCLUDE_UI, true);
        mThread = new Thread(this::recordLoop, "CopperRecorder");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private interface Grabber {
        boolean grab(Bitmap dest);
    }

    private interface Requester {
        void request(Handler main, Bitmap dest, int[] resultOut, CountDownLatch latch);
    }

    private static Grabber latchGrabber(final Requester requester) {
        final Handler main = new Handler(Looper.getMainLooper());
        return dest -> {
            CountDownLatch latch = new CountDownLatch(1);
            int[] result = {-1};
            try {
                requester.request(main, dest, result, latch);
                return latch.await(200, TimeUnit.MILLISECONDS) && result[0] == 0; // 0 == PixelCopy.RESULT_SUCCESS
            } catch (Exception e) {
                return false;
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private List<Grabber> makeGrabbers() {
        List<Grabber> grabbers = new ArrayList<>();
        final Window window = mActivity.getWindow();
        Grabber windowGrabber = latchGrabber((main, dest, result, latch) ->
                PixelCopy.request(window, dest, r -> {
                    result[0] = r;
                    latch.countDown();
                }, main));
        if (mSurfaceView instanceof SurfaceView) {
            final SurfaceView view = (SurfaceView) mSurfaceView;
            grabbers.add(latchGrabber((main, dest, result, latch) ->
                    PixelCopy.request(view, dest, r -> {
                        result[0] = r;
                        latch.countDown();
                    }, main)));
            if (mIncludeUi) grabbers.add(windowGrabber);
        } else {
            if (mIncludeUi) grabbers.add(windowGrabber);
            if (mSurfaceView instanceof TextureView) {
                final TextureView view = (TextureView) mSurfaceView;
                grabbers.add(dest -> {
                    try {
                        if (!view.isAvailable()) return false;
                        view.getBitmap(dest);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });
            }
        }
        return grabbers;
    }

    /** Accepts a strategy only if it produces a frame whose center isn't one flat color. */
    private static boolean probe(Grabber grabber, Bitmap full) {
        int uniform = 0;
        for (int i = 0; i < 6; i++) {
            if (!grabber.grab(full)) return false;
            if (isCenterUniform(full)) {
                if (++uniform >= 3) return false;
            } else {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isCenterUniform(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int first = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int px = bitmap.getPixel(w / 5 + x * (w * 3 / 5) / 7, h / 5 + y * (h * 3 / 5) / 7);
                if (x == 0 && y == 0) first = px;
                else if (px != first) return false;
            }
        }
        return true;
    }

    private void recordLoop() {
        Bitmap full = null;
        Bitmap scaled = null;
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        File recorded = null;
        boolean finished = false;
        try {
            List<Grabber> candidates = makeGrabbers();
            if (candidates.isEmpty()) throw new IOException("No capture strategy for this surface");
            android.view.View decor = mActivity.getWindow().getDecorView();
            int srcW = decor.getWidth();
            int srcH = decor.getHeight();
            if (srcW < 2 || srcH < 2) throw new IOException("Window not laid out yet");
            full = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
            Grabber grabber = null;
            for (int i = 0; i < candidates.size(); i++) {
                if (probe(candidates.get(i), full)) {
                    grabber = candidates.get(i);
                    Log.i(TAG, "Using capture strategy " + i);
                    break;
                }
            }
            if (grabber == null) {
                toastStatic(mActivity, R.string.recorder_capture_failed);
                return;
            }
            int h = Math.min(srcH, mMaxHeight);
            int w = (int) ((long) srcW * h / srcH);
            w -= w % 2;
            h -= h % 2;
            scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(scaled);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect destRect = new Rect(0, 0, w, h);
            int[] rgba = new int[w * h];

            MediaFormat format = MediaFormat.createVideoFormat(MIME, w, h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(h, mFps));
            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            recorded = prepareOutputFile(mActivity, mGallery);
            muxer = new MediaMuxer(recorded.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] track = {-1};
            boolean[] started = {false};
            boolean[] eosSeen = {false};

            long frameDurationUs = 1_000_000L / mFps;
            long frame = 0;
            while (mRunning) {
                if (decor.getWidth() != srcW || decor.getHeight() != srcH) break;
                long loopStart = System.nanoTime();
                if (grabber.grab(full)) {
                    canvas.drawBitmap(full, null, destRect, paint);
                    scaled.getPixels(rgba, 0, w, 0, 0, w, h);
                    feedFrame(codec, rgba, w, h, frame * frameDurationUs, false);
                    frame++;
                }
                drain(codec, muxer, track, started, eosSeen, 0, 64);
                long sleepNs = frameDurationUs * 1000 - (System.nanoTime() - loopStart);
                if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
            }
            feedFrame(codec, rgba, w, h, frame * frameDurationUs, true);
            int guard = 0;
            while (!eosSeen[0] && guard++ < 200) drain(codec, muxer, track, started, eosSeen, 10_000, 1);
            if (started[0]) muxer.stop();
            finished = true;
        } catch (Throwable t) {
            Log.e(TAG, "Recording failed", t);
            toastStatic(mActivity, R.string.recorder_failed);
        } finally {
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
            if (full != null) full.recycle();
            if (scaled != null) scaled.recycle();
            if (recorded != null) {
                if (finished) publishRecording(mActivity, recorded, mGallery);
                else recorded.delete();
            }
            sInstance = null;
            if (finished) toastStatic(mActivity, R.string.recorder_stopped);
        }
    }

    private static int bitrateFor(int height, int fps) {
        int base = height <= 480 ? 4_000_000 : height <= 720 ? 8_000_000 : 14_000_000;
        return (int) (base * (fps >= 60 ? 1.7f : 1f));
    }

    private void feedFrame(MediaCodec codec, int[] rgba, int w, int h, long ptsUs, boolean eos) {
        int inIdx = codec.dequeueInputBuffer(10_000);
        if (inIdx < 0) return;
        if (eos) {
            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return;
        }
        Image image = codec.getInputImage(inIdx);
        if (image == null) {
            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0);
            return;
        }
        fillImage(image, rgba, w, h);
        codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, ptsUs, 0);
    }

    private static void fillImage(Image image, int[] rgba, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < planes.length && p < 3; p++) {
            ByteBuffer buffer = planes[p].getBuffer();
            int rowStride = planes[p].getRowStride();
            int pixelStride = planes[p].getPixelStride();
            int step = (p == 0) ? 1 : 2;
            int rows = (p == 0) ? h : h / 2;
            int cols = (p == 0) ? w : w / 2;
            for (int y = 0; y < rows; y++) {
                int srcRow = y * step * w;
                int pos = y * rowStride;
                for (int x = 0; x < cols; x++) {
                    int px = rgba[srcRow + x * step];
                    int r = (px >> 16) & 0xFF;
                    int g = (px >> 8) & 0xFF;
                    int b = px & 0xFF;
                    int value;
                    if (p == 0) value = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    else if (p == 1) value = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    else value = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    buffer.put(pos + x * pixelStride, (byte) value);
                }
            }
        }
    }

    private void drain(MediaCodec codec, MediaMuxer muxer, int[] track, boolean[] started,
                       boolean[] eosSeen, long timeoutUs, int maxPasses) {
        int passes = 0;
        while (passes++ < maxPasses && !eosSeen[0]) {
            int outIdx = codec.dequeueOutputBuffer(mBufferInfo, timeoutUs);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (timeoutUs == 0) return;
                continue;
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                started[0] = true;
                continue;
            }
            if (outIdx >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(outIdx);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) mBufferInfo.size = 0;
                if (started[0] && buffer != null && mBufferInfo.size > 0) {
                    buffer.position(mBufferInfo.offset);
                    buffer.limit(mBufferInfo.offset + mBufferInfo.size);
                    muxer.writeSampleData(track[0], buffer, mBufferInfo);
                }
                boolean eos = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outIdx, false);
                if (eos) eosSeen[0] = true;
            } else {
                return;
            }
        }
    }

    // ── Output files, shared by both engines ─────────────────────────────────

    private static File prepareOutputFile(Context context, boolean gallery) {
        String name = "Cubic_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (gallery) {
            if (Build.VERSION.SDK_INT >= 29) return new File(context.getCacheDir(), name);
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                if (movies.isDirectory() || movies.mkdirs()) return new File(movies, name);
            }
        }
        File dir = getRecordingsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) return new File(context.getCacheDir(), name);
        return new File(dir, name);
    }

    static void publishRecording(Context context, File recorded, boolean gallery) {
        if (!gallery) return;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, recorded.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Cubic");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(recorded)) {
                    if (out != null) copy(in, out);
                    values.clear();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                    recorded.delete();
                    toastStatic(context, R.string.recorder_saved_gallery);
                    return;
                } catch (IOException e) {
                    context.getContentResolver().delete(uri, null, null);
                }
            }
        } else {
            MediaScannerConnection.scanFile(context, new String[]{recorded.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            toastStatic(context, R.string.recorder_saved_gallery);
            return;
        }
        File fallback = new File(getRecordingsDir(), recorded.getName());
        try (InputStream in = new FileInputStream(recorded);
             OutputStream out = new FileOutputStream(fallback)) {
            copy(in, out);
            recorded.delete();
        } catch (IOException ignored) {}
        toastStatic(context, R.string.recorder_saved_private);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private static void toastStatic(Context context, int resId) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, resId, Toast.LENGTH_SHORT).show());
    }
}