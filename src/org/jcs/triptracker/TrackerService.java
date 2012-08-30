package org.jcs.triptracker;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

	private NotificationManager nm;
	private static boolean isRunning = false;

	private String freqString;
	private int freqSeconds;
	private String endpoint;

	private final int MAX_RING_SIZE = 15;
	
	private LocationListener locationListener;

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

		/* we're not registered yet, so this will just log to our ring buffer,
		 * but as soon as the client connects we send the log buffer anyway */
		logText("service started, requesting location update every " +
			freqString);

		isRunning = true;

		LocationManager locationManager = (LocationManager)
			this.getSystemService(Context.LOCATION_SERVICE);

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

		/* use our frequency as a recommendation, but we may not get updates
		 * every interval.  oh well.  no point in using a timer to force it
		 * since the location will just be the same. */
		locationManager.requestLocationUpdates(
			LocationManager.GPS_PROVIDER, freqSeconds * 1000, 0,
			locationListener);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (httpPoster != null)
			httpPoster.cancel(true);

		/* kill persistent notification */
		nm.cancelAll();

		try {
			LocationManager locationManager = (LocationManager)
				this.getSystemService(Context.LOCATION_SERVICE);
			locationManager.removeUpdates(locationListener);
		}
		catch (Exception e) {
		}

		isRunning = false;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
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

	private void logText(String log) {
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
			(httpPoster = new HttpPoster()).execute(TrackerService.this);
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

	class HttpPoster extends AsyncTask<Object, Void, Boolean> {
		@Override
		protected Boolean doInBackground(Object... t) {
			TrackerService service = (TrackerService)t[0];

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

				if (failed)
					/* if our initial request failed, snooze for a bit and try
					 * again, the server might not be reachable */
					SystemClock.sleep(15 * 1000);

				int q = 0;
				updateLock.writeLock().lock();
				q = service.getUpdatesSize();
				updateLock.writeLock().unlock();

				if (q == 0)
					break;
				/* otherwise, run through the rest of the queue */
			}

			return true;
		}

		protected void onPostExecute(Void v) {
		}
	}
}
