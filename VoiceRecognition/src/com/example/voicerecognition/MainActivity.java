package com.example.voicerecognition;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Scanner;

import javax.net.ssl.HttpsURLConnection;

import com.example.voicerecognition.R;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 
 * @author Lucas Santana
 * 
 *         Activity responsible for recording and sending sound to Google API
 * 
 */

public class MainActivity extends Activity {

	// Language spoken
	// Obs: It requires Google codes: English(en_us), Portuguese(pt_br), Spanish
	// (es_es), etc
	String language = "en_us";

	// Key obtained through Google Developer group
	String api_key = "AIzaSyBgnC5fljMTmCFeilkgLsOKBvvnx6CBS0M";

	// Name of the sound file (.flac)
	String fileName = Environment.getExternalStorageDirectory()	+ "/recording.flac";

	// URL for Google API
	String root = "https://www.google.com/speech-api/full-duplex/v1/";
	String dwn = "down?maxresults=1&pair=";
	String API_DOWN_URL = root + dwn;
	String up_p1 = "up?lang=" + language
			+ "&lm=dictation&client=chromium&pair=";
	String up_p2 = "&key=";

	
	
	// Variables used to establish return code
	private static final long MIN = 10000000;
	private static final long MAX = 900000009999999L;
	long PAIR;

	// Constants
	private int mErrorCode = -1;
	private static final int DIALOG_RECORDING_ERROR = 0;
	// Rate of the recorded sound file
	int sampleRate;
	// Recorder instance
	private Recorder mRecorder;

	// Output for Google answer
	TextView txtView;
	Button recordButton, stopButton, listenButton;

	// Handler used for sending request to Google API
	Handler handler = new Handler();

	// Recording callbacks
	private Handler mRecordingHandler = new Handler(new Handler.Callback() {
		public boolean handleMessage(Message m) {
			switch (m.what) {
			case FLACRecorder.MSG_AMPLITUDES:
				FLACRecorder.Amplitudes amp = (FLACRecorder.Amplitudes) m.obj;

				break;

			case FLACRecorder.MSG_OK:
				// Ignore
				break;

			case Recorder.MSG_END_OF_RECORDING:

				break;

			default:
				mRecorder.stop();
				mErrorCode = m.what;
				showDialog(DIALOG_RECORDING_ERROR);
				break;
			}

			return true;
		}
	});

	// DOWN handler
	Handler messageHandler = new Handler() {

		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case 1: // GET DOWNSTREAM json id="@+id/comment"
				String mtxt = msg.getData().getString("text");
				if (mtxt.length() > 20) {
					final String f_msg = mtxt;
					handler.post(new Runnable() { // This thread runs in the UI
						// TREATMENT FOR GOOGLE RESPONSE
						@Override
						public void run() {
							System.out.println(f_msg);
							txtView.setText(f_msg);
						}
					});
				}
				break;
			case 2:
				break;
			}
		}
	}; // doDOWNSTRM Handler end

	// UPSTREAM channel. its servicing a thread and should have its own handler
	Handler messageHandler2 = new Handler() {

		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case 1: // GET DOWNSTREAM json
				Log.d("ParseStarter", msg.getData().getString("post"));
				break;
			case 2:
				Log.d("ParseStarter", msg.getData().getString("post"));
				break;
			}

		}
	}; // UPstream handler end

	/**************************************************************************************************************
	 * Implementation
	 **/

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		txtView = (TextView) this.findViewById(R.id.txtView);
		recordButton = (Button) this.findViewById(R.id.record);
		stopButton = (Button) this.findViewById(R.id.stop);
		stopButton.setEnabled(false);
		listenButton = (Button) this.findViewById(R.id.listen);
		listenButton.setEnabled(false);

		mRecorder = new Recorder(this, mRecordingHandler);

	}

	/***************************************************************************************************************
	 * Method related to recording in FLAC file
	 */

	public void recordButton(View v) {

		mRecorder.start(fileName);

		txtView.setText("");
		recordButton.setEnabled(false);
		stopButton.setEnabled(true);
		Toast.makeText(getApplicationContext(), "Recording...",
				Toast.LENGTH_LONG).show();

	}

	/***************************************************************************************************************
	 * Method that stops recording
	 */

	public void stopRecording(View v) {

		Toast.makeText(getApplicationContext(), "Loading...", Toast.LENGTH_LONG)
				.show();
		recordButton.setEnabled(true);
		listenButton.setEnabled(true);

		sampleRate = mRecorder.mFLACRecorder.getSampleRate();
		getTranscription(sampleRate);
		mRecorder.stop();

	}

	/***************************************************************************************************************
	 * Method that listens to recording
	 */
	public void listenRecord(View v) {
		Context context = this;

		FLACPlayer mFlacPlayer = new FLACPlayer(context, fileName);
		mFlacPlayer.start();

	}

	/**************************************************************************************************************
	 * Method related to Google Voice Recognition
	 **/

	public void getTranscription(int sampleRate) {

		File myfil = new File(fileName);
		if (!myfil.canRead())
			Log.d("ParseStarter", "FATAL no read access");

		// first is a GET for the speech-api DOWNSTREAM
		// then a future exec for the UPSTREAM / chunked encoding used so as not
		// to limit
		// the POST body sz

		PAIR = MIN + (long) (Math.random() * ((MAX - MIN) + 1L));
		// DOWN URL just like in curl full-duplex example plus the handler
		downChannel(API_DOWN_URL + PAIR, messageHandler);

		// UP chan, process the audio byteStream for interface to UrlConnection
		// using 'chunked-encoding'
		FileInputStream fis;
		try {
			fis = new FileInputStream(myfil);
			FileChannel fc = fis.getChannel(); // Get the file's size and then
												// map it into memory
			int sz = (int) fc.size();
			MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, sz);
			byte[] data2 = new byte[bb.remaining()];
			Log.d("ParseStarter", "mapfil " + sz + " " + bb.remaining());
			bb.get(data2);
			// conform to the interface from the curl examples on full-duplex
			// calls
			// see curl examples full-duplex for more on 'PAIR'. Just a globally
			// uniq value typ=long->String.
			// API KEY value is part of value in UP_URL_p2
			upChannel(root + up_p1 + PAIR + up_p2 + api_key, messageHandler2,
					data2);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void downChannel(String urlStr, final Handler messageHandler) {

		final String url = urlStr;

		new Thread() {
			Bundle b;

			public void run() {
				String response = "NAO FOI";
				Message msg = Message.obtain();
				msg.what = 1;
				// handler for DOWN channel http response stream - httpsUrlConn
				// response handler should manage the connection.... ??
				// assign a TIMEOUT Value that exceeds by a safe factor
				// the amount of time that it will take to write the bytes
				// to the UPChannel in a fashion that mimics a liveStream
				// of the audio at the applicable Bitrate. BR=sampleRate * bits
				// per sample
				// Note that the TLS session uses
				// "* SSLv3, TLS alert, Client hello (1): "
				// to wake up the listener when there are additional bytes.
				// The mechanics of the TLS session should be transparent. Just
				// use
				// httpsUrlConn and allow it enough time to do its work.
				Scanner inStream = openHttpsConnection(url);
				// process the stream and store it in StringBuilder
				while (inStream.hasNextLine()) {
					b = new Bundle();
					b.putString("text", inStream.nextLine());
					msg.setData(b);
					messageHandler.dispatchMessage(msg);
				}

			}
		}.start();
	}

	private void upChannel(String urlStr, final Handler messageHandler,
			byte[] arg3) {

		final String murl = urlStr;
		final byte[] mdata = arg3;
		Log.d("ParseStarter", "upChan " + mdata.length);
		new Thread() {
			public void run() {
				String response = "NAO FOI";
				Message msg = Message.obtain();
				msg.what = 2;
				Scanner inStream = openHttpsPostConnection(murl, mdata);
				inStream.hasNext();
				// process the stream and store it in StringBuilder
				while (inStream.hasNextLine()) {
					response += (inStream.nextLine());
					Log.d("ParseStarter", "POST resp " + response.length());
				}
				Bundle b = new Bundle();
				b.putString("post", response);
				msg.setData(b);
				// in.close(); // mind the resources
				messageHandler.sendMessage(msg);

			}
		}.start();

	}

	// GET for DOWNSTREAM
	private Scanner openHttpsConnection(String urlStr) {
		InputStream in = null;
		int resCode = -1;
		Log.d("ParseStarter", "dwnURL " + urlStr);

		try {
			URL url = new URL(urlStr);
			URLConnection urlConn = url.openConnection();

			if (!(urlConn instanceof HttpsURLConnection)) {
				throw new IOException("URL is not an Https URL");
			}

			HttpsURLConnection httpConn = (HttpsURLConnection) urlConn;
			httpConn.setAllowUserInteraction(false);
			// TIMEOUT is required
			httpConn.setInstanceFollowRedirects(true);
			httpConn.setRequestMethod("GET");

			httpConn.connect();

			resCode = httpConn.getResponseCode();
			if (resCode == HttpsURLConnection.HTTP_OK) {
				return new Scanner(httpConn.getInputStream());
			}

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	// GET for UPSTREAM
	private Scanner openHttpsPostConnection(String urlStr, byte[] data) {
		InputStream in = null;
		byte[] mextrad = data;
		int resCode = -1;
		OutputStream out = null;
		// int http_status;
		try {
			URL url = new URL(urlStr);
			URLConnection urlConn = url.openConnection();

			if (!(urlConn instanceof HttpsURLConnection)) {
				throw new IOException("URL is not an Https URL");
			}

			HttpsURLConnection httpConn = (HttpsURLConnection) urlConn;
			httpConn.setAllowUserInteraction(false);
			httpConn.setInstanceFollowRedirects(true);
			httpConn.setRequestMethod("POST");
			httpConn.setDoOutput(true);
			httpConn.setChunkedStreamingMode(0);
			httpConn.setRequestProperty("Content-Type", "audio/x-flac; rate="
					+ sampleRate);
			httpConn.connect();

			try {
				// this opens a connection, then sends POST & headers.
				out = httpConn.getOutputStream();
				// Note : if the audio is more than 15 seconds
				// dont write it to UrlConnInputStream all in one block as this
				// sample does.
				// Rather, segment the byteArray and on intermittently, sleeping
				// thread
				// supply bytes to the urlConn Stream at a rate that approaches
				// the bitrate ( =30K per sec. in this instance ).
				Log.d("ParseStarter", "IO beg on data");
				out.write(mextrad); // one big block supplied instantly to the
									// underlying chunker wont work for duration
									// > 15 s.
				Log.d("ParseStarter", "IO fin on data");
				// do you need the trailer?
				// NOW you can look at the status.
				resCode = httpConn.getResponseCode();

				Log.d("ParseStarter", "POST OK resp "
						+ httpConn.getResponseMessage().getBytes().toString());

				if (resCode / 100 != 2) {
					Log.d("ParseStarter", "POST bad io ");
				}

			} catch (IOException e) {
				Log.d("ParseStarter", "FATAL " + e);

			}

			if (resCode == HttpsURLConnection.HTTP_OK) {
				Log.d("ParseStarter", "OK RESP to POST return scanner ");
				return new Scanner(httpConn.getInputStream());
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

}
