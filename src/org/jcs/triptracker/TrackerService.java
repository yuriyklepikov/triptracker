package org.jcs.triptracker;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import android.content.Context;
import android.content.Intent;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

public class TrackerService extends Service {
	private static final String TAG = "TrackerService";

	public static TrackerService service;

	private NotificationManager nm;
	private static boolean isRunning = false;

	private String freqString;
	private int freqSeconds;
	private String endpoint;

	private final int MAX_RING_SIZE = 15;
	
	private LocationListener locationListener;
	private AlarmManager alarmManager;
	private PendingIntent pendingAlarm;
	private static volatile PowerManager.WakeLock wakeLock;

	private AsyncTask httpPoster;

	ArrayList<LogMessage> mLogRing = new ArrayList<LogMessage>();
	ArrayList<Messenger> mClients = new ArrayList<Messenger>();
	ArrayList<List> mUpdates = new ArrayList<List>();
	final ReentrantReadWriteLock updateLock = new ReentrantReadWriteLock();
	final Messenger mMessenger = new Messenger(new IncomingHandler());

	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int MSG_LOG = 3;
	static final int MSG_LOG_RING = 4;

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	@Override
	public void onCreate() {
		super.onCreate();

		TrackerService.service = this;

		endpoint = Prefs.getEndpoint(this);
		freqSeconds = 0;
		freqString = null;

		freqString = Prefs.getUpdateFreq(this);
		if (freqString != null && !freqString.equals("")) {
			try {
				Pattern p = Pattern.compile("(\\d+)(m|h|s)");
				Matcher m = p.matcher(freqString);
				m.find();
				freqSeconds = Integer.parseInt(m.group(1));
				if (m.group(2).equals("h"))
					freqSeconds *= (60 * 60);
				else if (m.group(2).equals("m"))
					freqSeconds *= 60;
			}
			catch (Exception e) {
			}
		}

		if (endpoint == null || endpoint.equals("")) {
			logText("invalid endpoint, stopping service");
			stopSelf();
		}

		if (freqSeconds < 1) {
			logText("invalid frequency (" + freqSeconds + "), stopping " +
				"service");
			stopSelf();
		}

		showNotification();

		isRunning = true;

		/* we're not registered yet, so this will just log to our ring buffer,
		 * but as soon as the client connects we send the log buffer anyway */
		logText("service started, requesting location update every " +
			freqString);

		/* findAndSendLocation() will callback to this */
		locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				sendLocation(location);
			}

			public void onStatusChanged(String provider, int status,
			Bundle extras) {
			}

			public void onProviderEnabled(String provider) {
			}

			public void onProviderDisabled(String provider) {
			}
		};

		/* we don't need to be exact in our frequency, try to conserve at least
		 * a little battery */
		alarmManager = (AlarmManager)getSystemService(ALARM_SERVICE);
		Intent i = new Intent(this, AlarmBroadcast.class);
		pendingAlarm = PendingIntent.getBroadcast(this, 0, i, 0);
		alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
			SystemClock.elapsedRealtime(), freqSeconds * 1000, pendingAlarm);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (httpPoster != null)
			httpPoster.cancel(true);

		try {
			LocationManager locationManager = (LocationManager)
				this.getSystemService(Context.LOCATION_SERVICE);
			locationManager.removeUpdates(locationListener);
		}
		catch (Exception e) {
		}

		/* kill persistent notification */
		nm.cancelAll();

		if (pendingAlarm != null)
			alarmManager.cancel(pendingAlarm);

		isRunning = false;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	/* called within wake lock from broadcast receiver, but assert that we have
	 * it so we can keep it longer when we return (since the location request
	 * uses a callback) and then free it when we're done running through the
	 * queue */
	public void findAndSendLocation() {
		if (wakeLock == null) {
			PowerManager pm = (PowerManager)this.getSystemService(
				Context.POWER_SERVICE);

			/* we don't need the screen on */
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
				"triptracker");
			wakeLock.setReferenceCounted(true);
		}

		if (!wakeLock.isHeld())
			wakeLock.acquire();

		LocationManager locationManager = (LocationManager)
			this.getSystemService(Context.LOCATION_SERVICE);

		locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
			locationListener, null);
	}

	public static boolean isRunning() {
		return isRunning;
	}

	private void showNotification() {
		nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		Notification notification = new Notification(R.drawable.icon,
			"Trip Tracker Started", System.currentTimeMillis());
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
			new Intent(this, MainActivity.class), 0);
		notification.setLatestEventInfo(this, "Trip Tracker",
			"Sending location every " + freqString, contentIntent);
		notification.flags = Notification.FLAG_ONGOING_EVENT;
		nm.notify(1, notification);
	}

	public void logText(String log) {
		LogMessage lm = new LogMessage(new Date(), log);
		mLogRing.add(lm);
		if (mLogRing.size() > MAX_RING_SIZE)
			mLogRing.remove(0);

		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Bundle b = new Bundle();
				b.putString("log", log);
				Message msg = Message.obtain(null, MSG_LOG);
				msg.setData(b);
				mClients.get(i).send(msg);
			}
			catch (RemoteException e) {
				/* client is dead, how did this happen */
				mClients.remove(i);
			}
		}
	}

	public List<NameValuePair> getUpdate(int i) {
		return mUpdates.get(i);
	}
	
	public int getUpdatesSize() {
		return mUpdates.size();
	}

	public void removeUpdate(int i) {
		mUpdates.remove(i);
	}

	private void sendLocation(Location location) {
		List<NameValuePair> pairs = new ArrayList<NameValuePair>(2);
		pairs.add(new BasicNameValuePair("time",
			String.valueOf(location.getTime())));
		pairs.add(new BasicNameValuePair("latitude",
			String.valueOf(location.getLatitude())));
		pairs.add(new BasicNameValuePair("longitude",
			String.valueOf(location.getLongitude())));
		pairs.add(new BasicNameValuePair("speed",
			String.valueOf(location.getSpeed())));

		logText("location " +
			(new DecimalFormat("#.######").format(location.getLatitude())) +
			", " +
			(new DecimalFormat("#.######").format(location.getLongitude())));

		/* push these pairs onto the queue, and only run the poster if another
		 * one isn't running already (if it is, it will keep running through
		 * the queue until it's empty) */
		boolean pokePoster = false;

		updateLock.writeLock().lock();
		mUpdates.add(pairs);
		if (mUpdates.size() == 1)
			pokePoster = true;
		updateLock.writeLock().unlock();

		if (pokePoster)
			(httpPoster = new HttpPoster()).execute();

		/* otherwise, the queue is already being run through */
	}

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				mClients.add(msg.replyTo);

				/* respond with our log ring to show what we've been up to */
				try {
					Message replyMsg = Message.obtain(null, MSG_LOG_RING);
					replyMsg.obj = mLogRing;
					msg.replyTo.send(replyMsg);
				}
				catch (RemoteException e) {
				}

				break;
			case MSG_UNREGISTER_CLIENT:
				mClients.remove(msg.replyTo);
				break;

			default:
				super.handleMessage(msg);
			}
		}
	}

	/* Void as first arg causes a crash, no idea why
	E/AndroidRuntime(17157): Caused by: java.lang.ClassCastException: java.lang.Object[] cannot be cast to java.lang.Void[]
	*/
	class HttpPoster extends AsyncTask<Object, Void, Boolean> {
		@Override
		protected Boolean doInBackground(Object... o) {
			TrackerService service = TrackerService.service;

			int retried = 0;
			int max_retries = 4;

			while (true) {
				if (isCancelled())
					return false;

				boolean failed = false;

				updateLock.writeLock().lock();
				List<NameValuePair> pairs = service.getUpdate(0);
				updateLock.writeLock().unlock();

				AndroidHttpClient httpClient =
					AndroidHttpClient.newInstance("TripTracker");

				try {
					HttpPost post = new HttpPost(endpoint);
					post.setEntity(new UrlEncodedFormEntity(pairs));
					HttpResponse resp = httpClient.execute(post);

					int httpStatus = resp.getStatusLine().getStatusCode();
					if (httpStatus == 200) {
						/* all good, we can remove this from the queue */
						updateLock.writeLock().lock();
						service.removeUpdate(0);
						updateLock.writeLock().unlock();
					}
					else {
						logText("POST failed to " + endpoint + ": got " +
							httpStatus + " status");
						failed = true;
					}
				}
				catch (Throwable th) {
					logText("POST failed to " + endpoint + ": " + th);
					failed = true;
				}
				finally {
					if (httpClient != null)
						httpClient.close();
				}

				if (failed) {
					/* if our initial request failed, snooze for a bit and try
					 * again, the server might not be reachable */
					SystemClock.sleep(15 * 1000);

					if (++retried > max_retries) {
						/* give up since we're holding the wake lock open for
						 * too long.  we'll get it next time, champ. */
						logText("too many failures, retrying later (queue " +
							"size " + service.getUpdatesSize() + ")");
						break;
					}
				}
				else
					retried = 0;

				int q = 0;
				updateLock.writeLock().lock();
				q = service.getUpdatesSize();
				updateLock.writeLock().unlock();

				if (q == 0)
					break;
				/* otherwise, run through the rest of the queue */
			}

			return false;
		}

		protected void onPostExecute(Boolean b) {
			if (wakeLock != null)
				wakeLock.release();
		}
	}
}
