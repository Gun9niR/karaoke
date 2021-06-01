package com.sjtu.karaoke.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.arthenica.mobileffmpeg.FFmpeg;
import com.sjtu.karaoke.component.LoadingDialog;
import com.sjtu.karaoke.component.RateResultDialog;
import com.sjtu.karaoke.entity.Score;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.sjtu.karaoke.util.Constants.ALBUM_COVER_DIRECTORY;
import static com.sjtu.karaoke.util.Constants.GET_ALBUM_COVER_URL;
import static com.sjtu.karaoke.util.Constants.GET_RECORD_AUDIO;
import static com.sjtu.karaoke.util.Constants.GET_SONG_INFO_URL;
import static com.sjtu.karaoke.util.Constants.PACKAGES_FOR_SHARING;
import static com.sjtu.karaoke.util.Constants.PERMISSIONS_RECORDER;
import static com.sjtu.karaoke.util.FileUtil.saveFileFromResponse;

/*
 * @ClassName: Utils
 * @Author: guozh
 * @Date: 2021/3/28
 * @Version: v1.2
 * @Description: 工具方法类。用于减少项目中的重复代码。
 */

public class MiscUtil {
    private static Toast toast;

    public static Intent getChooserIntent(Uri uri, Context context) {
        List<LabeledIntent> targetedShareIntents = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        for (String packageName: PACKAGES_FOR_SHARING) {
            if (isPackageInstalled(packageName, context)) {
                targetedShareIntents.addAll(getShareIntents(pm, uri, packageName));
            }
        }

        Intent chooserIntent = Intent.createChooser(targetedShareIntents.remove(0), "分享录音");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedShareIntents.toArray(new LabeledIntent[ targetedShareIntents.size() ]));
        return chooserIntent;
    }

    public static List<LabeledIntent> getShareIntents(PackageManager pm, Uri uri, String packageName) {
        Intent dummy = new Intent(Intent.ACTION_SEND);
        dummy.setType("*/*");
        dummy.setPackage(packageName);

        List<ResolveInfo> info = pm.queryIntentActivities(dummy, 0);
        List<LabeledIntent> intents = new ArrayList<>();
        for (ResolveInfo i: info) {
            ActivityInfo activityInfo = i.activityInfo;
            // Ignore WeChat Timeline
            if (activityInfo.packageName.equals("com.tencent.mm") && activityInfo.name.equals("com.tencent.mm.ui.tools.ShareToTimeLineUI")) {
                continue;
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setType("*/*");
            intent.setClassName(activityInfo.packageName, activityInfo.name);
            intents.add(new LabeledIntent(intent, packageName, i.loadLabel(pm), i.icon));
        }

        return intents;
    }

    public static boolean isPackageInstalled(String packageName, Context context) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void verifyAllPermissions(Activity activity) {
        boolean permission = (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                || (ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                || (ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED);
        if (permission) {
            ActivityCompat.requestPermissions(activity, PERMISSIONS_RECORDER,
                    GET_RECORD_AUDIO);
        }
    }

    public static void showToast(Activity activity, String message) {
        activity.runOnUiThread(() -> {
            if (toast != null) {
                toast.cancel();
            }
            toast = Toast.makeText(activity, message, Toast.LENGTH_LONG);
            ViewGroup group = (ViewGroup) toast.getView();
            TextView tvMessage = (TextView) group.getChildAt(0);
            tvMessage.setText(message);
            tvMessage.setGravity(Gravity.CENTER);
            toast.show();
        });
    }

    public static LoadingDialog showLoadingDialog(Activity activity, String text) {
        return showLoadingDialog(activity, text, false);
    }

    /**
     * Display loading dialog, with provided text as hint
     *
     * @param activity
     * @param text
     */
    public static LoadingDialog showLoadingDialog(Activity activity, String text, boolean showProgress) {
        LoadingDialog loadingDialog = new LoadingDialog(activity, text, showProgress);

        loadingDialog.show();
        return loadingDialog;
    }

    /**
     * Display rate result dialog.
     * @param activity
     * @return
     */
    public static RateResultDialog showRateResultDialog(Activity activity, Score score, String instrumentScoreStr) {
        RateResultDialog rateResultDialog = new RateResultDialog(activity, score, instrumentScoreStr);
        rateResultDialog.show();
        return rateResultDialog;
    }

    public static void getSongInfo(Callback callback) {
        getRequest(GET_SONG_INFO_URL, callback);
    }

    /**
     * @param url      url to make get request to
     * @param callback
     */
    public static void getRequest(String url, Callback callback) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).get().build();
        client.newCall(request).enqueue(callback);
    }

    /**
     * Load image from given fullPath and set the image of given ImageView object
     *
     * @param fullPath
     * @param imageView
     */
    public static void setImageFromFile(String fullPath, ImageView imageView) {
        Bitmap bmp = BitmapFactory.decodeFile(fullPath);
        imageView.setImageBitmap(bmp);
    }

    public static String getRequestParamFromId(Integer id) {
        return "?id=" + id;
    }

    /**
     * Get name of the record file from song name
     * Naming strategy is: <songName>-<year>-<month>-<date>-<hour>-<minute>
     * Time is generated after merging pcm files to wav file
     * @param id
     * @param songName
     * @return Resulting record file name
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static String getRecordName(Integer id, String songName) {
        Date date = new Date(System.currentTimeMillis());
        SimpleDateFormat formatter = new SimpleDateFormat("-yyyy-MM-dd-HH-mm");
        String dateString = formatter.format(date);
        return id + "-" + songName + dateString + ".wav";
    }

    /**
     * Download album cover from the server.
     * @param id
     * @param songName
     * @param activity The activity that contains the ImageVIew
     * @param imageView The ImageView object to set the image
     */
    public static void downloadAndSetAlbumCover(Integer id, String songName, Activity activity, ImageView imageView) {
        getRequest(GET_ALBUM_COVER_URL + "?id=" + id, new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Error when downloading file", "Failed to download album cover for " + songName);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // receive and save the file
                String destPath = ALBUM_COVER_DIRECTORY + songName + ".png";
                if (response.isSuccessful()) {
                    saveFileFromResponse(response, destPath);

                    // set image, should run on UI thread
                    activity.runOnUiThread(() -> setImageFromFile(destPath, imageView));
                }
            }
        });
    }

    public static Integer[] parseScore(String scoreStr) {
        String[] scores = scoreStr.split(" ");
        return new Integer[] {
                Integer.parseInt(scores[0]),
                Integer.parseInt(scores[1]),
                Integer.parseInt(scores[2]),
                Integer.parseInt(scores[3]),
        };
    }

    public static String mergeNotesToChord(String chordName, List<String> notes) {
        String destPath = PathUtil.getChordWavFullPath(chordName);
        int noteNum = notes.size();

        StringBuilder command = new StringBuilder("-y ");
        for (String note: notes) {
            command.append("-i ").append(note).append(" ");
        }

        command.append("-filter_complex amix=inputs=").append(notes.size());
        command.append(":duration=longest,volume=").append(noteNum).append(" ");
        command.append(destPath);

        FFmpeg.execute(command.toString());

        return destPath;
    }


}
