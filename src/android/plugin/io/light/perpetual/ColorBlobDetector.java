package plugin.io.light.perpetual;

import android.graphics.Color;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
    private int maxColorRanges = 15;

    private final int MAXCOLORRANGES = 20;

    // Cache
    Mat mPyrDownMat = new Mat();
    Mat mHsvMat = new Mat();
    Mat mMask = new Mat();
    Mat mDilatedMask = new Mat();
    Mat mHierarchy = new Mat();

    public void setColorRadius(Scalar radius) {
        mColorRadius = radius;
    }

    public void setMaxColorRanges(int number) {
        if (number > MAXCOLORRANGES)
            maxColorRanges = MAXCOLORRANGES;
        else maxColorRanges = number;
    }

    private List<ColorRange> colors = new ArrayList<>();

    public void resetColors() {
        synchronized (colors) {
            colors.clear();
        }
    }

    private class ColorRange extends Object implements Comparable<ColorRange> {

        public Scalar upper;
        public Scalar lower;

        public ColorRange(Scalar upper, Scalar lower) {
            this.upper = upper;
            this.lower = lower;
        }

        @Override
        public int compareTo(ColorRange another) {
            double low = this.lower.val[0] + this.lower.val[1] + this.lower.val[2];
            double high = another.lower.val[0] + another.lower.val[1] + another.lower.val[2];
            return low > high ? -1 : 1;
        }

    }
    public void setHsvColor(Scalar hsvColor) {
        double minHue = (hsvColor.val[0] >= mColorRadius.val[0]) ? hsvColor.val[0]-mColorRadius.val[0] : 0;
        double maxHue = (hsvColor.val[0]+mColorRadius.val[0] <= 255) ? hsvColor.val[0]+mColorRadius.val[0] : 255;
        double minSat = hsvColor.val[1] - mColorRadius.val[1];
        double maxSat = hsvColor.val[1] + mColorRadius.val[1];
        double minVal = hsvColor.val[2] - mColorRadius.val[2];
        double maxVal = hsvColor.val[2] + mColorRadius.val[2];

        Scalar lower = new Scalar(0);
        Scalar upper = new Scalar(0);
        lower.val[0] = minHue;
        upper.val[0] = maxHue;

        lower.val[1] = minSat;
        upper.val[1] = maxSat;

        lower.val[2] = minVal;
        upper.val[2] = maxVal;

        lower.val[3] = 0;
        upper.val[3] = 255;

        Collections.sort(colors);
        synchronized (colors) {
            Boolean add = true;

            for(ColorRange current : colors.toArray(new ColorRange[] {})) {
                int index = colors.indexOf(current) + 1;
                if (index < colors.size()) {
                    ColorRange next = colors.get(index);
                    double diffHue = next.lower.val[0] - current.lower.val[0];
                    double diffSat = next.lower.val[1] - current.lower.val[1];
                    double diffVal = next.lower.val[2] - current.lower.val[2];
                    boolean minHueCanApply = diffHue < mColorRadius.val[0] && diffHue > -1*mColorRadius.val[0];
                    boolean minSatCanApply = diffSat < mColorRadius.val[1] && diffSat > -1*mColorRadius.val[1];
                    boolean minValCanApply = diffVal < mColorRadius.val[2] && diffVal > -1*mColorRadius.val[2];
                    if (minHueCanApply && minSatCanApply && minValCanApply) {
                        next.lower.val[0] += diffHue;
                        next.lower.val[1] += diffSat;
                        if (diffVal < 0) next.lower.val[2] += diffVal;
                    }
                    diffHue = next.upper.val[0] - current.upper.val[0];
                    diffSat = next.upper.val[1] - current.upper.val[1];
                    diffVal = next.upper.val[2] - current.upper.val[2];
                    boolean maxHueCanApply = diffHue < mColorRadius.val[0] && diffHue > -1*mColorRadius.val[0];
                    boolean maxSatCanApply = diffSat < mColorRadius.val[1] && diffSat > -1*mColorRadius.val[1];
                    boolean maxValCanApply = diffVal < mColorRadius.val[2] && diffVal > -1*mColorRadius.val[2];
                    if (maxHueCanApply && maxSatCanApply && maxValCanApply) {
                        next.upper.val[0] += diffHue;
                        next.upper.val[1] += diffSat;
                        if (diffVal > 0) next.upper.val[2] += diffVal;
                    }
                    if ((maxHueCanApply && maxSatCanApply && maxValCanApply) ||
                            (minHueCanApply && minSatCanApply && minValCanApply))
                        colors.remove(--index);
                }
            }


            for(ColorRange d : colors) {
                Scalar l = d.lower;
                Scalar u = d.upper;
                if (l.val[0] == minHue && u.val[0] == maxHue &&
                        l.val[1] == minSat && u.val[1] == maxSat &&
                        l.val[2] == minVal && u.val[2] == maxVal) {
                    add = false;
                    break;
                }

            }
            if (!add) return;
            colors.add(new ColorRange(upper, lower));
            if (colors.size() == maxColorRanges + 1)
                colors.remove(0);
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
            for (ColorRange bounds : colors) {
                Core.inRange(mHsvMat, bounds.lower, bounds.upper, mMask);
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
