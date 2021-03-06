/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.display.WifiDisplayStatus;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.UserHandle;
import android.location.LocationManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.telephony.Phone;
import com.android.internal.view.RotationPolicy;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.statusbar.policy.CurrentUserTracker;
import com.android.systemui.statusbar.policy.LocationController.LocationGpsStateChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

import org.teameos.jellybean.settings.EOSConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        LocationGpsStateChangeCallback,
        BrightnessStateChangeCallback {

    // Sett InputMethoManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
    }

    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }

    static class RSSIState extends State {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
    }

    static class WifiState extends State {
        String signalContentDescription;
        boolean connected;
    }

    static class UserState extends State {
        Drawable avatar;
    }

    static class BrightnessState extends State {
        boolean autoBrightness;
    }

    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }

    public static class LteState extends State {
        int settingsNetworkMode;
        public static final int DEFAULT_MODE = Phone.PREFERRED_NT_MODE;
        public static final int CDMA_ONLY = Phone.NT_MODE_CDMA;
        public static final int LTE_CDMA = Phone.NT_MODE_GLOBAL;
        public static final String EOS_TELEPHONY_INTENT = EOSConstants.INTENT_TELEPHONY_LTE_TOGGLE;
        public static final String EOS_TELEPHONY_MODE_KEY = EOSConstants.INTENT_TELEPHONY_LTE_TOGGLE_KEY;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** ContentObserver to determine the next alarm */
    private class NextAlarmObserver extends ContentObserver {
        public NextAlarmObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onNextAlarmChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to watch adb */
    private class BugreportObserver extends ContentObserver {
        public BugreportObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBugreportChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.BUGREPORT_IN_POWER_MENU), false, this);
        }
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {
        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }
    
    private class NetworkStateObserver extends ContentObserver {
        public NetworkStateObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshLteTile();
            refresh2g3gTile();
        }
        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Global.PREFERRED_NETWORK_MODE),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final NextAlarmObserver mNextAlarmObserver;
    private final BugreportObserver mBugreportObserver;
    private final BrightnessObserver mBrightnessObserver;

    private List<String> mEnabledTiles;

    private QuickSettingsTileView mTorchTile;
    private RefreshCallback mTorchCallback;
    private State mTorchState = new State();

    private QuickSettingsTileView mWifiApTile;
    private RefreshCallback mWifiApCallback;
    private State mWifiApState = new State();

    private QuickSettingsTileView mRingerTile;
    private RefreshCallback mRingerCallback;
    private State mRingerState = new State();

    private QuickSettingsTileView mSeekbarTile;
    private RefreshCallback mSeekbarCallback;
    private State mSeekbarState = new State();

    private QuickSettingsTileView mVolSeekbarTile;
    private RefreshCallback mVolSeekbarCallback;
    private State mVolSeekbarState = new State();

    private QuickSettingsTileView mBrightSeekbarTile;
    private RefreshCallback mBrightSeekbarCallback;
    private State mBrightSeekbarState = new State();

    private QuickSettingsTileView mScreenTile;
    private RefreshCallback mScreenCallback;
    private State mScreenState = new State();

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private RefreshCallback mWifiCallback;
    private WifiState mWifiState = new WifiState();

    private QuickSettingsTileView mWifiDisplayTile;
    private RefreshCallback mWifiDisplayCallback;
    private State mWifiDisplayState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mBluetoothTile;
    private RefreshCallback mBluetoothCallback;
    private BluetoothState mBluetoothState = new BluetoothState();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private State mRotationLockState = new State();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mLteTile;
    private RefreshCallback mLteCallback;
    private LteState mLteState = new LteState();

    private QuickSettingsTileView m2g3gTile;
    private RefreshCallback m2g3gCallback;
    private State m2g3gState = new State();

    private QuickSettingsTileView mSyncTile;
    private RefreshCallback mSyncCallback;
    private State mSyncState = new State();

    private final String AVATAR = EOSConstants.SYSTEMUI_PANEL_USER_TILE;
    private final String SETTINGS = EOSConstants.SYSTEMUI_PANEL_SETTINGS_TILE;
    private final String SEEKBAR = EOSConstants.SYSTEMUI_PANEL_SEEKBAR_TILE;
    private final String BRIGHT_SEEKBAR = EOSConstants.SYSTEMUI_PANEL_BRIGHT_SEEKBAR_TILE;
    private final String VOL_SEEKBAR = EOSConstants.SYSTEMUI_PANEL_VOL_SEEKBAR_TILE;
    private final String BATTERY = EOSConstants.SYSTEMUI_PANEL_BATTERY_TILE;
    private final String ROTATION = EOSConstants.SYSTEMUI_PANEL_ROTATION_TILE;
    private final String AIRPLANE = EOSConstants.SYSTEMUI_PANEL_AIRPLANE_TILE;
    private final String WIFI = EOSConstants.SYSTEMUI_PANEL_WIFI_TILE;
    private final String DATA = EOSConstants.SYSTEMUI_PANEL_DATA_TILE;
    private final String BT = EOSConstants.SYSTEMUI_PANEL_BT_TILE;
    private final String SCREEN = EOSConstants.SYSTEMUI_PANEL_SCREENOFF_TILE;
    private final String LOCATION = EOSConstants.SYSTEMUI_PANEL_LOCATION_TILE;
    private final String RINGER = EOSConstants.SYSTEMUI_PANEL_RINGER_TILE;
    private final String WIFIAP = EOSConstants.SYSTEMUI_PANEL_WIFIAP_TILE;
    private final String TORCH = EOSConstants.SYSTEMUI_PANEL_TORCH_TILE;
    private final String LTE = EOSConstants.SYSTEMUI_PANEL_LTE_TILE;
    private final String TWOGEEZ = EOSConstants.SYSTEMUI_PANEL_2G3G_TILE;
    private final String BRIGHTNESS = EOSConstants.SYSTEMUI_PANEL_BRIGHTNESS_TILE;
    private final String SYNC = EOSConstants.SYSTEMUI_PANEL_SYNC_TILE;
    private final String INTENT_UPDATE_TORCH_TILE = EOSConstants.SYSTEMUI_PANEL_TORCH_INTENT;
    private final String INTENT_UPDATE_VOLUME_OBSERVER_STREAM = EOSConstants.SYSTEMUI_PANEL_VOLUME_OBSERVER_STREAM_INTENT;

    private VolumeObserver mVolumeObserver;
    private NetworkStateObserver mNetworkObserver;
    private boolean mHasMobileData = false;

    // keep aosp constructor just in case
    public QuickSettingsModel(Context context) {
        this(context, null);
    }

    public QuickSettingsModel(Context context, List<String> tiles) {
        mContext = context;

        if (tiles == null) {
            mEnabledTiles = Arrays.asList(EOSConstants.SYSTEMUI_PANEL_DEFAULTS);
        } else {
            mEnabledTiles = tiles;
        }

        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onReceive(Context context, Intent intent) {
                super.onReceive(context, intent);
                onUserSwitched();
            }
        };

        mNextAlarmObserver = new NextAlarmObserver(mHandler);
        mNextAlarmObserver.startObserving();
        mBugreportObserver = new BugreportObserver(mHandler);
        mBugreportObserver.startObserving();
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();
        if (isToggleEnabled(LTE) || isToggleEnabled(TWOGEEZ)) {
            mNetworkObserver = new NetworkStateObserver(mHandler);
            mNetworkObserver.startObserving();
        }

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);

        IntentFilter tileFilter = new IntentFilter();
        tileFilter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        tileFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        tileFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        tileFilter.addAction(INTENT_UPDATE_TORCH_TILE);
        tileFilter.addAction(Intent.ACTION_HEADSET_PLUG);
        tileFilter.addAction(INTENT_UPDATE_VOLUME_OBSERVER_STREAM);
        context.registerReceiver(tileReceiver, tileFilter);

        mVolumeObserver = new VolumeObserver(new Handler());
        mContext.getContentResolver().registerContentObserver(
                volumeStreamUri(QuickSettings.mVolumeStream), true, mVolumeObserver);

        MobileDataObserver mdo = new MobileDataObserver(new Handler());
        mContext.getContentResolver()
                .registerContentObserver(Settings.Global.getUriFor(Settings.Global.MOBILE_DATA),
                        false, mdo);

        mHasMobileData = deviceSupportsTelephony();
    }

    public void removeReceivers() {
        mContext.unregisterReceiver(mAlarmIntentReceiver);
        mContext.unregisterReceiver(tileReceiver);
    }

    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBrightnessTile();
        refreshBluetoothTile();
        refreshRotationLockTile();
    }

    boolean isToggleEnabled(String toggle) {
        return mEnabledTiles.contains(toggle);
    }

    // Torch
    void addTorchTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTorchTile = view;
        mTorchCallback = cb;
        mTorchCallback.refreshView(mTorchTile, mTorchState);
    }

    // Wifi Ap
    void addWifiApTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiApTile = view;
        mWifiApCallback = cb;
        mWifiApCallback.refreshView(mWifiApTile, mWifiApState);
    }

    // Ringer
    void addRingerTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRingerTile = view;
        mRingerCallback = cb;
        mRingerCallback.refreshView(mRingerTile, mRingerState);
    }

    // Seekbar
    void addSeekbarTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSeekbarTile = view;
        mSeekbarCallback = cb;
        mSeekbarCallback.refreshView(mSeekbarTile, mSeekbarState);
    }

    // Seekbar
    void addVolSeekbarTile(QuickSettingsTileView view, RefreshCallback cb) {
        mVolSeekbarTile = view;
        mVolSeekbarCallback = cb;
        mVolSeekbarCallback.refreshView(mVolSeekbarTile, mVolSeekbarState);
    }

    // Seekbar
    void addBrightSeekbarTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightSeekbarTile = view;
        mBrightSeekbarCallback = cb;
        mBrightSeekbarCallback.refreshView(mBrightSeekbarTile, mBrightSeekbarState);
    }

    // Screen
    void addScreenTile(QuickSettingsTileView view, RefreshCallback cb) {
        mScreenTile = view;
        mScreenCallback = cb;
        mScreenCallback.refreshView(mScreenTile, mScreenState);
    }

    // LTE
    void addLteTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLteTile = view;
        mLteCallback = cb;
        refreshLteTile();
    }

    // GSM 2g only
    void add2g3gTile(QuickSettingsTileView view, RefreshCallback cb) {
        m2g3gTile = view;
        m2g3gCallback = cb;
        refresh2g3gTile();
    }

    // Sync
    void addSyncTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSyncTile = view;
        mSyncCallback = cb;
        refreshSyncTile();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }

    void refreshSettingsTile() {
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        if (isToggleEnabled(SETTINGS)) {
            mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
        }
    }

    void refreshLteTile() {
        mLteState.settingsNetworkMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE,
                Phone.PREFERRED_NT_MODE);
        if (isToggleEnabled(LTE)) {
            mLteCallback.refreshView(mLteTile, mLteState);
        }
    }

    void refresh2g3gTile() {
        // keeps me from adding telephony-common
        // default gsm mode Phone.NT_MODE_WCDMA_PREF = 0;
        // gsm only Phone.NT_MODE_GSM_ONLY = 1;
        final int GSM_DEFAULT = 0;
        final int GSM_ONLY = 1;
        int currentMode = android.provider.Settings.Secure.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE, GSM_DEFAULT);
        m2g3gState.enabled = currentMode == GSM_ONLY ? true : false;
        if (isToggleEnabled(TWOGEEZ)) {
            m2g3gCallback.refreshView(m2g3gTile, m2g3gState);
        }
    }

    public void refreshSyncTile() {
        mSyncState.enabled = ContentResolver.getMasterSyncAutomatically();
        if (isToggleEnabled(SYNC)) {
            mSyncCallback.refreshView(mSyncTile, mSyncState);
        }
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }

    void onAlarmChanged(Intent intent) {
        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    void onNextAlarmChanged() {
        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }

    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }

    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        if (isToggleEnabled(AIRPLANE)) {
            mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
        }
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }

    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null)
            return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null)
            return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            string.substring(0, length - 1);
        }
        return string;
    }

    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);
        }
        if (isToggleEnabled(WIFI)) {
            mWifiCallback.refreshView(mWifiTile, mWifiState);
        }
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }

    boolean deviceSupportsTelephony() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, String dataContentDescription, String enabledDesc) {
        if (mHasMobileData) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataTypeIconId
                    : 0;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0)
                    && !mWifiState.enabled
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            if (isToggleEnabled(DATA)) {
                mRSSICallback.refreshView(mRSSITile, mRSSIState);
            }
        }
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }

    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }

    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }

    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r
                        .getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r
                        .getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }
        if (isToggleEnabled(BT)) {
            mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);
        }
    }

    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        if (isToggleEnabled(BATTERY)) {
            mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
        }
    }

    void refreshBatteryTile() {
        if (isToggleEnabled(BATTERY)) {
            mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
        }
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    // LocationController callback
    @Override
    public void onLocationGpsStateChanged(boolean inUse, String description) {
        mLocationState.enabled = inUse;
        mLocationState.label = description;
        // mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }

    // SettingsObserver callback
    public void onBugreportChanged() {
        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Secure.getInt(cr, Settings.Secure.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled;
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Wifi Display
    void addWifiDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiDisplayTile = view;
        mWifiDisplayCallback = cb;
    }

    public void onWifiDisplayStateChanged(WifiDisplayStatus status) {
        mWifiDisplayState.enabled =
                (status.getFeatureState() == WifiDisplayStatus.FEATURE_STATE_ON);
        if (status.getActiveDisplay() != null) {
            mWifiDisplayState.label = status.getActiveDisplay().getFriendlyDisplayName();
            mWifiDisplayState.iconId = R.drawable.ic_qs_remote_display_connected;
        } else {
            mWifiDisplayState.label = mContext.getString(
                    R.string.quick_settings_wifi_display_no_connection_label);
            mWifiDisplayState.iconId = R.drawable.ic_qs_remote_display;
        }
        mWifiDisplayCallback.refreshView(mWifiDisplayTile, mWifiDisplayState);

    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(mImeTile, mImeState);
    }

    /*
     * This implementation is taken from
     * InputMethodManagerService.needsToShowImeSwitchOngoingNotification().
     */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2)
            return true;
        if (N < 1)
            return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for (int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }

    void onImeWindowStatusChanged(boolean visible) {
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if (mImeCallback != null) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }

    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null)
            return null;
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId))
            return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : context.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        onRotationLockChanged();
    }

    void onRotationLockChanged() {
        boolean locked = RotationPolicy.isRotationLocked(mContext);
        mRotationLockState.enabled = locked;
        mRotationLockState.iconId = locked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = locked
                ? mContext.getString(R.string.quick_settings_rotation_locked_label)
                : mContext.getString(R.string.quick_settings_rotation_unlocked_label);

        // may be called before addRotationLockTile due to
        // RotationPolicyListener in QuickSettings
        if (mRotationLockTile != null && mRotationLockCallback != null) {
            mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
        }
    }

    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {	
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }

    @Override
    public void onBrightnessLevelChanged() {
        if (isToggleEnabled(SEEKBAR)) {
            mSeekbarCallback.refreshView(mSeekbarTile, mSeekbarState);
        }

        if (isToggleEnabled(BRIGHT_SEEKBAR)) {
            mBrightSeekbarCallback.refreshView(mBrightSeekbarTile, mBrightSeekbarState);
        }

        if (isToggleEnabled(BRIGHTNESS)) {
            Resources r = mContext.getResources();
            int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    mUserTracker.getCurrentUserId());
            mBrightnessState.autoBrightness =
                    (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            mBrightnessState.iconId = mBrightnessState.autoBrightness
                    ? R.drawable.ic_qs_brightness_auto_on
                    : R.drawable.ic_qs_brightness_auto_off;
            mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
            mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
        }
    }

    void refreshBrightnessTile() {
        onBrightnessLevelChanged();
    }

    // User switch: need to update visuals of all tiles known to have per-user
    // state
    void onUserSwitched() {
        mBrightnessObserver.startObserving();
        onRotationLockChanged();
        onBrightnessLevelChanged();
        onNextAlarmChanged();
        onBugreportChanged();
    }

    private Uri volumeStreamUri(int stream) {
        final AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        Uri streamUri = null;

        if (stream == AudioManager.STREAM_RING) {
            streamUri = Uri.parse("content://settings/system/volume_ring_speaker");
        } else if (stream == AudioManager.STREAM_MUSIC) {
            if (!am.isWiredHeadsetOn()) {
                streamUri = Uri.parse("content://settings/system/volume_music_speaker");
            } else {
                streamUri = Uri.parse("content://settings/system/volume_music_headphone");
            }
        }

        return streamUri;
    }

    private final BroadcastReceiver tileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(LocationManager.PROVIDERS_CHANGED_ACTION)) {
                if (isToggleEnabled(LOCATION)) {
                    mLocationCallback.refreshView(mLocationTile, mLocationState);
                }
            } else if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                if (isToggleEnabled(RINGER)) {
                    mRingerCallback.refreshView(mRingerTile, mRingerState);
                }
            } else if (intent.getAction().equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                if (isToggleEnabled(WIFIAP)) {
                    mWifiApCallback.refreshView(mWifiApTile, mWifiApState);
                }
            } else if (intent.getAction().equals(INTENT_UPDATE_TORCH_TILE)) {
                if (isToggleEnabled(TORCH)) {
                    mTorchCallback.refreshView(mTorchTile, mTorchState);
                }
            } else if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)
                    || intent.getAction().equals(INTENT_UPDATE_VOLUME_OBSERVER_STREAM)) {
                if (isToggleEnabled(SEEKBAR)) {
                    mSeekbarCallback.refreshView(mSeekbarTile, mSeekbarState);
                    mContext.getContentResolver().unregisterContentObserver(mVolumeObserver);
                    mContext.getContentResolver().registerContentObserver(
                            volumeStreamUri(QuickSettings.mVolumeStream), true, mVolumeObserver);
                }
                if (isToggleEnabled(VOL_SEEKBAR)) {
                    mVolSeekbarCallback.refreshView(mVolSeekbarTile, mVolSeekbarState);
                    mContext.getContentResolver().unregisterContentObserver(mVolumeObserver);
                    mContext.getContentResolver().registerContentObserver(
                            volumeStreamUri(QuickSettings.mVolumeStream), true, mVolumeObserver);
                }
            }
        }
    };

    private class VolumeObserver extends ContentObserver {
        public VolumeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return super.deliverSelfNotifications();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (isToggleEnabled(SEEKBAR)) {
                mSeekbarCallback.refreshView(mSeekbarTile, mSeekbarState);
            }
            if (isToggleEnabled(VOL_SEEKBAR)) {
                mVolSeekbarCallback.refreshView(mVolSeekbarTile, mVolSeekbarState);
            }
        }
    }

    private class MobileDataObserver extends ContentObserver {
        public MobileDataObserver(Handler handler) {
            super(handler);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return super.deliverSelfNotifications();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (isToggleEnabled(DATA)) {
                mRSSICallback.refreshView(mRSSITile, mRSSIState);
            }
        }
    }
}
