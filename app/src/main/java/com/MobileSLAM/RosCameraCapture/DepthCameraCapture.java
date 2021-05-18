package com.MobileSLAM.RosCameraCapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
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

import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.Arrays;

import java.lang.String;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class DepthCameraCapture {

    final static private String TAG = DepthCameraCapture.class.getSimpleName();

    private RosActivity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private String mCameraId;
    private CameraManager mCameraManager;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private Object frameLock = new Object();
    private boolean hasNext = false;

    private String imageEncoding = "mono16";

    private short[] latestFrameRaw;                     // Raw DEPTH16 value from sensor, not parsed

    private float depthConfidenceThreshold = 0.1f;
    private short maxDepthThreshold = 5000;               // Max Depth, in millimeter

    public String topicName = "/depth";

    public CameraUtil.CameraParam mCameraParam;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private HandlerThread previewThread;
    private Handler previewHandler;

    /**
     * Create depth camera capture object
     * Responsible for camera management, frame capture and ROS message publishing
     * @param context context from main activity
     * @param textureView preview surface for color frame
     */
    public DepthCameraCapture(@NonNull Context context, @NonNull TextureView textureView) {
        mMainActivity = (RosActivity) context;
        mCameraManager = (CameraManager) mMainActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraId = "4";            // Fixed camera id used for samsung s20+
        mTextureView = textureView;
        mCameraParam = CameraUtil.depthCameraParam;
    }


    /**
     * Start the background thread and handler
     */
    private void startBackgroundThread(){
        backgroundThread = new HandlerThread("DepthCameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        previewThread = new HandlerThread("DepthCameraPreview");
        previewThread.start();
        previewHandler = new Handler(previewThread.getLooper());
    }

    /**
     * Stop the background thread and handler
     */
    private void stopBackgroundThread(){
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /**
     * Start camera once the preview surface is ready
     */
    @SuppressLint("MissingPermission")
    public void startCamera() {

        startBackgroundThread();

        if(mTextureView.isAvailable()){
            try {
                mCameraManager.openCamera(mCameraId, depthCameraStateCallback, backgroundHandler);
            }catch (CameraAccessException e){
                e.printStackTrace();
            }
        }else{
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                    try {
                        mCameraManager.openCamera(mCameraId, depthCameraStateCallback, backgroundHandler);
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
        nodeConfiguration.setNodeName("mobile_camera/depth");

        nodeMainExecutor.execute(publishNodeDepth, nodeConfiguration);
    }

    /**
     * Process raw DEPTH16 frame into registered depth map
     * Depth value in short (millimeter)
     * Pixels are undistorted and registered to the rgb image
     * @return depth map in short
     * @throws InterruptedException
     */
    public short[] getLatestFrameValue() throws InterruptedException {
        short[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrameRaw, latestFrameRaw.length);
            hasNext = false;
        }

        short[] depthShort = CameraUtil.parseDepth16(copyData, mCameraParam.frameWidth, mCameraParam.frameHeight, depthConfidenceThreshold);
        depthShort = CameraUtil.undistortion(depthShort, mCameraParam);
        depthShort = CameraUtil.depthRegister(depthShort, mCameraParam, CameraUtil.colorCameraParam);
        return depthShort;
    }


    /**
     * Convert depth into 8 bits gray, not real depth value
     */
    public byte[] getLatestFrameGray() throws InterruptedException {
        short[] depthValue = getLatestFrameValue();
        byte[] depthGray = CameraUtil.convertShortToGray(depthValue, depthValue.length, maxDepthThreshold);

        return depthGray;
    }


    /**
     * Convert uint16 depth into bytes
     */
    public byte[] getLatestFrameShort() throws InterruptedException {
        short[] depthValue = getLatestFrameValue();
        byte[] depthByte = CameraUtil.convertShortToByte(depthValue, depthValue.length);

        return depthByte;
    }

    /**
     * Convert depth map into Bitmap and render on TextureView
     */
    private void depthPreview() {

        try{
            short[] depthShort = getLatestFrameValue();
            int[] depthARGB = CameraUtil.convertShortToARGB(depthShort, mCameraParam.frameWidth, mCameraParam.frameHeight, maxDepthThreshold);

            Bitmap depthBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
            depthBitmap.setPixels(depthARGB, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
            depthBitmap = Bitmap.createScaledBitmap(depthBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);

            Bitmap finalDepthBitmap = depthBitmap;
            // Update UI in main thread
            mTextureView.post(() -> CameraUtil.renderBitmapToTextureview(finalDepthBitmap, mTextureView));

        } catch (InterruptedException e){
            e.printStackTrace();
        }

    }

    /**
     * Callback for camera device state change
     * Create capture session and build capture target
     */
    private CameraDevice.StateCallback depthCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // start capture session as camera opened
            Log.i(TAG, "Camera " + cameraDevice.getId() + " Opened");

            // Configure image reader for image messaging
            mImageReader = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, ImageFormat.DEPTH16, 2);
            mImageReader.setOnImageAvailableListener(depthImageAvailableListener, backgroundHandler);

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


    /**
     * Callback for image reader, handle incoming depth frame
     * Parse DEPTH16 into real depth value and confidence
     * */
    private ImageReader.OnImageAvailableListener depthImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            ShortBuffer depthShortBuffer = img.getPlanes()[0].getBuffer().asShortBuffer();
            short[] depthRaw = new short[mCameraParam.frameWidth * mCameraParam.frameHeight];
            depthShortBuffer.get(depthRaw);

            // Render depth map in previewThread
            previewHandler.post(() -> depthPreview());

            synchronized (frameLock){
                latestFrameRaw = depthRaw;
                hasNext = true;
                frameLock.notifyAll();
            }

            img.close();
        }
    };


    /**
     * Callback for capture session state change
     * Create repeat capture request
     */
    private CameraCaptureSession.StateCallback depthCameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            try{
                CaptureRequest captureRequest = mCaptureRequestBuilder.build();
                cameraCaptureSession.setRepeatingRequest(captureRequest, null, backgroundHandler);
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
     * ROS node publishing depth message
     * Read current frame as gray / uint16
     * Send frame in sensor_msgs/Image (mono8 / mono16)
     */
    private NodeMain publishNodeDepth = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("depth");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {

            Publisher<sensor_msgs.Image> imagePublisher = connectedNode.newPublisher("~image_registered", sensor_msgs.Image._TYPE);
            sensor_msgs.Image img = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.Image._TYPE);
            ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());
            img.setHeight(mCameraParam.frameHeight);
            img.setWidth(mCameraParam.frameWidth);
            img.getHeader().setFrameId(topicName);
            img.setIsBigendian((byte) 0);

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
                protected void loop() throws InterruptedException {

                    Time timestamp = connectedNode.getCurrentTime();

                    try{

                        if(imageEncoding == "mono8"){
                            // Send depth in 8bit gray
                            img.setEncoding("8UC1");
                            img.setStep(mCameraParam.frameWidth);
                            dataStream.write(getLatestFrameGray());

                        }else if(imageEncoding == "mono16"){
                            // Send depth in uint16
                            img.setEncoding("16UC1");
                            img.setStep(mCameraParam.frameWidth * 2);
                            dataStream.write(getLatestFrameShort());
                        }

                    }catch (IOException e){
                        e.printStackTrace();
                    }catch (InterruptedException e){
                        e.printStackTrace();
                    }

                    img.getHeader().setStamp(timestamp);
                    img.setData(dataStream.buffer().copy());

                    dataStream.buffer().clear();
                    imagePublisher.publish(img);

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
