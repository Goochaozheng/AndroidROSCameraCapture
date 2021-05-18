package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.ros.android.RosActivity;
import org.ros.concurrent.CancellableLoop;
import org.ros.internal.message.MessageBuffers;
import org.ros.message.Time;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.String;
import java.nio.ByteBuffer;
import java.util.Arrays;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ColorCameraCapture{

    final static private String TAG = ColorCameraCapture.class.getSimpleName();

    private RosActivity mMainActivity;
    private TextureView mTextureView;
    private ImageReader imageReaderJPEG;
    private ImageReader imageReaderYUV;

    private CameraManager mCameraManager;
    private CaptureRequest.Builder mCaptureRequestBuilder;

//    private int imageEncoding = ImageFormat.JPEG;

    private final Object frameLockJPEG = new Object();
    private final Object frameLockYUV = new Object();
    public boolean hasNextJPEG = false;
    public boolean hasNextYUV = false;

    private HandlerThread backgroundThreadJPEG;
    private Handler backgroundHandlerJPEG;

    private HandlerThread backgroundThreadYUV;
    private Handler backgroundHandlerYUV;

    /**
     * frame stored Low Resolution image in YUV format
     */
    private byte[] latestFrame_y;
    private byte[] latestFrame_u;
    private byte[] latestFrame_v;
    private int yRowStride = -1;
    private int uvRowStride = -1;
    private int uvPixelStride = -1;

    /**
     * frame stored High Resolution image in jpeg format
     */
    private Bitmap colorBitmapJPEG;
    private byte[] latestFrameJPEG;

    public static final String mCameraId = "0";             // Fixed camera id used for samsung s20+
    public static final CameraUtil.CameraParam mCameraParam = CameraUtil.colorCameraParam;


    /**
     * Create color camera capture object
     * Responsible for camera management, frame capture and ROS message publishing
     * @param context context from main activity
     * @param textureView preview surface for color frame
     */
    public ColorCameraCapture(@NonNull Context context, @NonNull TextureView textureView) {
        mMainActivity = (RosActivity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mTextureView = textureView;
    }

    /**
     * Start the background thread and handler
     */
    private void startBackgroundThread(){
        backgroundThreadJPEG = new HandlerThread("ColorCameraBackground_HighResolution");
        backgroundThreadJPEG.start();
        backgroundHandlerJPEG = new Handler(backgroundThreadJPEG.getLooper());

        backgroundThreadYUV = new HandlerThread("ColorCameraBackground_LowResolution");
        backgroundThreadYUV.start();
        backgroundHandlerYUV = new Handler(backgroundThreadYUV.getLooper());
    }

    /**
     * Stop the background thread and handler
     */
    private void stopBackgroundThread(){
        backgroundThreadJPEG.quitSafely();
        backgroundThreadYUV.quitSafely();
        try {
            backgroundThreadJPEG.join();
            backgroundThreadYUV.join();

            backgroundThreadJPEG = null;
            backgroundHandlerJPEG = null;

            backgroundThreadYUV = null;
            backgroundHandlerYUV = null;

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /**
     * Start camera once the preview surface is ready
     */
    @SuppressLint("MissingPermission")
    public void startCamera(){

        startBackgroundThread();

        if(mTextureView.isAvailable()){
            try {
                mCameraManager.openCamera(mCameraId, colorCameraStateCallback, backgroundHandlerJPEG);
            }catch (CameraAccessException e){
                e.printStackTrace();
            }
        }else{
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                    try {
                        mCameraManager.openCamera(mCameraId, colorCameraStateCallback, backgroundHandlerJPEG);
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

    }

    /**
     * Start ROS node with image publisher
     * Started node will running in new thread
     * @param nodeMainExecutor ROS activity node executor
     */
    public void startRosNode(NodeMainExecutor nodeMainExecutor){

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mMainActivity.getRosHostname());
        nodeConfiguration.setMasterUri(mMainActivity.getMasterUri());
        nodeConfiguration.setNodeName("mobile_camera/rgb/hr");
        nodeMainExecutor.execute(publishNodeCompressed, nodeConfiguration);

        NodeConfiguration nodeConfiguration_lr = NodeConfiguration.newPublic(mMainActivity.getRosHostname());
        nodeConfiguration_lr.setMasterUri(mMainActivity.getMasterUri());
        nodeConfiguration_lr.setNodeName("mobile_camera/rgb/lr");
        nodeMainExecutor.execute(publishNodeImage, nodeConfiguration_lr);
    }


    /**
     * Return YUV frame in BGRA8 bytes
     */
    public byte[] getLatestFrameYUV() throws InterruptedException {

        byte[] copyData_y;
        byte[] copyData_u;
        byte[] copyData_v;

        synchronized (frameLockYUV){
            if(!hasNextYUV){
                frameLockYUV.wait();
            }
            copyData_y = Arrays.copyOf(latestFrame_y, latestFrame_y.length);
            copyData_u = Arrays.copyOf(latestFrame_u, latestFrame_u.length);
            copyData_v = Arrays.copyOf(latestFrame_v, latestFrame_v.length);

            hasNextYUV = false;
        }

        return CameraUtil.convertYUVToBGRA(copyData_y, copyData_u, copyData_v,
                yRowStride, uvRowStride, uvPixelStride,
                320, 240);
    }

    /**
     * Return color frame in JPEG encoding bytes
     */
    public byte[] getLatestFrameJPEG() throws InterruptedException{
        byte[] copyData;

        synchronized (frameLockJPEG){
            if(!hasNextJPEG){
                frameLockJPEG.wait();
            }
            copyData = Arrays.copyOf(latestFrameJPEG, latestFrameJPEG.length);
            hasNextJPEG = false;
        }

        colorBitmapJPEG = BitmapFactory.decodeByteArray(copyData, 0, copyData.length);
        colorBitmapJPEG = Bitmap.createScaledBitmap(colorBitmapJPEG, mCameraParam.frameWidth, mCameraParam.frameHeight, false);

        // Compress Bitmap to JPEG for publishing
        ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
        colorBitmapJPEG.compress(Bitmap.CompressFormat.JPEG, 80, jpegStream);

        return jpegStream.toByteArray();
    }



    /**
     * Callback for camera device state change
     * Create capture session and build capture target
     */
    private final CameraDevice.StateCallback colorCameraStateCallback = new CameraDevice.StateCallback(){

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {

            Log.d(TAG, "Camera Thread: " + Thread.currentThread().getId());

            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            try {
                CameraCharacteristics chara = mCameraManager.getCameraCharacteristics(mCameraId);
                StreamConfigurationMap configs = chara.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                assert configs != null;
                configs.getOutputSizes(ImageFormat.JPEG);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

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
            colorSurfaceTexture.setDefaultBufferSize(mTextureView.getWidth(), mTextureView.getHeight());
            Surface previewSurface = new Surface(colorSurfaceTexture);

            // Configure image reader for image messaging
            // ImageReader reads frame with full size:
            imageReaderJPEG = ImageReader.newInstance(4032, 3024, ImageFormat.JPEG, 2);
            imageReaderJPEG.setOnImageAvailableListener(colorImageAvailableListenerJPEG, backgroundHandlerJPEG);
            // ImageReader reads frame with low resolution:
            imageReaderYUV = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, ImageFormat.YUV_420_888, 2);
            imageReaderYUV.setOnImageAvailableListener(colorImageAvailableListenerYUV, backgroundHandlerYUV);

            // Configure capture request
            try{
                mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                mCaptureRequestBuilder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(0,0));
                mCaptureRequestBuilder.addTarget(previewSurface);
                mCaptureRequestBuilder.addTarget(imageReaderJPEG.getSurface());
                mCaptureRequestBuilder.addTarget(imageReaderYUV.getSurface());

                // create capture session
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReaderJPEG.getSurface(), imageReaderYUV.getSurface()), colorCameraCaptureSessionStateCallback, null);

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


    /**
     * ImageReader callback for JPEG frame
     * Store latest frame to 'latestFrameJPEG'
     * */
    final private ImageReader.OnImageAvailableListener colorImageAvailableListenerJPEG = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {

            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            ByteBuffer buffer = img.getPlanes()[0].getBuffer();
            byte[] jpegByte = new byte[buffer.capacity()];
            buffer.get(jpegByte);

            synchronized (frameLockJPEG){
                latestFrameJPEG = jpegByte;
                hasNextJPEG = true;
                frameLockJPEG.notifyAll();
            }

            img.close();
        }
    };


    /**
     * ImageReader callback for YUV frame
     * Store latest frame to latestFrame_y & latestFrame_u & latestFrame_v
     * yRowStride, uvRowStride, uvPixelStride are also set
     */
    final private ImageReader.OnImageAvailableListener colorImageAvailableListenerYUV = new ImageReader.OnImageAvailableListener() {
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

            if(yRowStride == -1){
                yRowStride = planes[0].getRowStride();
                uvRowStride = planes[1].getRowStride();
                uvPixelStride = planes[1].getPixelStride();
            }

            synchronized (frameLockYUV){
                latestFrame_y = yuvBytes[0];
                latestFrame_u = yuvBytes[1];
                latestFrame_v = yuvBytes[2];
                hasNextYUV = true;
                frameLockYUV.notifyAll();
            }

            img.close();
        }
    };


    /**
     * Callback for capture session state change
     * Create repeat capture request
     */
    private final CameraCaptureSession.StateCallback colorCameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            try{
                CaptureRequest captureRequest = mCaptureRequestBuilder.build();
                cameraCaptureSession.setRepeatingRequest(captureRequest, null, backgroundHandlerJPEG);
            } catch (CameraAccessException e){
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.e(TAG, "Capture Session Configure Failed");
        }
    };


    /**
     * ROS node publishing image message
     * Read high resolution frame (4032x3024)
     * Resize to 2560x1920
     * Send frame in sensor_msgs/CompressedImage (jpeg)
     */
    private final NodeMain publishNodeCompressed = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("color");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {

            Log.d(TAG, "Image Publishing Thread: " + Thread.currentThread().getId());

            // Image publisher for compressed JPEG image
            Publisher<sensor_msgs.CompressedImage> compressedImagePublisher = connectedNode.newPublisher("~compressed", sensor_msgs.CompressedImage._TYPE);
            sensor_msgs.CompressedImage compressed = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.CompressedImage._TYPE);
            compressed.setFormat("jpeg");
            compressed.getHeader().setFrameId("camera_link");

            ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());

            connectedNode.executeCancellableLoop(new CancellableLoop() {
                @Override
                protected void loop() {
                    // Send compressed image message
                    Time timestamp = connectedNode.getCurrentTime();

                    try{
                        byte[] jpegByte = getLatestFrameJPEG();
                        dataStream.write(jpegByte);
                    } catch (IOException | InterruptedException e){
                        e.printStackTrace();
                    }

                    compressed.getHeader().setStamp(timestamp);
                    compressed.setData(dataStream.buffer().copy());
                    compressedImagePublisher.publish(compressed);
                    dataStream.buffer().clear();

                }

            });
        }

        @Override
        public void onShutdown(Node node) {

        }

        @Override
        public void onShutdownComplete(Node node) {

        }

        @Override
        public void onError(Node node, Throwable throwable) {

        }
    };

    /**
     * ROS node publishing Low Resolution image (320x240)
     */
    private final NodeMain publishNodeImage = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return null;
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {

            Log.d(TAG, "LR Image Publishing Thread: " + Thread.currentThread().getId());

            // Image publisher for compressed BGRA8 image
            Publisher<sensor_msgs.Image> lrImagePublisher = connectedNode.newPublisher("~image", sensor_msgs.Image._TYPE);
            sensor_msgs.Image img = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.Image._TYPE);
            img.setEncoding("bgra8");
            img.setWidth(mCameraParam.frameWidth);
            img.setHeight(mCameraParam.frameHeight);
            img.setStep(mCameraParam.frameWidth * 4);
            img.setIsBigendian((byte)1);
            img.getHeader().setFrameId("camera_link");

            ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());

            // Camera info publisher
            Publisher<sensor_msgs.CameraInfo> infoPublisher = connectedNode.newPublisher("~camera_info", sensor_msgs.CameraInfo._TYPE);
            sensor_msgs.CameraInfo info = infoPublisher.newMessage();
            info.getHeader().setFrameId("mobile_camera");
            info.setHeight(mCameraParam.frameHeight);
            info.setWidth(mCameraParam.frameWidth);
            info.setDistortionModel("plumb_bob");
            info.setD(mCameraParam.getDistortionParam());
            info.setK(mCameraParam.getK());
            info.setR(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
            info.getRoi().setHeight(mCameraParam.frameHeight);
            info.getRoi().setWidth(mCameraParam.frameWidth);
            info.setP(mCameraParam.getP());

            connectedNode.executeCancellableLoop(new CancellableLoop() {
                @Override
                protected void loop() {
                    // Send compressed image message
                    Time timestamp = connectedNode.getCurrentTime();
                    try{
                        byte[] rgb8Byte = getLatestFrameYUV();
                        dataStream.write(rgb8Byte);
                    } catch (IOException | InterruptedException e){
                        e.printStackTrace();
                    }

                    img.getHeader().setStamp(timestamp);
                    img.setData(dataStream.buffer().copy());
                    lrImagePublisher.publish(img);
                    dataStream.buffer().clear();

                    info.getHeader().setStamp(timestamp);
                    infoPublisher.publish(info);

                }
            });

        }

        @Override
        public void onShutdown(Node node) {

        }

        @Override
        public void onShutdownComplete(Node node) {

        }

        @Override
        public void onError(Node node, Throwable throwable) {

        }
    };


}
