package com.MobileSLAM.RosCameraCapture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;

import java.nio.ShortBuffer;

public class CameraUtil {

    private final static String TAG = CameraUtil.class.getSimpleName();

    static final Size[] COLOR_OUTPUT_SIZES = new Size[] {
            new Size(4032, 3024),   // 0, scale factor: 1.0
            new Size(1440, 1080),   // 1, scale factor: 0.3571
            new Size(960, 720),     // 2, scale factor: 0.2381
            new Size(640, 480),     // 3, scale factor: 0.1587
            new Size(320, 240),     // 4, scale factor: 0.0794
            new Size(2560, 1920),   // 5, scale factor: 0.6349
    };

    static final Size[] DEPTH_OUTPUT_SIZES = new Size[] {
            new Size(640, 480),     // scale factor: 1.0
            new Size(320, 240)      // scale factor: 0.5
    };

    static public CameraParam depthCameraParam = new CameraParam(
            // frame size
            DEPTH_OUTPUT_SIZES[1].getWidth(),
            DEPTH_OUTPUT_SIZES[1].getHeight(),
            0.5,
            // intrinsics
            536.9581,
            536.7106,
            312.9077,
            233.22255,
            0,
            // extrinsics
            -0.011234,
            0,
            0,
            0.70304,
            -0.71113,
            0.00172,
            0,
            // distortion
            0.32826,
            -0.56677,
            0.12383,
            0,
            0
    );

    static public CameraParam colorCameraParam = new CameraParam(
            // frame size
            COLOR_OUTPUT_SIZES[4].getWidth(),
            COLOR_OUTPUT_SIZES[4].getHeight(),
            0.0794,
            // intrinsics
            3054.3071,
            3052.0754,
            1990.2135,
            1512.378,
            0,
            // extrinsics
            0,
            0,
            0,
            0.7071,
            -0.7071,
            0,
            0,
            // distortion
            0.05797,
            -0.05520,
            0.00144,
            0,
            0
    );

    /**
     * Parse Android depth output to real depth value in millimeter and measurement confidence
     * Measurement with confidence lower than threshold will be set as 0
     * @param shortData
     * @param width
     * @param height
     * @param confidenceThreshold threshold for valid depth measurement
     * @return depth value in short
     */
    static public native short[] parseDepth16(short[] shortData, int width, int height, float confidenceThreshold);

    /**
     * Convert YUV frame into RGBA
     * @param yData
     * @param uData
     * @param vData
     * @param yRowStride
     * @param uvRowStride
     * @param uvPixelStride
     * @param width
     * @param height
     * @return byte array for each channel, each pixel takes 4 bytes, R G B A respectively.
     */
    public static native byte[] convertYUVToBGRA(byte[] yData, byte[] uData, byte[] vData, int yRowStride, int uvRowStride, int uvPixelStride, int width, int height);


    /**
     * Convert YUV frame into RGBA
     * Returned in ARGB(8888),
     * For Android Bitmap rendering
     * @param yData
     * @param uData
     * @param vData
     * @param yRowStride
     * @param uvRowStride
     * @param uvPixelStride
     * @param width
     * @param height
     * @return int array for ARGB(8888)
     */
    public static native int[] convertYUVToARGBUint32(byte[] yData, byte[] uData, byte[] vData, int yRowStride, int uvRowStride, int uvPixelStride, int width, int height);

    /**
     * Convert YUV frame into RGB using opencv library
     * Returned bytes: R(8) G(8) B(8) A(8)
     * For ROS sensor_msgs/Image message
     * @param yData
     * @param uData
     * @param vData
     * @param yRowStride
     * @param uvRowStride
     * @param uvPixelStride
     * @param width
     * @param height
     * @return byte array for each channel, each pixel takes 4 bytes,
     */
    public static native byte[] convertYUVToARGB_opencv(byte[] yData, byte[] uData, byte[] vData, int yRowStride, int uvRowStride, int uvPixelStride, int width, int height);

    /**
     * Convert depth value in millimeter into grayscale image
     * Returned in ARGB(8888)
     * For Android Bitmap rendering
     * @param shortDepthValues
     * @param width
     * @param height
     * @param maxDepthThreshold
     * @return int array,
     */
    public static native int[] convertShortToARGB(short[] shortDepthValues, int width, int height, short maxDepthThreshold);

    /**
     * Normalize short(16bit) array to byte(8bit) according to max depth threshold
     * @param shortData short array
     * @param length array size
     * @param maxDepthThreshold maximum of measured depth, in millimeter
     * @return
     */
    public static native byte[] convertShortToGray(short[] shortData, int length, short maxDepthThreshold);

    /**
     * Convert Java short data into uint16 byte array
     * @param shortData
     * @param length
     * @return
     */
    public static native byte[] convertShortToByte(short[] shortData, int length);

    public static short[] undistortion(short[] input, CameraParam camParam) {

        double fx = camParam._fx;
        double fy = camParam._fy;
        double cx = camParam._cx;
        double cy = camParam._cy;
        double k1 = camParam._k1;
        double k2 = camParam._k2;
        double k3 = camParam._k3;
        double p1 = camParam._p1;
        double p2 = camParam._p2;

        int width = camParam.frameWidth;
        int height = camParam.frameHeight;

        short[] output = new short[input.length];

        for(int v = 0; v < height; v++){
            for(int u = 0; u < width; u++){

                // map image pixel(u,v) to point(x,y)
                double x = (u - cx) / fx;
                double y = (v - cy) / fy;
                // distorted point(x,y) to new point
                double r = Math.sqrt( x*x + y*y);
                double x_distorted = x * (1 + k1*r*r + k2*r*r*r*r + k3*r*r*r*r*r*r) + 2*p1*x*y + p2*(r*r + 2*x*x);
                double y_distorted = y * (1 + k1*r*r + k2*r*r*r*r + k3*r*r*r*r*r*r) + p1*(r*r + 2*y*y) + 2*p2*x*y;
                // map distorted point back to new pixel on image
                double u_distorted = fx * x_distorted + cx;
                double v_distorted = fy * y_distorted + cy;

                if(u_distorted >= 0 && v_distorted >= 0 && u_distorted <= width && v_distorted <= height){
                    output[v*width + u] = input[(int)v_distorted * width + (int)u_distorted];
                }else{
                    output[v*width + u] = 0;
                }
            }
        }

        return output;

    }


    public static int[] undistortion(int[] input, CameraParam camParam) {

        double fx = camParam._fx;
        double fy = camParam._fy;
        double cx = camParam._cx;
        double cy = camParam._cy;
        double k1 = camParam._k1;
        double k2 = camParam._k2;
        double k3 = camParam._k3;
        double p1 = camParam._p1;
        double p2 = camParam._p2;

        int width = camParam.frameWidth;
        int height = camParam.frameHeight;
        int[] output = new int[width*height];

        for(int v = 0; v < height; v++){
            for(int u = 0; u < width; u++){

                // map image pixel(u,v) to point(x,y)
                double x = (u - cx) / fx;
                double y = (v - cy) / fy;
                // distorted point(x,y) to new point
                double r = Math.sqrt( x*x + y*y);
                double x_distorted = x * (1 + k1*r*r + k2*r*r*r*r + k3*r*r*r*r*r*r) + 2*p1*x*y + p2*(r*r + 2*x*x);
                double y_distorted = y * (1 + k1*r*r + k2*r*r*r*r + k3*r*r*r*r*r*r) + p1*(r*r + 2*y*y) + 2*p2*x*y;
                // map distorted point back to new pixel on image
                double u_distorted = fx * x_distorted + cx;
                double v_distorted = fy * y_distorted + cy;

                if(u_distorted >= 0 && v_distorted >= 0 && u_distorted <= width && v_distorted <= height){
                    output[v*width + u] = input[(int)v_distorted * width + (int)u_distorted];
                }else{
                    output[v*width + u] = 0;
                }
            }
        }
        return output;
    }


    public static short[] depthRegister(short[] depth, CameraParam depthParam, CameraParam colorParam){

        int width = depthParam.frameWidth;
        int height = depthParam.frameHeight;

        double qx = depthParam._qx;
        double qy = depthParam._qy;
        double qz = depthParam._qz;
        double qw = depthParam._qw;

        double tx = depthParam._tx;
        double ty = depthParam._ty;
        double tz = depthParam._tz;

        double qx_2 = colorParam._qx;
        double qy_2 = colorParam._qy;
        double qz_2 = colorParam._qz;
        double qw_2 = colorParam._qw;

        short[] res = new short[width * height];
        for (int i = 0; i < width * height; i++) res[i] = 0;

        for(int v = 0; v < height; v++){
            for(int u = 0; u < width; u++){

                // 3d point of depth pixel at phone coordinate, in meter
                double depth_z = depth[ v * width + u ] / 1000.0f;
                if(depth_z == 0) continue;

                // depth image to depth camera
                double depth_x = (u - depthParam._cx) / depthParam._fx * depth_z;
                double depth_y = (v - depthParam._cy) / depthParam._fy * depth_z;

                // transform 3d point to sensor coordinate
                double color_x = depth_x*(1-2*qy*qy-2*qz*qz) + depth_y*(2*qx*qy+2*qz*qw) + depth_z*(2*qx*qz-2*qy*qw) + tx;
                double color_y = depth_x*(2*qx*qy-2*qz*qw) + depth_y*(1-2*qx*qx-2*qz*qz) + depth_z*(2*qy*qz+2*qx*qw) + ty;
                double color_z = depth_x*(2*qx*qz+2*qy*qw) + depth_y*(2*qy*qz-2*qx*qw) + depth_z*(1-2*qx*qx-2*qy*qy) + tz;

                // transform from sensor coordinate to color camera coordinate
                double color_x_2 = color_x*(1-2*qy_2*qy_2-2*qz_2*qz_2) + color_y*(2*qx_2*qy_2+2*qz_2*qw_2) + color_z*(2*qx_2*qz_2-2*qy_2*qw_2);
                double color_y_2 = color_x*(2*qx_2*qy_2-2*qz_2*qw_2) + color_y*(1-2*qx_2*qx_2-2*qz_2*qz_2) + color_z*(2*qy_2*qz_2+2*qx_2*qw_2);
                double color_z_2 = color_x*(2*qx_2*qz_2+2*qy_2*qw_2) + color_y*(2*qy_2*qz_2-2*qx_2*qw_2) + color_z*(1-2*qx_2*qx_2-2*qy_2*qy_2);

                // color camera to color image
                // using intrinsics of color camera under 320x240 resolution
                double color_u = 242.3898f * color_x_2 / color_z_2 + 157.9433f;
                double color_v = 242.2127f * color_y_2 / color_z_2 + 120.0223f;

                // assign depth value to new pixel
                if(color_u >= 0 && color_v >=0 && color_u <= width && color_v <= height){
                    res[(int)color_v * width + (int)color_u] = depth[v * width + u];
                }
            }
        }
        return res;
    }


    public static double[] convertQuatToMat(double[] quaternion){

        if(quaternion.length != 4){
            throw new IllegalArgumentException("Expect Array of 4");
        }

        double x = quaternion[0];
        double y = quaternion[1];
        double z = quaternion[2];
        double w = quaternion[3];

        double[] rotationMat = new double[] {
            1 - 2*y*y - 2*z*z,  2*x*y - 2*z*w,      2*x*z + 2*y*w,
            2*x*y + 2*z*w,      1 - 2*x*x - 2*z*z,  2*y*z - 2*x*w,
            2*x*z - 2*y*w,      2*y*z + 2*x*w,      1 - 2*x*x - 2*y*y
        };

        return rotationMat;
    }


    /**
     * Render Bitmap to surface
     * @param bitmap
     * @param view
     */
    public static void renderBitmapToTextureview(Bitmap bitmap, TextureView view){
        Canvas canvas = view.lockCanvas();
        assert canvas != null;
        canvas.drawBitmap(bitmap, 0, 0, null);
        view.unlockCanvasAndPost(canvas);
    }

    /**
     * Camera parameters
     * Camera Intrinsics: fx, fy, cx, cy, s
     * Camera Extrinsics, translation: tx, ty, tz
     * Camera Extrinsics, rotation: qx, qy, qz, qw
     * Lens Distortion: k1, k2, k3, p1, p2
     * Scale Factor, adjust intrinsics according to output frame size
     */
    static public class CameraParam{

        public double _scaleFactor;
        public int frameWidth;
        public int frameHeight;

        // Camera Intrinsics
        public double _fx;
        public double _fy;
        public double _cx;
        public double _cy;
        public double _s;

        // Camera Extrinsics, sensor coordinate
        public double _tx;   // horizontal points to right
        public double _ty;   // vertical points up
        public double _tz;   // towards outside of the screen

        public double _qx;
        public double _qy;
        public double _qz;
        public double _qw;

        // Distortion Param
        public double _k1;
        public double _k2;
        public double _k3;
        public double _p1;
        public double _p2;

        private double DEFAULT_ASPECT_RATIO = 4.0/3.0;

        public CameraParam() {}

        public CameraParam(int width, int height, double scaleFactor,
                           double fx, double fy, double cx, double cy, double s,
                           double tx, double ty, double tz,
                           double qx, double qy, double qz, double qw,
                           double k1, double k2, double k3, double p1, double p2) {

            setFrameSize(width, height, scaleFactor);
            setInrinsics(fx, fy, cx, cy, s);
            setExtrinsic(tx, ty, tz, qx, qy, qz, qw);
            setDistortionParam(k1, k2, k3, p1, p2);
        }

        public void setFrameSize(int width, int height, double scaleFactor){
            if((double)width/height == DEFAULT_ASPECT_RATIO){
                frameWidth = width;
                frameHeight = height;
                _scaleFactor = scaleFactor;
            }else{
                throw new IllegalArgumentException("Size not match default aspect ratio: " + String.valueOf(DEFAULT_ASPECT_RATIO));
            }
        }

        public double[] getFrameSize(){ return new double[] {frameWidth, frameHeight}; }

        public void setInrinsics(double fx, double fy, double cx, double cy, double s){
            _fx = fx * _scaleFactor;
            _fy = fy * _scaleFactor;
            _cx = cx * _scaleFactor;
            _cy = cy * _scaleFactor;
            _s = s;
        }

        public void setInrinsics(double[] intrinsics){

            if (intrinsics.length != 5){
                Log.e(TAG, "Intrinsics expect Array of 5 double, but get size: " + intrinsics.length);
                return;
            }

            _fx = intrinsics[0] * _scaleFactor;
            _fy = intrinsics[1] * _scaleFactor;
            _cx = intrinsics[2] * _scaleFactor;
            _cy = intrinsics[3] * _scaleFactor;
            _s = intrinsics[4];
        }

        public double[] getIntrinsics(){
            return new double[] {_fx, _fy, _cx, _cy};
        }

        public double[] getK(){
            return new double[] {_fx, 0, _cx, 0, _fy, _cy, 0, 0, 1};
        }

        public void setExtrinsic(double tx, double ty, double tz, double qx, double qy, double qz, double qw){
            _tx = tx;
            _ty = ty;
            _tz = tz;
            _qx = qx;
            _qy = qy;
            _qz = qz;
            _qw = qw;
        }

        public void setExtrinsic(double[] translation, double[] rotation){

            if (translation.length != 3){
                Log.e(TAG, "Extrinsic expect Array of 3 double for translation parameter, but get size: " + translation.length);
                return;
            }

            if (rotation.length != 4){
                Log.e(TAG, "Extrinsic expect Array of 4 double for rotation parameter, but get size: " + translation.length);
                return;
            }

            _tx = translation[0];
            _ty = translation[1];
            _tz = translation[2];

            _qx = rotation[0];
            _qy = rotation[1];
            _qz = rotation[2];
            _qw = rotation[3];
        }

        public double[] getTranslation(){
            return new double[] {_tx, _ty, _tz};
        }

        public double[] getRotation() { return new double[] {_qx, _qy, _qz, _qw}; }

//        public double[] getR() { return convertQuatToMat(getRotation()); }

        public double[] getR() {
            return new double[] {
                1, 0, 0,
                0, 1, 0,
                0, 0, 1
            };
        }

        public void setDistortionParam(double k1, double k2, double k3, double p1, double p2){
            _k1 = k1;
            _k2 = k2;
            _k3 = k3;
            _p1 = p1;
            _p2 = p2;
        }

        public void setDistortionParam(double[] distortion){

            if (distortion.length != 5){
                Log.e(TAG, "Distortion expect Array of 5 double, but get size: " + distortion.length);
                return;
            }

            _k1 = distortion[0];
            _k2 = distortion[1];
            _k3 = distortion[2];
            _p1 = distortion[3];
            _p2 = distortion[4];
        }

        public double[] getDistortionParam(){
            return new double[] {_k1, _k2, _p1, _p2, _k3};
        }

        /**
         * Projection matrix of the rectified image (Intrinsics after rectify)
         * P = K * [R|t]
         * @return
         */
        public double[] getP(){
            double[] P = new double[12];
            double[] R = getR();

            double r11 = R[0];
            double r12 = R[1];
            double r13 = R[2];

            double r21 = R[3];
            double r22 = R[4];
            double r23 = R[5];

            double r31 = R[6];
            double r32 = R[7];
            double r33 = R[8];

            // TODO calculate projection matrix

//            return new double[] {
//                _fx*r11+_cx*r31,    _fx*r12+_cx*r32,    _fx*r13+_cx*r33,    _fx*_tx+_cx,
//                _fx*r21+_cx*r31,    _fx*r22+_cx*r32,    _fx*r23+_cx*r33,    _fy*_ty+_cy,
//                r31,                r32,                r33,                1
//            };

            return new double[] {
                    _fx,    0,      _cx,    0,
                    0,      _fy,    _cy,    0,
                    0,      0,      1,      0
            };
        }

    }

}
