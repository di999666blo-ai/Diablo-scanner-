package com.diablo.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class AIModelSetup {

    private static final String PREFS = "diablo_scanner_ai";
    private static final String TEXT_MODEL = "text_model_path";
    private static final String IMAGE_MODEL = "image_model_path";

    private AIModelSetup() {
    }

    public static void chooseTextModel(Activity activity) {
        chooseFile(activity, 7201);
    }

    public static void chooseImageModel(Activity activity) {
        chooseFile(activity, 7202);
    }

    private static void chooseFile(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(
                activity,
                "Could not open the file picker.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    public static boolean handleResult(
            Activity activity,
            int requestCode,
            int resultCode,
            Intent data) {

        if (requestCode != 7201 && requestCode != 7202) {
            return false;
        }

        if (resultCode != Activity.RESULT_OK ||
                data == null ||
                data.getData() == null) {
            Toast.makeText(
                activity,
                "No model file selected.",
                Toast.LENGTH_SHORT
            ).show();
            return true;
        }

        Uri uri = data.getData();

        String destinationName =
                requestCode == 7201
                ? "diablo-text-model.gguf"
                : "diablo-image-projector.gguf";

        try {
            File destination = new File(
                activity.getFilesDir(),
                destinationName
            );

            try (
                InputStream input =
                    activity.getContentResolver()
                        .openInputStream(uri);
                OutputStream output =
                    new FileOutputStream(destination)
            ) {
                if (input == null) {
                    throw new Exception("Cannot read selected file.");
                }

                byte[] buffer = new byte[1024 * 1024];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                output.flush();
            }

            SharedPreferences prefs =
                activity.getSharedPreferences(
                    PREFS,
                    Activity.MODE_PRIVATE
                );

            String key =
                    requestCode == 7201
                    ? TEXT_MODEL
                    : IMAGE_MODEL;

            prefs.edit()
                .putString(key, destination.getAbsolutePath())
                .apply();

            Toast.makeText(
                activity,
                "Model file copied into app storage.",
                Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                activity,
                "Could not copy the model. Check free storage space.",
                Toast.LENGTH_LONG
            ).show();
        }

        return true;
    }

    public static boolean areBothModelsSelected(Activity activity) {
        SharedPreferences prefs =
            activity.getSharedPreferences(
                PREFS,
                Activity.MODE_PRIVATE
            );

        String textModel = prefs.getString(TEXT_MODEL, "");
        String imageModel = prefs.getString(IMAGE_MODEL, "");

        return textModel != null &&
               imageModel != null &&
               new File(textModel).exists() &&
               new File(imageModel).exists();
    }

    public static String getTextModelPath(Activity activity) {
        return activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        ).getString(TEXT_MODEL, "");
    }

    public static String getImageModelPath(Activity activity) {
        return activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        ).getString(IMAGE_MODEL, "");
    }
}
