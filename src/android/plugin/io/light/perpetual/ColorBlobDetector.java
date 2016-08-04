package plugin.io.light.perpetual;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

public class ColorBlobDetector {
    // Lower and Upper bounds for range checking in HSV color space
    private String LOWERKEY = "LOWER";
    private String UPPERKEY = "UPPER";
    private Scalar mLowerBound = new Scalar(0);
    private Scalar mUpperBound = new Scalar(0);
    private Scalar UPSCALAR = new Scalar(4,4);
    private Scalar WHITE = new Scalar(255,255,255);
    // Minimum contour area in percent for contours filtering
    private static double mMinContourArea = 0.1;
    // Color radius for range checking in HSV color space
    private Scalar mColorRadius = new Scalar(10,25,25,0);
    //private Mat mSpectrum = new Mat();
    private List<MatOfPoint> mContours = new ArrayList<>();

    // Cache
    Mat mPyrDownMat = new Mat();
    Mat mHsvMat = new Mat();
    Mat mMask = new Mat();
    Mat mDilatedMask = new Mat();
    Mat mHierarchy = new Mat();

    public void setColorRadius(Scalar radius) {
        mColorRadius = radius;
    }

    private List<Dictionary<String, Scalar>> colors = new ArrayList<>();

    public void resetColors() {
        colors.clear();
    }

    public void setHsvColor(Scalar hsvColor) {
        double minH = (hsvColor.val[0] >= mColorRadius.val[0]) ? hsvColor.val[0]-mColorRadius.val[0] : 0;
        double maxH = (hsvColor.val[0]+mColorRadius.val[0] <= 255) ? hsvColor.val[0]+mColorRadius.val[0] : 255;

        mLowerBound.val[0] = minH;
        mUpperBound.val[0] = maxH;

        mLowerBound.val[1] = hsvColor.val[1] - mColorRadius.val[1];
        mUpperBound.val[1] = hsvColor.val[1] + mColorRadius.val[1];

        mLowerBound.val[2] = hsvColor.val[2] - mColorRadius.val[2];
        mUpperBound.val[2] = hsvColor.val[2] + mColorRadius.val[2];

        mLowerBound.val[3] = 0;
        mUpperBound.val[3] = 255;

        synchronized (colors) {
            Boolean add = true;
            for(Dictionary<String, Scalar> d : colors) {
                Scalar l = d.get(LOWERKEY);
                Scalar u = d.get(UPPERKEY);
                if (l.val[0] == minH && u.val[0] == maxH &&
                        l.val[1] == mLowerBound.val[1] &&
                        u.val[1] == mUpperBound.val[1] &&
                        l.val[2] == mLowerBound.val[2] &&
                        u.val[2] == mUpperBound.val[2]) {
                    add = false;
                    break;
                }
            }
            if (!add) return;
            Dictionary<String, Scalar> dict = new Hashtable<>();
            dict.put(UPPERKEY, mUpperBound.clone());
            dict.put(LOWERKEY, mLowerBound.clone());
            colors.add(dict);
        }
    }

    public void setMinContourArea(double area) {
        mMinContourArea = area;
    }

    public void process(Mat rgbaImage) {
        Imgproc.pyrDown(rgbaImage, mPyrDownMat);
        Imgproc.pyrDown(mPyrDownMat, mPyrDownMat);

        Imgproc.cvtColor(mPyrDownMat, mHsvMat, Imgproc.COLOR_RGB2HSV_FULL);

        List<MatOfPoint> contours = new ArrayList<>();
        synchronized (colors) {
            for (Dictionary<String, Scalar> bounds : colors) {
                Core.inRange(mHsvMat, bounds.get(LOWERKEY), bounds.get(UPPERKEY), mMask);
                Imgproc.dilate(mMask, mDilatedMask, new Mat());
                Imgproc.findContours(mDilatedMask, contours, mHierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            }
        }

        // Filter contours by area and resize to fit the original image size
        Mat mat = new Mat(rgbaImage.size(), CvType.CV_8UC1);
        Imgproc.drawContours(mat, contours, -1, WHITE, -1);
        contours.clear();
        Imgproc.findContours(mat, contours, mHierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // Find max contour area
        double maxArea = 0;
        Iterator<MatOfPoint> each = contours.iterator();
        while (each.hasNext()) {
            MatOfPoint wrapper = each.next();
            double area = Imgproc.contourArea(wrapper);
            if (area > maxArea)
                maxArea = area;
        }

        mContours.clear();
        each = contours.iterator();
        while (each.hasNext()) {
            MatOfPoint contour = each.next();
            if (Imgproc.contourArea(contour) > mMinContourArea*maxArea) {
                Core.multiply(contour, UPSCALAR, contour);
                mContours.add(contour);
            }
        }

    }

    public List<MatOfPoint> getContours() {
        return mContours;
    }
}
