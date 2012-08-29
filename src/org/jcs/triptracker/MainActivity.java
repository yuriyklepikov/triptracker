package org.jcs.triptracker;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Bundle;
import android.view.Menu;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
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

public class MainActivity extends Activity {
	private static final String TAG = "MainActivity";

	/** Called when the activity is first created. */
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
				if (!hasFocus) { 
					String e = endpoint.getText().toString();

					try {
						URL u = new URL(e);
						e = u.toURI().toString();

						Prefs.putEndpoint(MainActivity.this, e);
					}
					catch (Exception ex) {
						endpoint.setError("Invalid URL");
					}
				}
			}
		});

		/* add minute values to update frequency drop down */
		final Spinner updatefreq = (Spinner)findViewById(R.id.main_updatefreq);
		ArrayAdapter<CharSequence> adapter =
			ArrayAdapter.createFromResource(this,
			R.array.main_updatefreq_entries,
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
			}

			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		final CheckBox enabler = (CheckBox)findViewById(R.id.main_enabler);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater menuInflater = getMenuInflater();
		menuInflater.inflate(R.menu.main, menu);

		return super.onCreateOptionsMenu(menu);
	}

	public void logText(String text) {
		TextView log = (TextView)findViewById(R.id.main_log);
		log.append(text);

		ScrollView scroller = (ScrollView)findViewById(R.id.main_log_scroller);
		scroller.smoothScrollTo(0, log.getBottom());  
	}
}
