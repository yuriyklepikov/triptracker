package org.jcs.triptracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlarmBroadcast extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		/* we now have a wake lock until we return */

		if (TrackerService.service != null &&
		TrackerService.service.isRunning())
			TrackerService.service.findAndSendLocation();
	}
}
