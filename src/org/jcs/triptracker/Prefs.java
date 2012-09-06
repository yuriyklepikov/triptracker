package org.jcs.triptracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;

public final class Prefs {
	private static final String TAG = "Prefs";

	public static String ENDPOINT = "endpoint";
	public static String ENABLED = "enabled";
	public static String UPDATE_FREQ = "update_freq";
	public static String LAST_POST_TIME = "last_post_time";

    public static SharedPreferences get(final Context context) {
        return context.getSharedPreferences("org.jcs.triptracker",
			Context.MODE_PRIVATE);
    }

	public static String getPref(final Context context, String pref,
	String def) {
		SharedPreferences prefs = Prefs.get(context);
		String val = prefs.getString(pref, def);

		if (val == null || val.equals("") || val.equals("null"))
			return def;
		else
			return val;
	}

	public static void putPref(final Context context, String pref,
	String val) {
		SharedPreferences prefs = Prefs.get(context);
		SharedPreferences.Editor editor = prefs.edit();

		editor.putString(pref, val);
		editor.commit();
	}

	public static String getEndpoint(final Context context) {
		return Prefs.getPref(context, ENDPOINT, null);
	}

	public static String getUpdateFreq(final Context context) {
		return Prefs.getPref(context, UPDATE_FREQ, "30m");
	}
	
	public static boolean getEnabled(final Context context) {
		String e = Prefs.getPref(context, ENABLED, "false");
		return e.equals("true");
	}

	public static void putUpdateFreq(final Context context, String freq) {
		Prefs.putPref(context, UPDATE_FREQ, freq);
	}

	public static void putEndpoint(final Context context, String endpoint) {
		Prefs.putPref(context, ENDPOINT, endpoint);
	}
	
	public static void putEnabled(final Context context, boolean enabled) {
		Prefs.putPref(context, ENABLED, (enabled ? "true" : "false"));
	}
}
