package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import org.apache.commons.lang.ArrayUtils;
import org.ros.android.RosActivity;
import org.ros.concurrent.CancellableLoop;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;

import java.lang.String;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import std_msgs.*;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ColorCameraCapture implements CameraCapture{

    final static private String TAG = ColorCameraCapture.class.getSimpleName();

    private RosActivity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private String mCameraId;
    private CameraManager mCameraManager;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private Object frameLock = new Object();
    public boolean hasNext = false;

    private byte[] latestFrame_y;
    private byte[] latestFrame_u;
    private byte[] latestFrame_v;

    private byte[] latestFrame;

    public int yRowStride;
    public int uvRowStride;
    public int uvPixelStride;

    public CameraUtil.CameraParam mCameraParam;


    public ColorCameraCapture(@NonNull Context context, @NonNull TextureView textureView) {
        mMainActivity = (RosActivity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "0";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
        mCameraParam = CameraUtil.colorCameraParam;
    }


    @SuppressLint("MissingPermission")
    @Override
    public void startCameraPreview(){

        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                try {
                    mCameraManager.openCamera(mCameraId, colorCameraStateCallback, null);
                }catch (CameraAccessException e){
                    e.printStackTrace();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) { }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });

    }


    @Override
    public void startRosNode(NodeMainExecutor nodeMainExecutor){

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mMainActivity.getRosHostname());
        nodeConfiguration.setMasterUri(mMainActivity.getMasterUri());
        nodeConfiguration.setNodeName("color_node");

        CameraPublisher colorRosNode = new CameraPublisher(
                "/color",
                mCameraParam.frameWidth,
                mCameraParam.frameHeight,
                mCameraParam.frameWidth * 4,
                "rgba8",
                this);

        nodeMainExecutor.execute(colorRosNode, nodeConfiguration);
    }

    // Return color frame in YUV arrays
    public byte[][] getLatestFrame_yuv() throws InterruptedException {
//        return new byte[mCameraParam.frameWidth * mCameraParam.frameHeight * 4];
        byte[][] copyData = new byte[3][];
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData[0] = Arrays.copyOf(latestFrame_y, latestFrame_y.length);
            copyData[1] = Arrays.copyOf(latestFrame_u, latestFrame_u.length);
            copyData[2] = Arrays.copyOf(latestFrame_v, latestFrame_v.length);
            hasNext = false;
        }
        return copyData;
    }

    // Return frame in RGB arrays
    public byte[] getLatestFrame() throws InterruptedException{
        byte[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrame, latestFrame.length);
            hasNext = false;
        }
        return copyData;
    }

    // Callback for camera device state change
    private CameraDevice.StateCallback colorCameraStateCallback = new CameraDevice.StateCallback(){

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            // Set Texture Transform for Landscape Orientation
            Matrix matrix = new Matrix();
            RectF textureRectF = new RectF(0, 0, mTextureView.getWidth(), mTextureView.getHeight());
            RectF previewRectF = new RectF(0, 0, mTextureView.getHeight(), mTextureView.getWidth());
            previewRectF.offset(textureRectF.centerX() - previewRectF.centerX(), textureRectF.centerY() - previewRectF.centerY());
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL);
            matrix.postRotate(-90, textureRectF.centerX(), textureRectF.centerY());
            mTextureView.setTransform(matrix);

            // Surface Texture for Preview
            SurfaceTexture colorSurfaceTexture = mTextureView.getSurfaceTexture();
            colorSurfaceTexture.setDefaultBufferSize(mCameraParam.frameWidth, mCameraParam.frameHeight);
            Surface previewSurface = new Surface(colorSurfaceTexture);

            // Configure image reader for image messaging
            mImageReader = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(colorImageAvailableListener, null);

            // Configure capture request
            try{
                mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                mCaptureRequestBuilder.addTarget(previewSurface);
                mCaptureRequestBuilder.addTarget(mImageReader.getSurface());

                // create capture session
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), colorCameraCaptureSessionStateCallbakc, null);

            } catch (CameraAccessException e){
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            Log.i(TAG, "Camera " + cameraDevice.getId() + " disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            Log.e(TAG, "Camera " + cameraDevice.getId() + " error: " + error);
        }
    };


    private ImageReader.OnImageAvailableListener colorImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {

            Long t1 = System.nanoTime();

            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            Image.Plane[] planes = img.getPlanes();
            byte[][] yuvBytes = new byte[planes.length][];
            for(int i = 0; i < planes.length; i++){
                yuvBytes[i] = new byte[planes[i].getBuffer().capacity()];
                planes[i].getBuffer().get(yuvBytes[i]);
            }

//            yRowStride = planes[0].getRowStride();
//            uvRowStride = planes[1].getRowStride();
//            uvPixelStride = planes[1].getPixelStride();

//            byte[] argbByte = CameraUtil.convertYUVToARGB(yuvBytes[0], yuvBytes[1], yuvBytes[2],
//                                        yRowStride, uvRowStride, uvPixelStride,
//                                        mCameraParam.frameWidth, mCameraParam.frameHeight);

//            int[] argbUint32 = CameraUtil.convertByteToUint32(argbByte, mCameraParam.frameWidth, mCameraParam.frameHeight, 4);

//            int[] argbUint32 = CameraUtil.convertYUVToARGBUint32(yuvBytes[0], yuvBytes[1], yuvBytes[2],
//                                        yRowStride, uvRowStride, uvPixelStride,
//                                        mCameraParam.frameWidth, mCameraParam.frameHeight);

//            argbUint32 = CameraUtil.undistortion(argbUint32, mCameraParam);

//            Bitmap colorBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
//            colorBitmap.setPixels(argbUint32, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
//            colorBitmap = Bitmap.createScaledBitmap(colorBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
//            CameraUtil.renderBitmapToTextureview(colorBitmap, mTextureView);

            synchronized (frameLock){

                yRowStride = planes[0].getRowStride();
                uvRowStride = planes[1].getRowStride();
                uvPixelStride = planes[1].getPixelStride();

                latestFrame_y = yuvBytes[0];
                latestFrame_u = yuvBytes[1];
                latestFrame_v = yuvBytes[2];

//                latestFrame = argbByte;

                hasNext = true;
                frameLock.notifyAll();
            }

            img.close();

            Long t2 = System.nanoTime();
            Log.d("Timing", "Image Reader Process Time: " + String.valueOf((float)(t2 - t1) / 1000000000));
        }
    };


    // Callback for capture session state change
    private CameraCaptureSession.StateCallback colorCameraCaptureSessionStateCallbakc = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            try{
                CaptureRequest captureRequest = mCaptureRequestBuilder.build();
                cameraCaptureSession.setRepeatingRequest(captureRequest, null, null);
            } catch (CameraAccessException e){
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.e(TAG, "Capture Session Configure Failed");
        }
    };



}
