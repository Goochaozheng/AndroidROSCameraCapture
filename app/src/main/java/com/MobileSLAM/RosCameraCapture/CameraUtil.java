package com.MobileSLAM.RosCameraCapture;

import android.util.Log;

import java.nio.ShortBuffer;

public class CameraUtil {

    private final static String TAG = CameraUtil.class.getSimpleName();

    static public CameraParam depthCameraParam = new CameraParam(
            // frame size
            320,
            240,
            // intrinsics
            536.9581f,
            536.7106f,
            312.9077f,
            233.22255f,
            0f,
            // extrinsics
            0f,
            0.01124f,
            0f,
            0.70304f,
            -0.71113f,
            0.00172f,
            0,
            // distortion
            0.32826f,
            -0.56677f,
            0.12383f,
            0,
            0
    );

    static public CameraParam colorCameraParam = new CameraParam(
            // frame size
            320,
            240,
            // intrinsics
            3054.3071f,
            3052.0754f,
            1990.2135f,
            1512.378f,
            0f,
            // extrinsics
            0f,
            0f,
            0f,
            0.7071f,
            -0.7071f,
            0f,
            0f,
            // distortion
            0.05797f,
            -0.05520f,
            0.00144f,
            0,
            0
    );

    static public short[] parseDepth16(ShortBuffer shortBuffer, int width, int height, float confidenceThreshold){
        short[] res = new short[width * height];

        for(int y=0; y<height; y++){
            for(int x=0; x<width; x++){
                int index = width * y + x;
                short rawDepth = shortBuffer.get(index);

                short depthRange = (short) (rawDepth & 0x1FFF);
                short depthConfidence = (short) ((rawDepth >> 13) & 0x7);
                float confidencePercentage = depthConfidence == 0 ? 1.f : (depthConfidence - 1) / 7.f;

                if(confidencePercentage > confidenceThreshold){
                    res[index] = depthRange;
                }else{
                    res[index] = 0;
                }
            }
        }

        return res;
    }

    public static short[] undistortion(short[] input, CameraParam camParam) {

        float fx = camParam._fx;
        float fy = camParam._fy;
        float cx = camParam._cx;
        float cy = camParam._cy;
        float k1 = camParam._k1;
        float k2 = camParam._k2;
        float k3 = camParam._k3;
        float p1 = camParam._p1;
        float p2 = camParam._p2;

        int width = camParam.frameWidth;
        int height = camParam.frameHeight;

        short[] output = new short[width*height];

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

        float fx = camParam._fx;
        float fy = camParam._fy;
        float cx = camParam._cx;
        float cy = camParam._cy;
        float k1 = camParam._k1;
        float k2 = camParam._k2;
        float k3 = camParam._k3;
        float p1 = camParam._p1;
        float p2 = camParam._p2;

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


    public static short[] depthRectify(short[] depth, CameraParam depthParam, CameraParam colorParam){

        int width = depthParam.frameWidth;
        int height = depthParam.frameHeight;

        float qx = depthParam._qx;
        float qy = depthParam._qy;
        float qz = depthParam._qz;
        float qw = depthParam._qw;

        float tx = depthParam._tx;
        float ty = depthParam._ty;
        float tz = depthParam._tz;

        float qx_2 = colorParam._qx;
        float qy_2 = colorParam._qy;
        float qz_2 = colorParam._qz;
        float qw_2 = colorParam._qw;

        short[] res = new short[width * height];
        for (int i = 0; i < width * height; i++) res[i] = 0;

        for(int v = 0; v < height; v++){
            for(int u = 0; u < width; u++){

                // 3d point of depth pixel at phone coordinate, in meter
                float depth_z = depth[ v * width + u ] / 1000.0f;
                if(depth_z == 0) continue;

                // depth image to depth camera
                float depth_x = (u - depthParam._cx) / depthParam._fx * depth_z;
                float depth_y = (v - depthParam._cy) / depthParam._fy * depth_z;

                // transform 3d point to sensor coordinate
                float color_x = depth_x*(1-2*qy*qy-2*qz*qz) + depth_y*(2*qx*qy+2*qz*qw) + depth_z*(2*qx*qz-2*qy*qw) - tx;
                float color_y = depth_x*(2*qx*qy-2*qz*qw) + depth_y*(1-2*qx*qx-2*qz*qz) + depth_z*(2*qy*qz+2*qx*qw) - ty;
                float color_z = depth_x*(2*qx*qz+2*qy*qw) + depth_y*(2*qy*qz-2*qx*qw) + depth_z*(1-2*qx*qx-2*qy*qy) - tz;

                // transform from sensor coordinate to color camera coordinate
                float color_x_2 = color_x*(1-2*qy_2*qy_2-2*qz_2*qz_2) + color_y*(2*qx_2*qy_2+2*qz_2*qw_2) + color_z*(2*qx_2*qz_2-2*qy_2*qw_2);
                float color_y_2 = color_x*(2*qx_2*qy_2-2*qz_2*qw_2) + color_y*(1-2*qx_2*qx_2-2*qz_2*qz_2) + color_z*(2*qy_2*qz_2+2*qx_2*qw_2);
                float color_z_2 = color_x*(2*qx_2*qz_2+2*qy_2*qw_2) + color_y*(2*qy_2*qz_2-2*qx_2*qw_2) + color_z*(1-2*qx_2*qx_2-2*qy_2*qy_2);

                // color camera to color image
                float color_u = colorParam._fx * color_x_2 / color_z_2 + colorParam._cx;
                float color_v = colorParam._fy * color_y_2 / color_z_2 + colorParam._cy;

                // assign depth value to new pixel
                if(color_u >= 0 && color_v >=0 && color_u <= width && color_v <= height){
                    res[(int)color_v * width + (int)color_u] = depth[v * width + u];
                }
            }
        }
        return res;
    }

    static public class CameraParam{

        public float _scaleFactor = 1.0f;
        public int frameWidth;
        public int frameHeight;

        // Camera Intrinsics
        public float _fx;
        public float _fy;
        public float _cx;
        public float _cy;
        public float _s;

        // Camera Extrinsics
        public float _tx;
        public float _ty;
        public float _tz;

        public float _qx;
        public float _qy;
        public float _qz;
        public float _qw;

        // Distortion Param
        public float _k1;
        public float _k2;
        public float _k3;
        public float _p1;
        public float _p2;

        private int DEFAULT_WIDTH = 640;
        private int DEFAULT_HEIGHT = 480;

        public CameraParam() {}

        public CameraParam(int width, int height,
                           float fx, float fy, float cx, float cy, float s,
                           float tx, float ty, float tz,
                           float qx, float qy, float qz, float qw,
                           float k1, float k2, float k3, float p1, float p2) {

            setFrameSize(width, height);
            setInrinsics(fx, fy, cx, cy, s);
            setExtrinsic(tx, ty, tz, qx, qy, qz, qw);
            setDistortionParam(k1, k2, k3, p1, p2);
        }

        public void setFrameSize(int width, int height){
            frameWidth = width;
            frameHeight = height;
            if((float)width/DEFAULT_WIDTH == (float)height/DEFAULT_HEIGHT){
                _scaleFactor = (float)width/DEFAULT_WIDTH;
            }else{
                throw new IllegalArgumentException("Size not match default aspect ratio: " + String.valueOf(DEFAULT_WIDTH) + ":" + String.valueOf(DEFAULT_HEIGHT));
            }
        }

        public float[] getFrameSize(){ return new float[] {frameWidth, frameHeight}; }

        public void setInrinsics(float fx, float fy, float cx, float cy, float s){
            _fx = fx;
            _fy = fy;
            _cx = cx;
            _cy = cy;
            _s = s;
        }

        public void setInrinsics(float[] intrinsics){

            if (intrinsics.length != 5){
                Log.e(TAG, "Intrinsics expect Array of 5 float, but get size: " + intrinsics.length);
                return;
            }

            _fx = intrinsics[0] * _scaleFactor;
            _fy = intrinsics[1] * _scaleFactor;
            _cx = intrinsics[2] * _scaleFactor;
            _cy = intrinsics[3] * _scaleFactor;
            _s = intrinsics[4];
        }

        public float[] getIntrinsics(){
            return new float[] {_fx, _fy, _cx, _cy};
        }

        public void setExtrinsic(float tx, float ty, float tz, float qx, float qy, float qz, float qw){
            _tx = tx;
            _ty = ty;
            _tz = tz;
            _qx = qx;
            _qy = qy;
            _qz = qz;
            _qw = qw;
        }

        public void setExtrinsic(float[] translation, float[] rotation){

            if (translation.length != 3){
                Log.e(TAG, "Extrinsic expect Array of 3 float for translation parameter, but get size: " + translation.length);
                return;
            }

            if (rotation.length != 4){
                Log.e(TAG, "Extrinsic expect Array of 4 float for rotation parameter, but get size: " + translation.length);
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

        public float[] getExtrinsics(){
            return new float[] {_tx, _ty, _tz};
        }

        public void setDistortionParam(float k1, float k2, float k3, float p1, float p2){
            _k1 = k1;
            _k2 = k2;
            _k3 = k3;
            _p1 = p1;
            _p2 = p2;
        }

        public void setDistortionParam(float[] distortion){

            if (distortion.length != 5){
                Log.e(TAG, "Distortion expect Array of 5 float, but get size: " + distortion.length);
                return;
            }

            _k1 = distortion[0];
            _k2 = distortion[1];
            _k3 = distortion[2];
            _p1 = distortion[3];
            _p2 = distortion[4];
        }

        public float[] getDistortionParam(){
            return new float[] {_k1, _k2, _k3, _p1, _p2};
        }

    }

}