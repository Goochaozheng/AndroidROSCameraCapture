package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Camera;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.util.Arrays;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ColorCameraCapture {

    final static private String TAG = ColorCameraCapture.class.getSimpleName();

    private Activity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private String mCameraId;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CaptureRequest mCaptureRequest;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    public static final float SCALE_FACTOR = 1.0f;

    public int frameWidth;
    public int frameHeight;

    public CameraUtil.CameraParam mCameraParam;
//    public CameraUtil.CameraParam mCameraParam = new CameraUtil.CameraParam(
//            3054.3071f * SCALE_FACTOR,
//            3052.0754f * SCALE_FACTOR,
//            1990.2135f * SCALE_FACTOR,
//            1512.378f * SCALE_FACTOR,
//            0f,
//            0f,
//            0f,
//            0f,
//            0.05797f,
//            -0.05520f,
//            0.00144f,
//            0,
//            0
//    );

    public ColorCameraCapture(Context context, @NonNull TextureView textureView) {
        mMainActivity = (Activity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "0";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
    }


    @SuppressLint("MissingPermission")
    public boolean StartCameraPreview(){
        try {
            mCameraManager.openCamera(mCameraId, mColorCameraStateCallback, null);
            // Set camera parameters
            CameraCharacteristics camChara = mCameraManager.getCameraCharacteristics(mCameraId);
            mCameraParam.setInrinsics(camChara.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION));
            mCameraParam.setExtrinsic(
                    camChara.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
                    camChara.get(CameraCharacteristics.LENS_POSE_ROTATION)
            );
            mCameraParam.setDistortionParam(camChara.get(CameraCharacteristics.LENS_RADIAL_DISTORTION));
        }catch (CameraAccessException e){
            e.printStackTrace();
            return false;
        }
        return true;
    }


    // Callback for camera device state change
    private CameraDevice.StateCallback mColorCameraStateCallback = new CameraDevice.StateCallback(){

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            mCameraDevice = cameraDevice;

            // Bind surface texture for preview
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(frameWidth, frameHeight);
            Surface previewSurface = new Surface(texture);
            try{
                mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                mCaptureRequestBuilder.addTarget(previewSurface);        // target for preview

                // create capture session
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface), mColorCameraCaptureSessionStateCallbakc, null);

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


    // Callback for capture session state change
    private CameraCaptureSession.StateCallback mColorCameraCaptureSessionStateCallbakc = new CameraCaptureSession.StateCallback() {
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

    private int[] ConvertYUV2ARGB(){
        // TODO Implement conversion
        int[] res = new int[frameWidth*frameHeight];
        return res;
    }

    private boolean RosInit(){
        // TODO Implement ROS initialization, create new ros node ?
        return true;
    }

}
