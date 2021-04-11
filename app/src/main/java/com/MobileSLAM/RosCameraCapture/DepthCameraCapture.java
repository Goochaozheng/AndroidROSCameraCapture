package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
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
import android.view.TextureView;

import java.nio.ShortBuffer;
import java.util.Arrays;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class DepthCameraCapture {

    final static private String TAG = DepthCameraCapture.class.getSimpleName();

    private Activity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private String mCameraId;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CaptureRequest mCaptureRequest;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private Object frameLock = new Object();
    public boolean hasNext = false;
    public short[] latestFrame;

    public float depthConfidenceThreshold = 0.1f;
    public short maxDepthThreshold = 5000;            // Max Depth, in millimeter

    public CameraUtil.CameraParam mCameraParam;


    public DepthCameraCapture(@NonNull Context context, @NonNull TextureView textureView) {
        mMainActivity = (Activity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "4";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
        mCameraParam = CameraUtil.depthCameraParam;
    }


    @SuppressLint("MissingPermission")
    public boolean startCameraPreview() {
        try {
            mCameraManager.openCamera(mCameraId, depthCameraStateCallback, null);
        }catch (CameraAccessException e){
            e.printStackTrace();
            return false;
        }
        return true;
    }


    // TODO get latest frame and send as message by ROS
    public short[] getLatestFrame() throws InterruptedException {
        short[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrame, latestFrame.length);
            hasNext = false;
        }
        return copyData;
    }


    // Handle callback when camera state changes
    private CameraDevice.StateCallback depthCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // start capture session as camera opened
            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            mCameraDevice = cameraDevice;

            // Configure image reader for image messaging
            mImageReader = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, ImageFormat.DEPTH16, 2);
            mImageReader.setOnImageAvailableListener(depthImageAvailableListener, null);

            try{
                mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                mCaptureRequestBuilder.addTarget(mImageReader.getSurface());
                cameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()), depthCameraCaptureSessionStateCallback, null);
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


    // handle callback when image reader state changes
    private ImageReader.OnImageAvailableListener depthImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            ShortBuffer depthShortBuffer = img.getPlanes()[0].getBuffer().asShortBuffer();
            short[] depthShort = CameraUtil.parseDepth16(depthShortBuffer, mCameraParam.frameWidth, mCameraParam.frameHeight, depthConfidenceThreshold);

            depthShort = CameraUtil.undistortion(depthShort, mCameraParam);
            depthShort = CameraUtil.depthRectify(depthShort, mCameraParam, CameraUtil.colorCameraParam);

//            byte[] depthGray = CameraUtil.convertShortToGray(depthShort, maxDepthThreshold);
//            int[] depthARGB = CameraUtil.convertGrayToARGB(depthGray);

            int[] depthARGB = CameraUtil.convertShortToARGB(depthShort, maxDepthThreshold);

            Bitmap depthBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
            depthBitmap.setPixels(depthARGB, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
            depthBitmap = Bitmap.createScaledBitmap(depthBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
            CameraUtil.renderBitmapToTextureview(depthBitmap, mTextureView);

            synchronized (frameLock){
                latestFrame = depthShort;
                hasNext = true;
                frameLock.notifyAll();
            }

            img.close();
        }
    };

    // handle callback when capture session state changes
    private CameraCaptureSession.StateCallback depthCameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
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

    private boolean rosInit(){
        // TODO Implement ROS initialization, create new ros node ?
        return true;
    }


}
