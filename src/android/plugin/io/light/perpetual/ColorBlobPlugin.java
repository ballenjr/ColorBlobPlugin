package plugin.io.light.perpetual;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
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
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.ORBFeatureProcessor;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class ColorBlobPlugin extends CordovaPlugin implements View.OnTouchListener, CameraGLSurfaceView.CameraTextureListener, CameraBridgeViewBase.CvCameraViewListener2 {

    public static final String TAG = "ColorBlobPlugin";

    private static CordovaInterface _cordova;
    private static CordovaWebView _webView;
    private static final List<Integer> lock = new ArrayList<>();

    private boolean mIsColorSelected = false;
    private Mat mRgba;
    private Mat lastFrame;
    private Mat snapShot;
    private Mat resizedSnapShot;
    private Mat toShow;
    private Scalar mBlobColorRgba;
    private Scalar mBlobColorHsv;
    private ColorBlobDetector mDetector;
    private Mat mSpectrum;
    private Size SPECTRUM_SIZE;
    private Scalar CONTOUR_COLOR;
    private float zoomFactor = 1.0F;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ViewGroup frame;
    private int orientation;
    private List<android.graphics.Rect> hitBoxes = new ArrayList<>();

    private class ZoomListener extends ScaleGestureDetector.
            SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));
            zoomFactor = scaleFactor;
            mDetector.resetColors();
            return super.onScale(detector);
        }
    };

    private ScaleGestureDetector zoomDetector;

    private int MAXFEATURESTODETECT = 256;

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
                        if (mOpenCvCameraView == null) return;
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
        if (zoomDetector == null) {
            zoomDetector = new ScaleGestureDetector(_cordova.getActivity().getApplication(), new ZoomListener());
            zoomDetector.setQuickScaleEnabled(true);
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
        zoomDetector.onTouchEvent(event);
//        if (zoomDetector.onTouchEvent(event))
//            return false;
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

        Mat touchedRegionRgba;
        if (resizedSnapShot == null)
            touchedRegionRgba = mRgba.submat(touchedRect);
        else
            touchedRegionRgba = resizedSnapShot.submat(touchedRect);

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

    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {

        mRgba = inputFrame.rgba();
        if (lastFrame != null) {
            lastFrame.release();
            lastFrame = new Mat();
        } else lastFrame = new Mat();
        synchronized (lastFrame) {
            Imgproc.cvtColor(mRgba, lastFrame, Imgproc.COLOR_RGBA2RGB);
        }
        Mat snap = null;
        Mat resize = null;
        if (snapShot == null) {
            resize = mRgba;
        }
        else {
            resize = snap = resizedSnapShot = snapShot.clone();
        }
        if (zoomFactor > 1F) {
            Size size = resize.size();
            int zx = (int) Math.floor(size.width * zoomFactor);
            int zy = (int) Math.floor(size.height * zoomFactor);
            if (zx % 2 != 0) zx -= 1;
            if (zy % 2 != 0) zy -= 1;
            Imgproc.resize(resize, resize, new Size(zx, zy));
            Size now = resize.size();
            Rect roi = new Rect(
                    (int) (now.width - size.width) / 2,
                    (int) (now.height - size.height) / 2,
                    (int) size.width,
                    (int) size.height
            );
            if (roi.x % 2 != 0) roi.x -= 1;
            if (roi.y % 2 != 0) roi.y -= 1;
            if (snapShot == null)
                mRgba = resize.submat(roi);
            else
                snap = resizedSnapShot = resize.submat(roi);
        }
        if (toShow == null) {
            if (snap != null && mIsColorSelected) {
                mDetector.process(snap);
                List<MatOfPoint> contours = mDetector.getContours();
                //Log.e(TAG, "Contours count: " + contours.size());
                Imgproc.drawContours(snap, contours, -1, CONTOUR_COLOR);
                return snap;
            } else if (snap != null) {
                return snap;
            } else if (mIsColorSelected) {
                mDetector.process(mRgba);
                List<MatOfPoint> contours = mDetector.getContours();
                //Log.e(TAG, "Contours count: " + contours.size());
                Imgproc.drawContours(mRgba, contours, -1, CONTOUR_COLOR);
            } else {
                MatOfPoint corners = new MatOfPoint();
                Mat gray = new Mat();
                Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.goodFeaturesToTrack(gray, corners, 2048, .15, 2);
                gray.release();
                Rect focused = Imgproc.boundingRect(corners);
                corners.release();
                Imgproc.rectangle(mRgba, new Point(focused.x, focused.y), new Point(focused.x + focused.width, focused.y + focused.height), CONTOUR_COLOR);
            }
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

            private void setColorRadius() throws JSONException {
                mDetector.setColorRadius(new Scalar(
                        args.getDouble(0),
                        args.getDouble(1),
                        args.getDouble(2),
                        0
                ));
            }

            private void setMaxColors() throws JSONException {
                mDetector.setMaxColorRanges(args.getInt(0));
            }

            private void setMaxFeaturesToDetect() throws JSONException {
                MAXFEATURESTODETECT = args.getInt(0);
            }

            private void setSelectionColor() throws JSONException {
                CONTOUR_COLOR = new Scalar(
                        args.getInt(0),
                        args.getInt(1),
                        args.getInt(2)
                );
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
                mIsColorSelected = false;
                if (toShow == null) {
                    if (mDetector != null)
                        mDetector.resetColors();
                } else
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
                this.getDataURL(false);
            }

            private Mat getDataURL(boolean isInternal) throws Exception {
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

                if (isInternal) return initialMask;

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
                return null;
            }

            private void setSnapShot() {
                snapShot = lastFrame.clone();
                zoomFactor = 1.0F;
            }

            private void clearSnapShot() {
                snapShot = resizedSnapShot = null;
                zoomFactor = 1.0F;
            }

            private void getDescriptors() throws Exception {
                long start = System.currentTimeMillis();
                Mat last = lastFrame.clone();
                Mat mask = this.getDataURL(true);
                ORBFeatureProcessor orb = ORBFeatureProcessor.create();
                orb.setMaxFeatures(MAXFEATURESTODETECT);
                MatOfKeyPoint kp = new MatOfKeyPoint();
                Mat des = new Mat();
                orb.detectAndCompute(last, mask, kp, des);
                if (des.rows() < MAXFEATURESTODETECT)
                    throw new Exception("Not enough data to describe...");
                JSONArray json = new JSONArray(des.dump());
                byte[] buf = new byte[json.length()];
                for(int i = 0; i < json.length(); i++)
                    buf[i] = (byte) json.getInt(i);
                String base64 = Base64.encodeToString(buf, Base64.DEFAULT | Base64.NO_WRAP);
                callbackContext.success(base64);
            }

            private String getHash(Mat roi) {
                roi = roi.clone();
                Imgproc.resize(roi, roi new Size(8, 8));
                Imgproc.cvtColor(roi, roi, Imgproc.COLOR_RGB2GRAY);
                avg = reduce(lambda x, y: x + y, im.getdata()) / 64.
                return reduce(lambda x, (y, z): x | (z << y),
                        enumerate(map(lambda i: 0 if i < avg else 1, im.getdata())),
                0)
            }

        });

        return true;
    }

}