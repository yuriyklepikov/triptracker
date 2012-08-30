package org.jcs.triptracker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class MainActivity extends Activity {
	private static final String TAG = "MainActivity";

	Messenger mService = null;
	boolean mIsBound;
	final Messenger mMessenger = new Messenger(new IncomingHandler());

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);

		/* load saved endpoint */
		final EditText endpoint = (EditText)findViewById(R.id.main_endpoint);
		String savedendpoint = Prefs.getEndpoint(this);
		if (savedendpoint != null)
			endpoint.setText(savedendpoint);

		/* save endpoint when textbox loses focus if the url is valid */
		endpoint.setOnFocusChangeListener(new OnFocusChangeListener() {
			@Override
			public void onFocusChange(View v, boolean hasFocus) {
				if (hasFocus)
					return;

				String e = endpoint.getText().toString();

				try {
					URL u = new URL(e);
					e = u.toURI().toString();

					Prefs.putEndpoint(MainActivity.this, e);
				}
				catch (Exception ex) {
					endpoint.setError("Invalid URL");
					Prefs.putEndpoint(MainActivity.this, null);
				}

				/* hide the keyboard */
				InputMethodManager imm = (InputMethodManager)
					getSystemService(Activity.INPUT_METHOD_SERVICE);
				imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
			}
		});

		/* add minute values to update frequency drop down */
		final Spinner updatefreq = (Spinner)findViewById(R.id.main_updatefreq);
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
			this, R.array.main_updatefreq_entries,
			android.R.layout.simple_spinner_item);
		adapter.setDropDownViewResource(
			android.R.layout.simple_spinner_dropdown_item);
		updatefreq.setAdapter(adapter);

		/* load saved frequency */
		String savedfreq = Prefs.getUpdateFreq(this);
		if (savedfreq != null)
			updatefreq.setSelection(adapter.getPosition(savedfreq));

		/* store frequency when we change it */
		updatefreq.setOnItemSelectedListener(
		new AdapterView.OnItemSelectedListener() {
			public void onItemSelected(AdapterView<?> parent, View view,
			int pos, long id) {
				Prefs.putUpdateFreq(MainActivity.this,
					updatefreq.getSelectedItem().toString());

				/* if the service is already running, restart it */
				if (Prefs.getEndpoint(MainActivity.this) == null &&
				TrackerService.isRunning()) {
					doUnbindService();
					stopService(new Intent(MainActivity.this,
						TrackerService.class));
					startService(new Intent(MainActivity.this,
						TrackerService.class));
					doBindService();
				}
			}

			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		final CheckBox enabler = (CheckBox)findViewById(R.id.main_enabler);

		enabler.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				/* divert focus away from the endpoint text field so it can
				 * validate and hide the keyboard */
				findViewById(R.id.main_layout).requestFocus();

				if (enabler.isChecked()) {
					if (Prefs.getEndpoint(MainActivity.this) == null)
						enabler.setChecked(false);
					else {
						startService(new Intent(MainActivity.this,
							TrackerService.class));
						doBindService();
					}
				}
				else {
					stopService(new Intent(MainActivity.this,
						TrackerService.class));
					doUnbindService();
				}
			}
		});

		/* if the service is already running, bind and we'll get back its
		 * recent log ring buffer */
		if (TrackerService.isRunning()) {
			enabler.setChecked(true);
			doBindService();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater menuInflater = getMenuInflater();
		menuInflater.inflate(R.menu.main, menu);

		return super.onCreateOptionsMenu(menu);
	}

    @Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_exit:
			stopService(new Intent(MainActivity.this, TrackerService.class));
			doUnbindService();
			finish();

			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		try {
			doUnbindService();
		}
		catch (Throwable e) {
		}
	}

	public void logText(String text) {
		logText(text, new Date());
	}

	public void logText(String text, Date date) {
		/* DateFormat.SHORT doesn't honor 24 hour time :( */
		String now = (new SimpleDateFormat("HH:mm:ss")).format(date);

		TextView log = (TextView)findViewById(R.id.main_log);
		log.append("[" + now + "] " + text + "\n");

		/* we have to scroll asynchronously because the view isn't updated from
		 * our append() yet, so scrolling to its bottom will not actually reach
		 * the bottom */
		final ScrollView scroller = (ScrollView)findViewById(
			R.id.main_log_scroller);
		scroller.post(new Runnable() {            
			@Override
			public void run() {
				/* TODO: if log is scrolled up by the user, don't scroll it
				 * down until they do */
				scroller.fullScroll(View.FOCUS_DOWN);
			}
		});
	}

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case TrackerService.MSG_LOG:
				logText(msg.getData().getString("log"));
				break;

			case TrackerService.MSG_LOG_RING:
				ArrayList<LogMessage> logs = (ArrayList)msg.obj;

				for (int i = 0; i < logs.size(); i++) {
					LogMessage l = logs.get(i);
					logText(l.message, l.date);
				}

				break;

			default:
				super.handleMessage(msg);
			}
		}
	}

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className,
		IBinder service) {
			mService = new Messenger(service);
			try {
				Message msg = Message.obtain(null,
					TrackerService.MSG_REGISTER_CLIENT);
				msg.replyTo = mMessenger;
				mService.send(msg);
			}
			catch (RemoteException e) {
				logText("error connecting to service: " + e.getMessage());
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			mService = null;
			logText("disconnected from service :(");
		}
	};

	void doBindService() {
		bindService(new Intent(this, TrackerService.class), mConnection,
			Context.BIND_AUTO_CREATE);
		mIsBound = true;
	}

	void doUnbindService() {
		if (!mIsBound)
			return;

		if (mService != null) {
			try {
				Message msg = Message.obtain(null,
					TrackerService.MSG_UNREGISTER_CLIENT);
				msg.replyTo = mMessenger;
				mService.send(msg);
			}
			catch (RemoteException e) {
			}
		}

		unbindService(mConnection);
		mIsBound = false;

		logText("service stopped");
	}
}
