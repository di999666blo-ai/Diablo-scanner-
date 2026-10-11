package com.diablo.scanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler workerHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        startCaptureNotification();
    }

    private void startCaptureNotification() {
        String channelId = "diablo_screen_capture";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            channelId,
                            "DIABLO Screen Capture",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(this, channelId)
                        : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("DIABLO SCANNER")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(1001, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent resultData;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent.class
            );
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);

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
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startCapture() {

        android.util.DisplayMetrics metrics =
                getResources().getDisplayMetrics();

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        workerThread = new HandlerThread("DiabloCaptureWorker");
        workerThread.start();

        workerHandler = new Handler(workerThread.getLooper());

        imageReader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2
        );

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

        imageReader.setOnImageAvailableListener(reader -> {

            Image image = null;

            try {
                image = reader.acquireLatestImage();

                if (image == null) {
                    return;
                }

                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();

                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();

                int rowPadding =
                        rowStride - pixelStride * width;

                Bitmap bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );

                bitmap.copyPixelsFromBuffer(buffer);

                Bitmap cropped = Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        width,
                        height
                );

                bitmap.recycle();

                /*
                 * NEXT STEP:
                 * Connect this captured screen image
                 * to the DIABLO chart-analysis engine.
                 *
                 * This code captures frames but does
                 * not yet generate live BUY or SELL signals.
                 */

                cropped.recycle();

            } catch (Exception ignored) {
                // Ignore individual frame errors.
            } finally {
                if (image != null) {
                    image.close();
                }
            }

        }, workerHandler);

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
    }

    private void stopCapture() {

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
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
