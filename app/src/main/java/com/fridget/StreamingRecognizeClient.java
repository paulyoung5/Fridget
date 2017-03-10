package com.fridget;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1beta1.RecognitionConfig;
import com.google.cloud.speech.v1beta1.RecognitionConfig.AudioEncoding;
import com.google.cloud.speech.v1beta1.SpeechGrpc;
import com.google.cloud.speech.v1beta1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1beta1.SpeechRecognitionResult;
import com.google.cloud.speech.v1beta1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1beta1.StreamingRecognitionResult;
import com.google.cloud.speech.v1beta1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1beta1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.auth.ClientAuthInterceptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpChannelProvider;
import io.grpc.stub.StreamObserver;

/**
 * Client that sends streaming audio to Speech.Recognize and returns streaming transcript.
 */
public class StreamingRecognizeClient implements StreamObserver<StreamingRecognizeResponse> {

    private final int mSamplingRate;

    private final ManagedChannel mChannel;

    private final SpeechGrpc.SpeechStub mSpeechClient;

    private boolean mIsInitialized = false;

    private static final List<String> OAUTH2_SCOPES =
            Arrays.asList("https://www.googleapis.com/auth/cloud-platform");

    private Context appContext;

    public String response;

    public Fridget fridget;

    /**
     * Construct client connecting to Cloud Speech server at {@code host:port}.
     */
    public StreamingRecognizeClient(ManagedChannel channel, int samplingRate, Context appContext, Fridget fridget)
            throws IOException {
        this.mSamplingRate = samplingRate;
        this.mChannel = channel;
        this.appContext = appContext;
        this.fridget = fridget;

        mSpeechClient = SpeechGrpc.newStub(channel);
    }

    public void shutdown() throws InterruptedException {
        mChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    StreamObserver<StreamingRecognizeRequest> requestObserver;

    private void initializeRecognition() throws InterruptedException, IOException {

        requestObserver = mSpeechClient.streamingRecognize(this);

        RecognitionConfig config =
                RecognitionConfig.newBuilder()
                        .setEncoding(AudioEncoding.LINEAR16)
                        .setSampleRate(mSamplingRate)
                        .setLanguageCode("en-GB")
                        .build();

        StreamingRecognitionConfig streamingConfig =
                StreamingRecognitionConfig.newBuilder()
                        .setConfig(config)
                        .build();

        StreamingRecognizeRequest initial =
                StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build();

        requestObserver.onNext(initial);
    }

    public class TranscriptLoader extends AsyncTask<Void,Void,Void> {

        private List<SpeechRecognitionAlternative> alternatives;

        public TranscriptLoader(List<SpeechRecognitionAlternative> alternatives) {
            this.alternatives = alternatives;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Get the transcription

            for (SpeechRecognitionAlternative alternative : alternatives) {

                try {
                    // Pass the transcription to Fridget
                    fridget.userSpeech = alternative.getTranscript();
                } catch (Exception e) {
                    Log.e(StreamingRecognizeClient.this.getClass().getSimpleName(), "An error occurred: "+e.getMessage());
                }

            }

            return null;
        }

        @Override
        protected void onPostExecute(Void param) {
            // Now stop all resources
            fridget.stopRecording();
        }
    }

    @Override
    public void onNext(StreamingRecognizeResponse response) {

        Log.i(getClass().getSimpleName(), "Received response: " +
                TextFormat.printToString(response));

        List<StreamingRecognitionResult> results = response.getResultsList();

        for (StreamingRecognitionResult result: results) {

            if(result.getIsFinal()) {

                new TranscriptLoader(result.getAlternativesList()).execute();

            }

        }

    }

    @Override
    public void onError(Throwable error) {
        Status status = Status.fromThrowable(error);

        // Stop recording
        this.finish();

        Log.w(getClass().getSimpleName(), "recognize failed: {0}: " + status);
        Log.e(StreamingRecognizeClient.this.getClass().getSimpleName(), "Error to" +
                " Recognize.", error);
    }

    @Override
    public void onCompleted() {
        Log.i(getClass().getSimpleName(), "recognize completed.");
    }

    public void recognizeBytes(byte[] audioBytes, int size) throws IOException,
            InterruptedException {
        if (!mIsInitialized) {
            initializeRecognition();
            mIsInitialized = true;
        }
        try {
            StreamingRecognizeRequest request =
                    StreamingRecognizeRequest.newBuilder()
                            .setAudioContent(ByteString.copyFrom(audioBytes, 0, size))
                            .build();
            requestObserver.onNext(request);
        } catch (RuntimeException e) {
            Log.e(StreamingRecognizeClient.this.getClass().getSimpleName(), "Error stopping.", e);
            requestObserver.onError(e);
            throw e;
        }
    }

    public void finish() {
        Log.i(StreamingRecognizeClient.this.getClass().getSimpleName(), "onComplete.");
        requestObserver.onCompleted();
        mIsInitialized = false;
    }

    public static ManagedChannel createChannel(String host, int port, InputStream credentials)
            throws IOException {
        GoogleCredentials creds = GoogleCredentials.fromStream(credentials);
        creds = creds.createScoped(OAUTH2_SCOPES);
        OkHttpChannelProvider provider = new OkHttpChannelProvider();
        OkHttpChannelBuilder builder = provider.builderForAddress(host, port);
        ManagedChannel channel =  builder.intercept(new ClientAuthInterceptor(creds, Executors
                .newSingleThreadExecutor
                ()))
                .build();

        credentials.close();
        return channel;
    }
}
