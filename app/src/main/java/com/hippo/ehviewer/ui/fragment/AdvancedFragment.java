/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.wifi.WiFiClientActivity;
import com.hippo.ehviewer.ui.wifi.WiFiServerActivity;
import com.hippo.ehviewer.widget.ProgressHelper;
import com.hippo.util.LogCat;

import java.io.File;
import java.util.Arrays;

public class AdvancedFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    public static final int DB_LOADING = 0;
    public static final int DB_LOAD_FINISH = 1;

    public static final String LOADING_STATUS = "loading_status";
    public static final String LOADING_PROGRESS = "loading_progress";

    private static final String KEY_DUMP_LOGCAT = "dump_logcat";
    private static final String KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_IMPORT_DATA = "import_data";
    private static final String KEY_WIFI_SERVER = "wifi_server";
    private static final String KEY_WIFI_CLIENT = "wifi_client";

    private final DbSyncHandle dbSyncHandle = new DbSyncHandle(Looper.getMainLooper());

    private Context context;

    /** ★ 个性化改造：日志按钮，用来动态显示「开始录制 / 停止录制」 */
    private Preference dumpLogcatPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        context = getContext();
        addPreferencesFromResource(R.xml.advanced_settings);

        Preference dumpLogcat = findPreference(KEY_DUMP_LOGCAT);
        dumpLogcatPref = dumpLogcat;
        Preference clearMemoryCache = findPreference(KEY_CLEAR_MEMORY_CACHE);
        Preference appLanguage = findPreference(KEY_APP_LANGUAGE);
        Preference importData = findPreference(KEY_IMPORT_DATA);
        Preference socketData = findPreference(KEY_WIFI_SERVER);
        Preference clientData = findPreference(KEY_WIFI_CLIENT);

        dumpLogcat.setOnPreferenceClickListener(this);
        clearMemoryCache.setOnPreferenceClickListener(this);
        importData.setOnPreferenceClickListener(this);
        socketData.setOnPreferenceClickListener(this);
        clientData.setOnPreferenceClickListener(this);

        appLanguage.setOnPreferenceChangeListener(this);
        refreshLogcatPref();
    }

    /** 按录制状态刷新日志项的标题与摘要 */
    private void refreshLogcatPref() {
        if (dumpLogcatPref == null) {
            return;
        }
        boolean recording = LogCat.isRecording();
        dumpLogcatPref.setTitle(recording
                ? R.string.settings_advanced_logcat_stop
                : R.string.settings_advanced_dump_logcat);
        dumpLogcatPref.setSummary(recording
                ? R.string.settings_advanced_logcat_recording_summary
                : R.string.settings_advanced_dump_logcat_summary);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLogcatPref();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case KEY_DUMP_LOGCAT:
                return dumpLogcat();
            case KEY_CLEAR_MEMORY_CACHE:
                return clearMemoryCache();
            case KEY_IMPORT_DATA:
                importData(getActivity());
                getActivity().setResult(Activity.RESULT_OK);
                return true;
            case KEY_WIFI_SERVER:
                return gotoWiFiServerActivity();
            case KEY_WIFI_CLIENT:
                return gotoWiFiClientActivity();
            default:
                return false;
        }
    }

    private boolean gotoWiFiClientActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiClientActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean gotoWiFiServerActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiServerActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean clearMemoryCache() {
        ((EhApplication) getActivity().getApplication()).clearMemoryCache();
        Runtime.getRuntime().gc();
        return false;
    }

    /**
     * ★ 个性化改造：日志按钮改为「开始录制 / 停止并导出」两态。
     *
     * 原来只有一次性 dump（logcat -d），只能拿到系统环形缓冲区里的 10~15 秒。
     * 现在改成流式录制：只订阅 SpiderQueen / EhSSLSocketFactory / EhEngine，
     * 边读边落盘，不受缓冲区大小限制；下载全程都能留下。
     *
     * 首次点击 = 开始录制；再次点击 = 停止并把文件路径告诉你。
     */
    private boolean dumpLogcat() {
        Resources resources = getResources();
        File dir = AppConfig.getExternalLogcatDir();

        if (LogCat.isRecording()) {
            LogCat.stopRecording();
            refreshLogcatPref();
            File f = LogCat.getLastFile();
            Toast.makeText(getActivity(),
                    f != null ? resources.getString(R.string.settings_advanced_dump_logcat_to, f.getPath())
                              : resources.getString(R.string.settings_advanced_dump_logcat_failed),
                    Toast.LENGTH_LONG).show();
            return true;
        }

        if (dir == null) {
            Toast.makeText(getActivity(), resources.getString(R.string.settings_advanced_dump_logcat_failed),
                    Toast.LENGTH_SHORT).show();
            return true;
        }

        boolean ok = LogCat.startRecording(dir);
        refreshLogcatPref();
        Toast.makeText(getActivity(),
                ok ? resources.getString(R.string.settings_advanced_logcat_recording_started, dir.getPath())
                   : resources.getString(R.string.settings_advanced_dump_logcat_failed),
                Toast.LENGTH_LONG).show();
        return true;
    }

    private boolean importData(final Context context) {
        final File dir = AppConfig.getExternalDataDir();
        if (null == dir) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        final String[] files = dir.list();
        if (null == files || files.length <= 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return false;
        }
        Arrays.sort(files);
        new AlertDialog.Builder(context).setItems(files, (dialog, which) -> {
            dialog.dismiss();
            showProgress(context, dir, files, which);
        }).show();
        return false;
    }

    private void showProgress(final Context context, File dir, String[] files, int which) {

        File file = new File(dir, files[which]);
        ProgressHelper.showDialog(context, context.getString(R.string.loading_db_file));
        new Thread(
                () -> {
                    String error = EhDB.importDB(context, file, dbSyncHandle);
                    Message message = new Message();
                    Bundle bundle = new Bundle();
                    bundle.putString("error", error);
                    bundle.putInt(LOADING_STATUS, DB_LOAD_FINISH);
                    message.setData(bundle);
                    dbSyncHandle.sendMessage(message);
                }
        ).start();


    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_APP_LANGUAGE.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        }
        return false;
    }

    private class DbSyncHandle extends Handler {
        public DbSyncHandle(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Bundle data = msg.getData();
            int state = data.getInt(LOADING_STATUS);
            if (state == DB_LOAD_FINISH){
                ProgressHelper.dismissDialog();
                String error = data.getString("error");
                if (context == null) {
                    return;
                }
                if (null == error) {
                    error = context.getString(R.string.settings_advanced_import_data_successfully);
                }

                Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
            } else if (state == DB_LOADING) {
                ProgressHelper.setProgress(data.getInt(LOADING_PROGRESS,0));
            }

        }
    }
}
