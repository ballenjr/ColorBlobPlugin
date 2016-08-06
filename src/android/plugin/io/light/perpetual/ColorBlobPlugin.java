package plugin.io.light.perpetual;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraGLSurfaceView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class ColorBlobPlugin extends CordovaPlugin implements View.OnTouchListener, CameraGLSurfaceView.CameraTextureListener, CameraBridgeViewBase.CvCameraViewListener2 {

    public static final String TAG = "ColorBlobPlugin";

    private static CordovaInterface _cordova;
    private static CordovaWebView _webView;
    private static List<Integer> lock = new ArrayList<>();

    private boolean mIsColorSelected = false;
    private Mat mRgba;
    private Mat lastFrame;
    private Mat toShow;
    private Scalar mBlobColorRgba;
    private Scalar mBlobColorHsv;
    private ColorBlobDetector mDetector;
    private Mat mSpectrum;
    private Size SPECTRUM_SIZE;
    private Scalar CONTOUR_COLOR;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ViewGroup frame;
    private int orientation;
    private List<android.graphics.Rect> hitBoxes = new ArrayList<>();

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        Log.d(TAG, "onResume: ");
        BaseLoaderCallback loader = new BaseLoaderCallback(_cordova.getActivity().getApplicationContext()) {
            @Override
            public void onManagerConnected(int status) {
                switch (status) {
                    case LoaderCallbackInterface.SUCCESS:
                    {
                        Log.i(TAG, "OpenCV loaded successfully");
                        mOpenCvCameraView.enableView();
                        mOpenCvCameraView.setOnTouchListener(ColorBlobPlugin.this);
                    } break;
                    default:
                    {
                        super.onManagerConnected(status);
                    } break;
                }
            }
        };
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, _cordova.getActivity().getApplicationContext(), loader);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            loader.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        Log.d(TAG, "onPause: ");
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mDetector = new ColorBlobDetector();
        mSpectrum = new Mat();
        mBlobColorRgba = new Scalar(255);
        mBlobColorHsv = new Scalar(255);
        SPECTRUM_SIZE = new Size(200, 64);
        CONTOUR_COLOR = new Scalar(255,0,0,255);
    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    @Override
    public boolean onCameraTexture(int i, int i1, int i2, int i3) {
        return false;
    }

    public boolean onTouch(View v, MotionEvent event) {
        int cols = mRgba.cols();
        int rows = mRgba.rows();

        int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
        int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

        int x = (int)event.getX() - xOffset;
        int y = (int)event.getY() - yOffset;

        ///Log.i(TAG, "Touch image coordinates: (" + x + ", " + y + ")");

        if ((x < 0) || (y < 0) || (x > cols) || (y > rows)) return false;

        Rect touchedRect = new Rect();

        touchedRect.x = (x>4) ? x-4 : 0;
        touchedRect.y = (y>4) ? y-4 : 0;

        touchedRect.width = (x+4 < cols) ? x + 4 - touchedRect.x : cols - touchedRect.x;
        touchedRect.height = (y+4 < rows) ? y + 4 - touchedRect.y : rows - touchedRect.y;

        Mat touchedRegionRgba = mRgba.submat(touchedRect);

        Mat touchedRegionHsv = new Mat();
        Imgproc.cvtColor(touchedRegionRgba, touchedRegionHsv, Imgproc.COLOR_RGB2HSV_FULL);

        // Calculate average color of touched region
        mBlobColorHsv = Core.sumElems(touchedRegionHsv);
        int pointCount = touchedRect.width*touchedRect.height;
        for (int i = 0; i < mBlobColorHsv.val.length; i++)
            mBlobColorHsv.val[i] /= pointCount;

        mBlobColorRgba = converScalarHsv2Rgba(mBlobColorHsv);

        //Log.i(TAG, "Touched rgba color: (" + mBlobColorRgba.val[0] + ", " + mBlobColorRgba.val[1] +
        //", " + mBlobColorRgba.val[2] + ", " + mBlobColorRgba.val[3] + ")");

        mDetector.setHsvColor(mBlobColorHsv);

        //Imgproc.resize(mDetector.getSpectrum(), mSpectrum, SPECTRUM_SIZE);

        mIsColorSelected = true;

        touchedRegionRgba.release();
        touchedRegionHsv.release();

        return true; // don't need subsequent touch events
    }

    private int c = 0;
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();
        if (lastFrame != null) {
            lastFrame.release();
            lastFrame = new Mat();
        } else lastFrame = new Mat();
        synchronized (lastFrame) {
            Imgproc.cvtColor(mRgba, lastFrame, Imgproc.COLOR_RGBA2RGB);
        }

        if (toShow == null)
            if (mIsColorSelected) {
                mDetector.process(mRgba);
                List<MatOfPoint> contours = mDetector.getContours();
                //Log.e(TAG, "Contours count: " + contours.size());
                Imgproc.drawContours(mRgba, contours, -1, CONTOUR_COLOR);
            } else {
                //Imgproc.circle(mRgba, new Point(mRgba.width() / 2, mRgba.height() / 2), mRgba.width() / 5, new Scalar(255, 255, 255), -1);
                MatOfPoint corners = new MatOfPoint();
                Mat gray = new Mat();
                Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.goodFeaturesToTrack(gray, corners, 2048, .15, 2);
                gray.release();
                Rect focused = Imgproc.boundingRect(corners);
                corners.release();
                Imgproc.rectangle(mRgba, new Point(focused.x, focused.y), new Point(focused.x + focused.width, focused.y + focused.height), CONTOUR_COLOR);
            /*Mat mask = new Mat(mRgba.size(), CvType.CV_8UC1);
            mask.zeros(mask.size(), mask.type());
            Imgproc.circle(mask, new Point(mask.width()/2,mask.height()/2), 100, new Scalar(255), -1);
            gray.copyTo(mRgba, mask);*/
            }

        if (toShow != null) {
            while(mRgba.width() != toShow.width() || mRgba.height() != toShow.height())
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            return toShow;
        }
        return mRgba;
    }

    private Scalar converScalarHsv2Rgba(Scalar hsvColor) {
        Mat pointMatRgba = new Mat();
        Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
        Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL, 4);

        return new Scalar(pointMatRgba.get(0, 0));
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        Log.d(TAG, "initialize: ");
        _cordova = cordova;
        _webView = webView;
        orientation = _cordova.getActivity().getRequestedOrientation();

        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

        cordova.getThreadPool().execute(new Runnable() {

            @Override
            public void run() {
                String badCall = "Bad Call";
                try {
                    Method method = this.getClass().getDeclaredMethod(action);
                    method.setAccessible(true);
                    method.invoke(this);
                } catch (NoSuchMethodException e) {
                    Log.e(TAG, "run: " + action, e);
                    callbackContext.error(badCall);
                } catch (IllegalAccessException e) {
                    Log.e(TAG, "run: " + action, e);
                    callbackContext.error(badCall);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "run: " + action, e);
                    callbackContext.error(badCall);
                } catch (InvocationTargetException e) {
                    Log.e(TAG, "run: " + action, e);
                    callbackContext.error(badCall);
                } catch (Exception e) {
                    Log.e(TAG, "run: " + action, e);
                    callbackContext.error("Encounterd an error while running " + action);
                }
            }

            private void trackbox() throws JSONException {
                hitBoxes.add(new android.graphics.Rect(
                        args.getInt(0),
                        args.getInt(1),
                        args.getInt(2),
                        args.getInt(3)
                ));
                callbackContext.success();
            }

            private void untrackbox() throws JSONException {
                hitBoxes.remove(args.getInt(0));
                callbackContext.success();
            }

            private void untrackall() {
                hitBoxes.clear();
                callbackContext.success();
            }

            private void resetselection() {
                if (toShow == null)
                    if (mDetector != null)
                        mDetector.resetColors();
                    else
                        synchronized (toShow) {
                            toShow.release();
                            toShow = null;
                            if (mDetector != null)
                                mDetector.resetColors();
                        }
            }

            private void show() {
                _cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Activity activity = cordova.getActivity();
                            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            frame = (ViewGroup) activity.findViewById(android.R.id.content);

                            Resources res = activity.getResources();
                            String pack = activity.getPackageName();
                            int lid = res.getIdentifier("color_blob_detection_surface_view", "layout", pack);
                            int vid = res.getIdentifier("color_blob_detection_activity_surface_view", "integer", pack);

                            View.inflate(frame.getContext(), lid, frame);
                            mOpenCvCameraView = (CameraBridgeViewBase) activity.findViewById(vid);
                            ((View) mOpenCvCameraView.getParent()).setBackgroundColor(Color.CYAN);
                            mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
                            mOpenCvCameraView.setCvCameraViewListener(ColorBlobPlugin.this);
                            onResume(false);

                            _webView.getView().setBackgroundColor(0x00000000);

                            frame.removeView(_webView.getView());
                            frame.addView(_webView.getView(), 1);

                            frame.setBackgroundColor(0x00000000);

                            _webView.getView().setOnTouchListener(new View.OnTouchListener() {
                                @Override
                                public boolean onTouch(View v, MotionEvent e) {
                                    for(android.graphics.Rect r : hitBoxes)
                                        if (r.left < e.getX() && r.right > e.getX() &&
                                                r.top < e.getY() && r.bottom > e.getY())
                                            return false;
                                    mOpenCvCameraView.dispatchTouchEvent(e);
                                    return false;
                                }
                            });

                            callbackContext.success();
                        } catch(Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
            }

            private void close() {
                _cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mOpenCvCameraView.disableView();
                            frame.removeView((View) mOpenCvCameraView.getParent());
                            _cordova.getActivity().setRequestedOrientation(orientation);
                            callbackContext.success();
                        } catch(Exception e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
            }

            private void getDataURL() throws Exception {
                //synchronized (lock) {
                List<MatOfPoint> contours = mDetector.getContours();
                Mat last;
                synchronized (lastFrame) {
                    last = lastFrame.clone();
                }
                Rect rect = new Rect(0,0,0,0);
                MatOfPoint fBlob = new MatOfPoint();
                for(MatOfPoint blob : contours) {
                    Rect trect = Imgproc.boundingRect(blob);
                    if (trect.area() > rect.area()) {
                        rect = trect;
                        fBlob = blob;
                    }
                }

                if (!(0 <= rect.x && 0 <= rect.width && rect.x + rect.width <= last.cols() &&
                        0 <= rect.y && 0 <= rect.height && rect.y + rect.height <= last.rows())) {
                    throw new Exception("Bad Selection");
                }

                List<MatOfPoint> oneBlob = new ArrayList<>();

                oneBlob.add(fBlob);
                Mat initialMask = new Mat(lastFrame.size(), CvType.CV_8UC1, new Scalar(0));
                Imgproc.drawContours(initialMask, oneBlob, -1, new Scalar(255), -1);

                Mat foreground = null;
                Mat roi = null;
                toShow = new Mat();
                synchronized (toShow) {
                    roi = new Mat(last.size(), CvType.CV_8UC4);
                    Imgproc.cvtColor(last, last, Imgproc.COLOR_RGB2RGBA);
                    Log.d(TAG, "getDataURL: verified");
                    last.copyTo(roi, initialMask);
                    foreground = roi.submat(rect);
                    roi.copyTo(toShow);
                    Imgproc.cvtColor(toShow, toShow, Imgproc.COLOR_RGBA2RGB);
                }

                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (toShow != null)
                            synchronized (toShow) {
                                toShow.release();
                                toShow = null;
                            }
                    }
                }, 5000);

                MatOfByte buf = new MatOfByte();
                Mat bgr = new Mat();
                Imgproc.cvtColor(foreground, bgr, Imgproc.COLOR_RGBA2BGRA);
                Imgcodecs.imencode(".png", bgr, buf);
                String base64 = Base64.encodeToString(buf.toArray(), Base64.DEFAULT | Base64.NO_WRAP);
                callbackContext.success("data:image/png;base64,"+base64);

                initialMask.release();
                roi.release();
                last.release();
                bgr.release();
                buf.release();
                foreground.release();
                //}
            }

        });

        return true;
    }

}