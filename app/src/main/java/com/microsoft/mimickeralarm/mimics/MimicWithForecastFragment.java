/*
 *
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license.
 *
 * Project Oxford: http://ProjectOxford.ai
 *
 * Project Oxford Mimicker Alarm Github:
 * https://github.com/Microsoft/ProjectOxford-Apps-MimickerAlarm
 *
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License:
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED ""AS IS"", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.microsoft.mimickeralarm.mimics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.microsoft.mimickeralarm.R;
import com.microsoft.mimickeralarm.appcore.AlarmMainActivity;
import com.microsoft.mimickeralarm.mimics.MimicFactory.MimicResultListener;
import com.microsoft.mimickeralarm.model.ShortForecast;
import com.microsoft.mimickeralarm.ringing.ShareFragment;
import com.microsoft.mimickeralarm.utilities.Loggable;
import com.microsoft.mimickeralarm.utilities.Logger;
import com.microsoft.projectoxford.speechrecognition.Confidence;
import com.microsoft.projectoxford.speechrecognition.ISpeechRecognitionServerEvents;
import com.microsoft.projectoxford.speechrecognition.RecognitionResult;
import com.microsoft.projectoxford.speechrecognition.RecognitionStatus;
import com.microsoft.projectoxford.speechrecognition.RecognizedPhrase;
import com.microsoft.projectoxford.speechrecognition.SpeechRecognitionMode;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.jsoup.Jsoup;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * Base class for all camera based mimic games
 * it provides a capture button, countdown timer, game state banner, and a preview surface
 * Classes that inherits from this will only have to override the verify function.
 **/
@SuppressWarnings("deprecation")
public class MimicWithForecastFragment extends Fragment implements IMimicImplementation, View.OnClickListener {

    private static final String LOGTAG = "MimicWithForecastFragment";
    private final static float DIFFERENCE_SUCCESS_THRESHOLD = 0.3f;
    private final static float DIFFERENCE_PERFECT_THRESHOLD = 0.1f;
    private static final int TIMEOUT_MILLISECONDS = 50000;
    // Max width for sending to Project Oxford, reduce latency
    private static final int MAX_WIDTH = 500;
    private static final int LIGHT_THRESHOLD = 15;

    protected static int CameraFacing = Camera.CameraInfo.CAMERA_FACING_FRONT;
    MimicResultListener mCallback;
    private ShareFragment.ShareResultListener msCallback;
    private CameraPreview mCameraPreview;
    private IMimicMediator mStateManager;
    private TextView mTextResponse;
    private Uri mSharableUri;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private SensorEventListener mLightSensorListener;
    private Toast mTooDarkToast;
    private ToggleButton mFlashButton;
    private String mQuestion = null;





    String url = "http://www.kweather.co.kr/forecast/forecast_lifestyle.html";
    private TextToSpeech myTTS;
    private String forecastKor;
    private String forecastLocal = " ";
    private String forecastInfo = " ";
    private String mSuccessMessage;

    Handler handler = new Handler();
    private Runnable mSharingFragmentDismissTask;
    private Runnable mToastAutoDismiss;
    private Handler mHandler;
    private String mShareableUri;

    private Point mSize;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_forecast_weather_mimic, container, false);
        ProgressButton progressButton = (ProgressButton) view.findViewById(R.id.capture_button);
        progressButton.setReadyState(ProgressButton.State.ReadyAudio);

        mStateManager = new MimicStateManager();
        mStateManager.registerCountDownTimer(
                (CountDownTimerView) view.findViewById(R.id.countdown_timer), TIMEOUT_MILLISECONDS);
        mStateManager.registerStateBanner((MimicStateBanner) view.findViewById(R.id.mimic_state));
        mStateManager.registerProgressButton(progressButton, MimicButtonBehavior.AUDIO);
        mStateManager.registerMimic(this);
        mTextResponse = (TextView) view.findViewById(R.id.understood_text);
        mTextResponse.setOnClickListener(this);

        myTTS = new TextToSpeech(getActivity().getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.ERROR) {
                    myTTS.setLanguage(Locale.KOREAN);
                }
            }
        });

//        Button finishButton = (Button) view.findViewById(R.id.fin_button);
//        Bundle args = getArguments();
//        mShareableUri = args.getString("shareable-uri");
//
//        // Set up timer to dismiss the sharing fragment if there is no user interaction with the buttons
//        mSharingFragmentDismissTask = new Runnable() {
//            @Override
//            public void run() {
//                finishShare();
//            }
//        };
//        mHandler = new Handler();
        return view;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.understood_text:
                new ReceiveShortForecast().execute();
                JsoupAsyncTask jsoupAsyncTask = new JsoupAsyncTask();
                jsoupAsyncTask.execute();
                break;
//            case R.id.fin_button:
//                finishShare();
//                break;
        }
    }

    public void finishShare() {
        mHandler.removeCallbacks(mSharingFragmentDismissTask);
        if (msCallback != null) {
            msCallback.onShareCompleted();
        }
    }

    public interface ShareResultListener {
        void onShareCompleted();
        void onRequestLaunchShareAction();
    }

//    private void verify() {
//        gameSuccess(0);
//        if (mUnderstoodText == null) {
//            gameFailure(true);
//            return;
//        }
//
//        double difference = (double)levenshteinDistance(mUnderstoodText, mQuestion) / (double)mQuestion.length();
//
//        Loggable.UserAction userAction = new Loggable.UserAction(Loggable.Key.ACTION_GAME_TWISTER_SUCCESS);
//        Resources resources = getResources();
//        String[] questions = resources.getStringArray(R.array.tongue_twisters);
//        mQuestion = questions[new Random().nextInt(questions.length)];
//        userAction.putProp(Loggable.Key.PROP_QUESTION, mQuestion);
//        userAction.putProp(Loggable.Key.PROP_DIFF, 0);

//        if (difference <= DIFFERENCE_SUCCESS_THRESHOLD) {
//            Logger.track(userAction);
//            gameSuccess(0);
//        }
//        else {
//            userAction.Name = Loggable.Key.ACTION_GAME_TWISTER_FAIL;
//            Logger.track(userAction);
//            gameFailure(true);
//        }
//    }

//    protected void gameSuccess(double difference) {
//        mSuccessMessage = getString(R.string.mimic_success_message);
//        if (difference <= DIFFERENCE_PERFECT_THRESHOLD) {
//            mSuccessMessage = getString(R.string.mimic_twister_perfect_message);
//        }
//
//
//        createSharableBitmap();
//        mStateManager.onMimicSuccess(mSuccessMessage);
//    }

//    private void createSharableBitmap() {
//        Bitmap sharableBitmap = Bitmap.createBitmap(getView().getWidth(), getView().getHeight(), Bitmap.Config.ARGB_8888);
//        Canvas canvas = new Canvas(sharableBitmap);
//        canvas.drawColor(ContextCompat.getColor(getContext(), R.color.white));
//
//        // Load the view for the sharable. This will be drawn to the bitmap
//        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
//        LinearLayout layout = (LinearLayout) inflater.inflate(R.layout.fragment_sharable_tongue_twister, null);
//
//        TextView textView = (TextView) layout.findViewById(R.id.twister_sharable_tongue_twister);
//        textView.setText("QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ");
//
//        textView = (TextView) layout.findViewById(R.id.twister_sharable_i_said);
//        textView.setText("UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU");
//
//        textView = (TextView) layout.findViewById(R.id.mimic_twister_share_success);
//        textView.setText(mSuccessMessage);
//
//        // Perform the layout using the dimension of the bitmap
//        int widthSpec = View.MeasureSpec.makeMeasureSpec(canvas.getWidth(), View.MeasureSpec.EXACTLY);
//        int heightSpec = View.MeasureSpec.makeMeasureSpec(canvas.getHeight(), View.MeasureSpec.EXACTLY);
//        layout.measure(widthSpec, heightSpec);
//        layout.layout(0, 0, layout.getMeasuredWidth(), layout.getMeasuredHeight());
//
//        // Draw the generated view to canvas
//        layout.draw(canvas);
//
//        String title = getString(R.string.app_short_name) + ": " + getString(R.string.mimic_twister_name);
//        mSharableUri = ShareFragment.saveShareableBitmap(getActivity(), sharableBitmap, title);
//    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mCallback = (MimicResultListener) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallback = null;
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mSensorManager != null && mLightSensorListener != null) {
            mSensorManager.registerListener(mLightSensorListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
        }

        mStateManager.start();
    }

    @Override
    public void onStop() {
        super.onStop();
        mStateManager.stop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.flush();
        if (myTTS != null) {
            myTTS.stop();
            myTTS.shutdown();
        }

        if (mShareableUri != null && mShareableUri.length() > 0) {
            new Thread(new Runnable(){
                @Override
                public void run() {
                    File deleteFile = new File(mShareableUri);
                    boolean deleted = deleteFile.delete();
                    if (!deleted) {
                        Loggable.AppError appError = new Loggable.AppError(Loggable.Key.APP_ERROR, "Failed to delete shareable");
                        Logger.track(appError);
                    }
                }
            }).start();
        }
        mHandler.removeCallbacks(mToastAutoDismiss);
    }

    @Override
    public void initializeCapture() {
    }

    @Override
    public void startCapture() {
    }

    @Override
    public void stopCapture() {
    }

    @Override
    public void onCountDownTimerExpired() {
//        verify();
//        getActivity().getSupportFragmentManager().beginTransaction().addToBackStack("").replace()

    }

    @Override
    public void onSucceeded() {
    }

    @Override
    public void onFailed() {
    }

    @Override
    public void onInternalError() {
    }

    protected class GameResult {
        boolean success = false;
        String message = null;
        Uri shareableUri = null;
        String question = null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void ttsGreater21(String str) {
        String utteranceId = this.hashCode() + "";
        myTTS.speak(str, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    class JsoupAsyncTask extends AsyncTask<URL, Integer, Long> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Long doInBackground(URL... params) {

            try {
                org.jsoup.nodes.Document doc = Jsoup.connect(url).get();
                forecastKor = doc.select(".lifestyle_condition_content").html();
                int idx = forecastKor.indexOf("<br>");
                forecastKor = forecastKor.substring(0, idx - 1);

            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            super.onPostExecute(result);
            forecastInfo = forecastKor + forecastLocal;
            mTextResponse.setText(forecastInfo);
            ttsGreater21(forecastInfo);
        }
    }

    class ReceiveShortForecast extends AsyncTask<URL, Integer, Long> {
        ArrayList<ShortForecast> shortForecasts = new ArrayList<>();

        protected Long doInBackground(URL... urls) {
            String url = "http://www.kma.go.kr/wid/queryDFSRSS.jsp?zone=4119071000";
            OkHttpClient client = new OkHttpClient();
            Request req = new Request.Builder().url(url).build();
            Response res = null;

            try {
                res = client.newCall(req).execute();
                parseXML(res.body().string());

            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        protected void onPostExecute(Long result) {
            super.onPostExecute(result);
            forecastLocal = "부천시 심곡본동 현재날씨정보는 " + shortForecasts.get(0).getWfKor() + " 입니다 " +
                    "현재 시간 온도는 " + shortForecasts.get(0).getTemp() + " 도 이며 " +
                    "강수확률은 " + shortForecasts.get(0).getPop() + " 퍼센트 " +
                    "습도는 " + shortForecasts.get(0).getReh() + " 퍼센트 입니다 감사합니다" + "\n";
        }

        void parseXML(String xml) {
            try {
                String tagName = "";
                boolean onHour = false;
                boolean onDay = false;
                boolean onTemp = false;
                boolean onTmx = false;
                boolean onTmn = false;
                boolean onWfKor = false;
                boolean onPop = false;
                boolean onReh = false;
                boolean onEnd = false;
                boolean isItemTag1 = false;
                int i = 0;

                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(new StringReader(xml));
                int eventType = parser.getEventType();

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (tagName.equals("data")) {
                            shortForecasts.add(new ShortForecast());
                            onEnd = false;
                            isItemTag1 = true;
                        }
                    } else if (eventType == XmlPullParser.TEXT && isItemTag1) {
                        if (tagName.equals("hour") && !onHour) {
                            shortForecasts.get(i).setHour(parser.getText());
                            onHour = true;
                        }
                        if (tagName.equals("day") && !onDay) {
                            shortForecasts.get(i).setDay(parser.getText());
                            onDay = true;
                        }
                        if (tagName.equals("temp") && !onTemp) {
                            shortForecasts.get(i).setTemp(parser.getText());
                            onTemp = true;
                        }
                        if (tagName.equals("tmx") && !onTmx) {
                            shortForecasts.get(i).setTmx(parser.getText());
                            onTmx = true;
                        }
                        if (tagName.equals("tmn") && !onTmn) {
                            shortForecasts.get(i).setTmn(parser.getText());
                            onTmn = true;
                        }

                        if (tagName.equals("wfKor") && !onWfKor) {
                            shortForecasts.get(i).setWfKor(parser.getText());
                            onWfKor = true;
                        }
                        if (tagName.equals("pop") && !onPop) {
                            shortForecasts.get(i).setPop(parser.getText());
                            onPop = true;
                        }
                        if (tagName.equals("reh") && !onReh) {
                            shortForecasts.get(i).setReh(parser.getText());
                            onReh = true;
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if (tagName.equals("s06") && !onEnd) {
                            i++;
                            onHour = false;
                            onDay = false;
                            onTemp = false;
                            onTmx = false;
                            onTmn = false;
                            onWfKor = false;
                            onPop = false;
                            onReh = false;
                            isItemTag1 = false;
                            onEnd = true;
                        }
                    }
                    eventType = parser.next();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}


