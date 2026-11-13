package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the actual Provider gate; no root, system todo writes or device data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AppParcelProviderTest {
    private TodoProvider provider;
    private Context context;
    private static final int CAINIAO_UID = 10123;
    private static final int PDD_UID = 10456;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        install(AppParcelData.CAINIAO, CAINIAO_UID);
        install(AppParcelData.PINDUODUO, PDD_UID);
        provider = Robolectric.buildContentProvider(TodoProvider.class).create().get();
        ShadowBinder.setCallingUid(CAINIAO_UID);
    }

    private void install(String pkg, int uid) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.uid = uid;
        shadowOf(context.getPackageManager()).installPackage(info);
    }

    private void enable(boolean on) {
        context.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                .putBoolean(TodoProvider.APP_PARCEL_PREF, on).commit();
    }

    @Test public void defaultsOff() {
        assertFalse(provider.call("appParcelEnabled", AppParcelData.CAINIAO, null).getBoolean("enabled"));
    }

    @Test public void configChangesAreImmediateAtProvider() {
        enable(true);
        assertTrue(provider.call("appParcelEnabled", AppParcelData.CAINIAO, null).getBoolean("enabled"));
        enable(false);
        assertFalse(provider.call("appParcelEnabled", AppParcelData.CAINIAO, null).getBoolean("enabled"));
    }

    @Test public void callerCannotClaimOtherApp() {
        enable(true);
        assertEquals("unauthorized", provider.call("onAppParcel", AppParcelData.PINDUODUO, new Bundle()).getString("error"));
    }

    @Test public void shellCannotSpoofSupportedPackage() {
        ShadowBinder.setCallingUid(2000);
        assertEquals("unauthorized", provider.call("appParcelEnabled", AppParcelData.CAINIAO, null).getString("error"));
    }

    @Test public void disabledGateRejectsEvenValidSender() {
        assertEquals("app_parcel_disabled", provider.call("onAppParcel", AppParcelData.CAINIAO, new Bundle()).getString("error"));
    }

    @Test public void invalidCodesRejectedBeforeDatabaseAccess() {
        enable(true);
        Bundle data = new Bundle();
        data.putString("code", "12**56");
        assertEquals("invalid_parcel", provider.call("onAppParcel", AppParcelData.CAINIAO, data).getString("error"));
        data.putString("code", "123456");
        data.putString("place", "x".repeat(201));
        assertEquals("invalid_parcel", provider.call("onAppParcel", AppParcelData.CAINIAO, data).getString("error"));
    }

    @Test public void appCannotUseSmsOrCompletionEndpoints() {
        assertEquals("unauthorized", provider.call("onSms", null, new Bundle()).getString("error"));
        assertEquals("unauthorized", provider.call("markDone", "1-2-3456", null).getString("error"));
    }
}
