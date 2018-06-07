package com.allenliu.versionchecklib.v2.ui;

import android.app.IntentService;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.allenliu.versionchecklib.R;
import com.allenliu.versionchecklib.callback.DownloadListener;
import com.allenliu.versionchecklib.core.DownloadManager;
import com.allenliu.versionchecklib.core.PermissionDialogActivity;
import com.allenliu.versionchecklib.core.http.AllenHttp;
import com.allenliu.versionchecklib.core.http.HttpRequestMethod;
import com.allenliu.versionchecklib.utils.ALog;
import com.allenliu.versionchecklib.utils.AllenEventBusUtil;
import com.allenliu.versionchecklib.utils.AppUtils;
import com.allenliu.versionchecklib.v2.AllenVersionChecker;
import com.allenliu.versionchecklib.v2.builder.DownloadBuilder;
import com.allenliu.versionchecklib.v2.builder.RequestVersionBuilder;
import com.allenliu.versionchecklib.v2.builder.UIData;
import com.allenliu.versionchecklib.v2.callback.RequestVersionListener;
import com.allenliu.versionchecklib.v2.eventbus.AllenEventType;
import com.allenliu.versionchecklib.v2.eventbus.CommonEvent;
import com.allenliu.versionchecklib.v2.net.DownloadMangerV2;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class VersionService extends Service {
    private static final int JOB_ID = 100011;
    public static DownloadBuilder builder;
    private BuilderHelper builderHelper;
    private NotificationHelper notificationHelper;
    private boolean isServiceAlive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        ALog.e("version service create");
        if (builder == null)
            return;
        isServiceAlive = true;
        builderHelper = new BuilderHelper(getApplicationContext(), builder);
        notificationHelper = new NotificationHelper(getApplicationContext(), builder);
        new Thread() {
            public void run() {
                onHandleWork();
            }
        }.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ALog.e("version service destroy");
        builder = null;
        builderHelper = null;
        if (notificationHelper != null)
            notificationHelper.onDestroy();
        notificationHelper = null;
        isServiceAlive = false;
        AllenHttp.getHttpClient().dispatcher().cancelAll();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void enqueueWork(final Context context, final DownloadBuilder downloadBuilder) {
        //清除之前的任务，如果有
        AllenVersionChecker.getInstance().cancelAllMission(context);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (builder == null)
                    ALog.e("a1builder==null");
                builder = downloadBuilder;
                if (builder == null)
                    ALog.e("a2builder==null");
                Intent intent = new Intent(context, VersionService.class);
                context.startService(intent);
            }
        }, 500);


//        enqueueWork(context, VersionService.class, JOB_ID, new Intent());
    }


    protected void onHandleWork() {
        if (checkWhetherNeedRequestVersion()) {
            requestVersion();
        } else {
            downloadAPK();
        }
    }

    /**
     * 请求版本接口
     */
    private void requestVersion() {
        RequestVersionBuilder requestVersionBuilder = builder.getRequestVersionBuilder();
        OkHttpClient client = AllenHttp.getHttpClient();
        HttpRequestMethod requestMethod = requestVersionBuilder.getRequestMethod();
        Request request = null;
        switch (requestMethod) {
            case GET:
                request = AllenHttp.get(requestVersionBuilder).build();
                break;
            case POST:
                request = AllenHttp.post(requestVersionBuilder).build();
                break;
            case POSTJSON:
                request = AllenHttp.postJson(requestVersionBuilder).build();
                break;
        }
        final RequestVersionListener requestVersionListener = requestVersionBuilder.getRequestVersionListener();
        Handler handler = new Handler(Looper.getMainLooper());
        if (requestVersionListener != null) {
            try {
                final Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    final String result = response.body().string();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            UIData versionBundle = requestVersionListener.onRequestVersionSuccess(result);
                            builder.setVersionBundle(versionBundle);
                            downloadAPK();
                        }
                    });

                } else {
                    if (!isServiceAlive)
                        return;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            AllenVersionChecker.getInstance().cancelAllMission(getApplicationContext());
                            requestVersionListener.onRequestVersionFailure(response.message());
                        }
                    });
                }
            } catch (final IOException e) {
                e.printStackTrace();
                if (!isServiceAlive)
                    return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AllenVersionChecker.getInstance().cancelAllMission(getApplicationContext());
                        requestVersionListener.onRequestVersionFailure(e.getMessage());
                    }
                });

            }
        } else {
            throw new RuntimeException("using request version function,you must set a requestVersionListener");
        }
    }


    private boolean checkWhetherNeedRequestVersion() {
        if (builder.getRequestVersionBuilder() != null)
            return true;
        else
            return false;
    }

    private void downloadAPK() {
        if (builder.getVersionBundle() != null) {
            if (builder.isDirectDownload()) {
                AllenEventBusUtil.sendEventBus(AllenEventType.START_DOWNLOAD_APK);
            } else {
                if (builder.isSilentDownload()) {
                    requestPermissionAndDownload();
                } else {
                    showVersionDialog();
                }
            }
        } else {
            AllenVersionChecker.getInstance().cancelAllMission(getApplicationContext());
        }
    }


    /**
     * 开启UI展示界面
     */
    private void showVersionDialog() {
        Intent intent = new Intent(this, UIActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showDownloadingDialog() {
        if (builder.isShowDownloadingDialog()) {
            Intent intent = new Intent(this, DownloadingActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    private void updateDownloadingDialogProgress(int progress) {
        CommonEvent commonEvent = new CommonEvent();
        commonEvent.setEventType(AllenEventType.UPDATE_DOWNLOADING_PROGRESS);
        commonEvent.setData(progress);
        commonEvent.setSuccessful(true);
        EventBus.getDefault().post(commonEvent);
    }

    private void showDownloadFailedDialog() {
        Intent intent = new Intent(this, DownloadFailedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void requestPermissionAndDownload() {
        Intent intent = new Intent(this, PermissionDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void install() {
        AllenEventBusUtil.sendEventBus(AllenEventType.DOWNLOAD_COMPLETE);
        final String downloadPath = builder.getDownloadAPKPath() + VersionService.GetFileName(builder.getDownloadUrl());
        if (builder.isSilentDownload()) {
            showVersionDialog();
        } else {
            builderHelper.checkForceUpdate();
            AppUtils.installApk(getApplicationContext(), new File(downloadPath));
        }
    }

    public static String GetFileName(String url){
        if(url == null){
            return null;
        }
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        String postfix = "apk";
        if(path.lastIndexOf('.')!=-1){
            postfix = path.substring(path.lastIndexOf('.') + 1);
        }
        return String.format(Locale.getDefault(), "%d.%s", uri.hashCode(), postfix);
    }

    @WorkerThread
    private void startDownloadApk() {
        //判断是否缓存并且是否强制重新下载
        String downloadUrl = builder.getDownloadUrl();
        builderHelper.checkAndDeleteAPK();
        if (downloadUrl == null && builder.getVersionBundle() != null) {
            downloadUrl = builder.getVersionBundle().getDownloadUrl();
        }
        if (downloadUrl == null) {
            AllenVersionChecker.getInstance().cancelAllMission(getApplicationContext());
            throw new RuntimeException("you must set a download url for download function using");
        }
        final String downloadPath = builder.getDownloadAPKPath() + GetFileName(downloadUrl);
        if (DownloadManager.checkAPKIsExists(getApplicationContext(), downloadPath) && !builder.isForceRedownload()) {
            ALog.e("using cache");
            if (builder.getApkDownloadListener() != null)
                builder.getApkDownloadListener().onDownloadSuccess(new File(downloadPath));
            if(builder.isAutoInstall()){
                install();
            }
            return;
        }
        ALog.e("downloadPath:"+downloadPath);
        DownloadMangerV2.download(downloadUrl, builder.getDownloadAPKPath(), VersionService.GetFileName(builder.getDownloadUrl()), new DownloadListener() {
            @Override
            public void onCheckerDownloading(int progress) {
                if (isServiceAlive) {
                    if (!builder.isSilentDownload()) {
                        notificationHelper.updateNotification(progress);
                        updateDownloadingDialogProgress(progress);
                    }
                    if (builder.getApkDownloadListener() != null)
                        builder.getApkDownloadListener().onDownloading(progress);
                }
            }

            @Override
            public void onCheckerDownloadSuccess(File file) {
                if (isServiceAlive) {
                    if (!builder.isSilentDownload())
                        notificationHelper.showDownloadCompleteNotifcation(file);
                    if (builder.getApkDownloadListener() != null)
                        builder.getApkDownloadListener().onDownloadSuccess(file);
                    if(builder.isAutoInstall()){
                        install();
                    }
                }
            }

            @Override
            public void onCheckerDownloadFail() {

                if (!isServiceAlive)
                    return;
                if (builder.getApkDownloadListener() != null)
                    builder.getApkDownloadListener().onDownloadFail();

                if (!builder.isSilentDownload()) {
                    AllenEventBusUtil.sendEventBus(AllenEventType.CLOSE_DOWNLOADING_ACTIVITY);
                    if (builder.isShowDownloadFailDialog()) {
                        showDownloadFailedDialog();
                    }
                    notificationHelper.showDownloadFailedNotification();
                } else {
                    AllenVersionChecker.getInstance().cancelAllMission(getApplicationContext());
                }

            }

            @Override
            public void onCheckerStartDownload() {
                ALog.e("start download apk");
                if (!builder.isSilentDownload()) {
                    notificationHelper.showNotification();
                    showDownloadingDialog();
                }
            }
        });
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void receiveEvent(CommonEvent commonEvent) {
        switch (commonEvent.getEventType()) {
            case AllenEventType.START_DOWNLOAD_APK:
                requestPermissionAndDownload();
                break;
            case AllenEventType.REQUEST_PERMISSION:
                boolean permissionResult = (boolean) commonEvent.getData();
                if (permissionResult)
                    startDownloadApk();
                else
                    stopSelf();
                break;
        }

    }
}
