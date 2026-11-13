package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class FrameworkConfigTest {
    @After public void detach() { HookConfig.attach(null); }
    @Test public void applicationSettingsPublishThroughFrameworkWithoutRoot() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                .putBoolean(TodoProvider.APP_PARCEL_PREF, true).putBoolean("noti_hook", true)
                .putString("blacklist", "quoted\"value\nnext").commit();
        SharedPreferences remote = app.getSharedPreferences("publish-test", Context.MODE_PRIVATE);
        HookConfig.attach(remote);
        ProcessSync.push(app);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!remote.contains("snapshot") && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Background snapshot was committed", remote.contains("snapshot"));
        JSONObject snapshot = new JSONObject(remote.getString("snapshot", ""));
        assertTrue(snapshot.getBoolean("appParcelEnabled"));
        assertTrue(snapshot.getBoolean("notiHook"));
        assertEquals("quoted\"value\nnext", snapshot.getString("blacklist"));
    }
    @Test public void remoteSnapshotDistinguishesNoTodoAndHotUpdatesDisabledSwitches() throws Exception {
        SharedPreferences remote = RuntimeEnvironment.getApplication().getSharedPreferences("remote-test", Context.MODE_PRIVATE);
        HookConfig.attach(remote);
        JSONObject snapshot = new JSONObject().put("noTodo", true).put("notiHook", true)
                .put("sysDirectWrite", true).put("blacklist", "ignored");
        assertTrue(HookConfig.publish(snapshot.toString()));
        assertTrue(ProcessSync.load().noTodo);
        assertTrue(ProcessSync.load().notiHook);
        assertTrue(SystemDirectWriter.enabled(null));
        assertEquals("ignored", ProcessSync.load().blacklist);
        assertTrue(HookConfig.publish(snapshot.put("sysDirectWrite", false).put("notiHook", false).toString()));
        assertFalse(SystemDirectWriter.enabled(null));
        assertFalse(ProcessSync.load().notiHook);
    }
    @Test public void absentFrameworkFailsClosedAndIgnoresHistoricalMarker() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        new java.io.File(app.getFilesDir(), "pickup_injected.flag").createNewFile();
        HookConfig.attach(null);
        assertFalse(HookConfig.publish("{}"));
        assertFalse(ProcessSync.load().notiHook);
        assertFalse(SystemDirectWriter.enabled(null));
        assertFalse(Diagnostics.lightHealthCheck(app).get(0).ok);
    }
    @Test public void sharedUserRulesExcludePrivateTestSamples() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                .putBoolean("user_rules_enabled", true).commit();
        UserRules.Rule rule = new UserRules.Rule();
        rule.id = "local-test";
        rule.name = "local rule";
        rule.codeRegex = "取件码([0-9-]+)";
        rule.enabled = true;
        rule.testCases.add(new UserRules.TestCase("private SMS sample取件码1234", "1234"));
        UserRules.compileRule(rule);
        UserRules.saveAll(app, java.util.Collections.singletonList(rule));
        String safe = UserRules.systemSnapshot(app);
        assertFalse(safe.contains("private SMS sample"));
        assertTrue(safe.contains("local rule"));
    }
}
