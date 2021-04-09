package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.TextureView;

import java.nio.ShortBuffer;

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

    public float depthConfidenceThreshold = 0.1f;

    public CameraUtil.CameraParam mCameraParam = CameraUtil.depthCameraParam;


    public DepthCameraCapture(Context context, @NonNull TextureView textureView) {
        mMainActivity = (Activity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "0";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
    }


    @SuppressLint("MissingPermission")
    public boolean startCameraPreview() {
        try {
            mCameraManager.openCamera(mCameraId, depthCameraStateCallback, null);
            // Set camera parameters
//            CameraCharacteristics camChara = mCameraManager.getCameraCharacteristics(mCameraId);
//            mCameraParam.setFrameSize(320, 240);
//            mCameraParam.setInrinsics(camChara.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION));
//            mCameraParam.setExtrinsic(
//                    camChara.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
//                    camChara.get(CameraCharacteristics.LENS_POSE_ROTATION)
//            );
//            mCameraParam.setDistortionParam(camChara.get(CameraCharacteristics.LENS_RADIAL_DISTORTION));
        }catch (CameraAccessException e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private CameraDevice.StateCallback depthCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            mCameraDevice = cameraDevice;

            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mCameraParam.frameWidth, mCameraParam.frameHeight);

            mImageReader = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, ImageFormat.DEPTH16, 2);
            mImageReader.setOnImageAvailableListener(depthImageAvailableListener, null);

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

    private ImageReader.OnImageAvailableListener depthImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image img = imageReader.acquireLatestImage();
            assert img != null;

            ShortBuffer depthShortBuffer = img.getPlanes()[0].getBuffer().asShortBuffer();
            short[] depthShort = CameraUtil.parseDepth16(depthShortBuffer, mCameraParam.frameWidth, mCameraParam.frameHeight, depthConfidenceThreshold);
            depthShort = CameraUtil.undistortion(depthShort, mCameraParam);
            depthShort = CameraUtil.depthRectify(depthShort, mCameraParam, CameraUtil.colorCameraParam);
        }

    };


}
