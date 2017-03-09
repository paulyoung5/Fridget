/**
 * Created by paulyoung on 24/02/2017.
 */

package com.fridget;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.security.ProviderInstaller;

import java.io.InputStream;
import java.util.Locale;

import io.grpc.ManagedChannel;
import pl.bclogic.pulsator4droid.library.PulsatorLayout;

import com.fridget.R;
import com.google.android.gms.vision.text.Text;

public class Fridget {

    private Context appContext;

    private TextToSpeech textToSpeech;

    private static final String HOSTNAME = "speech.googleapis.com";
    private static final int PORT = 443;
    private static final int RECORDER_SAMPLERATE = 16000;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    protected AudioRecord mAudioRecord = null;
    private Thread mRecordingThread = null;
    protected boolean mIsRecording = false;

    protected StreamingRecognizeClient mStreamingClient;
    private int mBufferSize;

    protected String userSpeech;
    protected View rootView;

    public Fridget(Context context, View rootView) {

        appContext = context;

        // Set up microphone / speech recognition
        mBufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, AudioFormat
                .CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2;

        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDER_SAMPLERATE,
                RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING,
                mBufferSize);

        initialize();

        this.rootView = rootView;

        // Set up text-to-speech
        textToSpeech = new TextToSpeech(appContext, new TextToSpeech.OnInitListener() {

            @Override
            public void onInit(int status) {

                int result = textToSpeech.setLanguage(Locale.UK);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "This Language is not supported");
                }

            }

        });

        // Now load access token

    }

    private void initialize() {
        final Fridget fridget = this;
        new Thread(new Runnable() {
            @Override
            public void run() {

                // Required to support Android 4.x.x (patches for OpenSSL from Google-Play-Services)
                try {
                    ProviderInstaller.installIfNeeded(appContext);
                } catch (GooglePlayServicesRepairableException e) {

                    // Indicates that Google Play services is out of date, disabled, etc.
                    e.printStackTrace();
                    // Prompt the user to install/update/enable Google Play services.
                    GooglePlayServicesUtil.showErrorNotification(
                            e.getConnectionStatusCode(), appContext);
                    return;

                } catch (GooglePlayServicesNotAvailableException e) {
                    // Indicates a non-recoverable error; the ProviderInstaller is not able
                    // to install an up-to-date Provider.
                    e.printStackTrace();
                    return;
                }

                try {
                    InputStream credentials = appContext.getAssets().open("credentials.json");
                    ManagedChannel channel = StreamingRecognizeClient.createChannel(
                            HOSTNAME, PORT, credentials);

                    mStreamingClient = new StreamingRecognizeClient(channel, RECORDER_SAMPLERATE, appContext, fridget);
                } catch (Exception e) {
                    Log.e(Fridget.class.getSimpleName(), "Error", e);
                }

            }
        }).start();
    }


    protected void startRecording() {

        this.speak("Okay, I'm listening");

        mAudioRecord.startRecording();
        mIsRecording = true;
        mRecordingThread = new Thread(new Runnable() {
            public void run() {
                readData();
            }
        }, "AudioRecorder Thread");
        mRecordingThread.start();

        toggleRecordingButton(false);

    }

    private void toggleRecordingButton(boolean show) {

        int visibility;

        if(show) {
            visibility = View.VISIBLE;
        } else {
            visibility = View.INVISIBLE;
        }

        ((ImageButton) rootView.findViewById(R.id.voiceInputButton)).setVisibility(visibility);

    }

    private void readData() {
        byte sData[] = new  byte[mBufferSize];
        while (mIsRecording) {
            int bytesRead = mAudioRecord.read(sData, 0, mBufferSize);
            if (bytesRead > 0) {
                try {
                    mStreamingClient.recognizeBytes(sData, bytesRead);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                Log.e(getClass().getSimpleName(), "Error while reading bytes: " + bytesRead);
            }
        }
    }

    protected void destroy() {

        if (mStreamingClient != null) {
            try {
                mStreamingClient.shutdown();
            } catch (InterruptedException e) {
                Log.e(Fridget.class.getSimpleName(), "Error", e);
            }
        }

    }


    public void speak(String message) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
        }else{
            this.textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null);
        }

    }

    public void stopRecording() {

        // Stop resources
        mIsRecording = false;
        mAudioRecord.stop();

        // Start processing the request
        processSpeech();

    }

    public void processSpeech() {

        if(userSpeech != null) {

            // We've got the user's transcript; update the view
            PulsatorLayout pulsator = ((PulsatorLayout) rootView.findViewById(R.id.pulsator));
            TextView infoLabel =  (TextView) this.rootView.findViewById(R.id.infoLabel);
            ProgressBar loadingSpinner = ((ProgressBar) this.rootView.findViewById(R.id.loadingProgress));

            infoLabel.setText(userSpeech);

            // Show that we've stopped listening, and now we're processing the speech
            pulsator.stop();
            loadingSpinner.setVisibility(View.VISIBLE);

            // -------------------------------------------------------------


            // TODO: This is where we'd make a request to LUIS..
            // (Using userSpeech)

            // Update these dummy values with our dialogue response
            String dialogueRepsonse = "Ok, I heard you: " + userSpeech;
            boolean shouldContinueDialogue = true;

            // -------------------------------------------------------------

            this.speak(dialogueRepsonse); // Say the dialogue response

            if(shouldContinueDialogue) {

                // Start listening again
                loadingSpinner.setVisibility(View.INVISIBLE);
                pulsator.start();
                infoLabel.setText(R.string.listening);
                this.startRecording();

            } else {

                infoLabel.setText(dialogueRepsonse);

            }


        }

    }

}
