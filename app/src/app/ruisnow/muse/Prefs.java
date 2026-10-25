package app.ruisnow.muse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class Prefs {
    private static final String NAME = "muse_shell";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c) {
        return sp(c).getString("base_url", "https://phone.ruisnow.de");
    }

    public static String token(Context c) {
        return sp(c).getString("token", "");
    }

    public static String deviceId(Context c) {
        String id = sp(c).getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8);
            sp(c).edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static boolean killSwitch(Context c) {
        return sp(c).getBoolean("kill_switch", false);
    }

    public static void setKillSwitch(Context c, boolean on) {
        sp(c).edit().putBoolean("kill_switch", on).apply();
    }

    /** Press-and-hold the screen for 0.5s to open Muse. Default ON. */
    public static boolean longPressMuse(Context c) {
        return sp(c).getBoolean("long_press_muse", true);
    }

    public static void setLongPressMuse(Context c, boolean on) {
        sp(c).edit().putBoolean("long_press_muse", on).apply();
    }

    /** Auto-connect checkbox: boot autostart + watchdog resurrection. Default ON
     *  (preserves the pre-0.4.8 behavior where boot autostart was unconditional). */
    public static boolean autoConnect(Context c) {
        return sp(c).getBoolean("auto_connect", true);
    }

    public static void setAutoConnect(Context c, boolean on) {
        sp(c).edit().putBoolean("auto_connect", on).apply();
    }

    /** AI browser channel ("AI 浏览器" checkbox). Default ON; the user's kill
     *  switch still blocks every browser tool when engaged. */
    public static boolean browserEnabled(Context c) {
        return sp(c).getBoolean("browser_enabled", true);
    }

    public static void setBrowserEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("browser_enabled", on).apply();
    }

    /** Root shell approval bypass ("root shell 免审" checkbox). Default ON and
     *  persisted (user's standing instruction 2026-10-22): shell commands run
     *  without an approval card across service restarts, until the user
     *  unchecks it in the console. */
    public static boolean shellTrust(Context c) {
        return sp(c).getBoolean("shell_trust", true);
    }

    public static void setShellTrust(Context c, boolean on) {
        sp(c).edit().putBoolean("shell_trust", on).apply();
    }

    /** Set when the user stops the service by hand: the watchdog must not fight
     *  a deliberate stop. Cleared on manual start and at boot. */
    public static boolean userStopped(Context c) {
        return sp(c).getBoolean("user_stopped", false);
    }

    public static void setUserStopped(Context c, boolean on) {
        sp(c).edit().putBoolean("user_stopped", on).apply();
    }
}
