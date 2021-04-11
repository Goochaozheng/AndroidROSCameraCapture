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

import java.util.Arrays;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ColorCameraCapture {

    final static private String TAG = ColorCameraCapture.class.getSimpleName();

    private Activity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private String mCameraId;
    private CameraManager mCameraManager;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private Object frameLock = new Object();
    public boolean hasNext = false;
    public short[] latestFrame;

    public CameraUtil.CameraParam mCameraParam;


    public ColorCameraCapture(@NonNull Context context, @NonNull TextureView textureView) {
        mMainActivity = (Activity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "0";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
        mCameraParam = CameraUtil.colorCameraParam;
    }


    @SuppressLint("MissingPermission")
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
//                cameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()), colorCameraCaptureSessionStateCallbakc, null);
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
            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            Image.Plane[] planes = img.getPlanes();
            byte[][] yuvBytes = new byte[planes.length][];
            for(int i = 0; i < planes.length; i++){
                yuvBytes[i] = new byte[planes[i].getBuffer().capacity()];
                planes[i].getBuffer().get(yuvBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

//            int[] argbUint32 = CameraUtil.convertYUVToARGB(yuvBytes[0], yuvBytes[1], yuvBytes[2],
//                                        yRowStride, uvRowStride, uvPixelStride,
//                                        mCameraParam.frameWidth, mCameraParam.frameHeight);
//
//            argbUint32 = CameraUtil.undistortion(argbUint32, mCameraParam);
//
//            Bitmap colorBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
//            colorBitmap.setPixels(argbUint32, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
//            colorBitmap = Bitmap.createScaledBitmap(colorBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
//            CameraUtil.renderBitmapToTextureview(colorBitmap, mTextureView);

            img.close();
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


    private boolean rosInit(){
        // TODO Implement ROS initialization, create new ros node ?
        return true;
    }

}
