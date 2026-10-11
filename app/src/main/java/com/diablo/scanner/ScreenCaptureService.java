package com.diablo.scanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public static final String ACTION_STOP_CAPTURE =
            "com.diablo.scanner.STOP_CAPTURE";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "diablo_screen_capture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private boolean captureStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DIABLO Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription(
                    "Notification for DIABLO screen capture"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("DIABLO SCANNER")
                .setContentText("Screen capture is running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void startCaptureNotification() {
        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP_CAPTURE.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (captureStarted) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                0
        );

        Intent resultData;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent.class
            );
        } else {
            resultData = intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
            );
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCaptureNotification();

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);

            if (manager == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            projection = manager.getMediaProjection(
                    resultCode,
                    resultData
            );

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            startCapture();

        } catch (Exception e) {
            stopCapture();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startCapture() {

        if (projection == null || captureStarted) {
            return;
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        if (width <= 0 || height <= 0) {
            stopSelf();
            return;
        }

        workerThread = new HandlerThread("DiabloCaptureWorker");
        workerThread.start();

        workerHandler = new Handler(workerThread.getLooper());

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        /*
         * Register the callback before creating the virtual display.
         */
        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopCapture();
                        stopSelf();
                    }
                },
                workerHandler
        );

        imageReader.setOnImageAvailableListener(
                this::processCapturedFrame,
                workerHandler
        );

        virtualDisplay = projection.createVirtualDisplay(
                "DIABLO_SCREEN",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                workerHandler
        );

        if (virtualDisplay == null) {
            stopCapture();
            stopSelf();
            return;
        }

        captureStarted = true;
    }

    private void processCapturedFrame(ImageReader reader) {

        Image image = null;
        Bitmap fullBitmap = null;
        Bitmap croppedBitmap = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            Image.Plane[] planes = image.getPlanes();

            if (planes == null || planes.length == 0) {
                return;
            }

            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();

            if (pixelStride <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return;
            }

            int rowPadding =
                    rowStride - pixelStride * imageWidth;

            int paddedWidth =
                    imageWidth + Math.max(0, rowPadding / pixelStride);

            fullBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    imageHeight,
                    Bitmap.Config.ARGB_8888
            );

            buffer.rewind();
            fullBitmap.copyPixelsFromBuffer(buffer);

            croppedBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    imageWidth,
                    imageHeight
            );

            /*
             * NEXT STEP:
             * Connect croppedBitmap to the DIABLO chart-analysis engine.
             *
             * This version captures screen frames but does not yet
             * produce trading signals from those frames.
             */

        } catch (Exception ignored) {
            // Ignore a failed frame and continue capturing.
        } finally {

            if (image != null) {
                image.close();
            }

            if (croppedBitmap != null && !croppedBitmap.isRecycled()) {
                croppedBitmap.recycle();
            }

            if (fullBitmap != null && !fullBitmap.isRecycled()) {
                fullBitmap.recycle();
            }
        }
    }

    private void stopCapture() {

        captureStarted = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            projection.stop();
            projection = null;
        }

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
