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

import org.apache.commons.lang.ArrayUtils;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

import java.lang.String;
import java.util.Calendar;
import java.util.Date;

import std_msgs.*;

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

    private short[] latestFrame_short;
    private byte[] latestFrame_gray;

    private float depthConfidenceThreshold = 0.1f;
    private short maxDepthThreshold = 5000;            // Max Depth, in millimeter

    public String topicName = "/depth";

    public CameraUtil.CameraParam mCameraParam;

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
     * Start camera once the preview surface is ready
     */
    @SuppressLint("MissingPermission")
    public void startCameraPreview() {

        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                try {
                    mCameraManager.openCamera(mCameraId, depthCameraStateCallback, null);
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


    /**
     * Start ROS node with image publisher
     * Started node will running in new thread
     * @param nodeMainExecutor ROS activity node executor
     */
    public void startRosNode(NodeMainExecutor nodeMainExecutor){

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mMainActivity.getRosHostname());
        nodeConfiguration.setMasterUri(mMainActivity.getMasterUri());
        nodeConfiguration.setNodeName("mobile_camera/depth");

        nodeMainExecutor.execute(mCameraPublishNode, nodeConfiguration);
    }


    /**
     * Return depth in 8 bits gray, not real depth value
     */
    public byte[] getLatestFrame_gray() throws InterruptedException {
        byte[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrame_gray, latestFrame_gray.length);
            hasNext = false;
        }
        return copyData;
    }


    /**
     * Return depth in 16 bits value, in millimeter
     */
    public short[] getLatestFrame_short() throws InterruptedException {
        short[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrame_short, latestFrame_short.length);
            hasNext = false;
        }
        return copyData;
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
            short[] depthShort = CameraUtil.parseDepth16(depthRaw, mCameraParam.frameWidth, mCameraParam.frameHeight, depthConfidenceThreshold);
            byte[] depthGray = CameraUtil.convertShortToGray(depthShort, depthShort.length, maxDepthThreshold);

//            depthShort = CameraUtil.undistortion(depthShort, mCameraParam);
//            depthShort = CameraUtil.depthRectify(depthShort, mCameraParam, CameraUtil.colorCameraParam);

            int[] depthARGB = CameraUtil.convertShortToARGB(depthShort, mCameraParam.frameWidth, mCameraParam.frameHeight, maxDepthThreshold);

            double depthMean = 0;
            for(short depthValue : depthShort){
                depthMean += depthValue;
            }
            depthMean = depthMean / depthShort.length;

            Log.d(TAG, "Depth short mean: " + depthMean);

            Bitmap depthBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
            depthBitmap.setPixels(depthARGB, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
            depthBitmap = Bitmap.createScaledBitmap(depthBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
            CameraUtil.renderBitmapToTextureview(depthBitmap, mTextureView);

            synchronized (frameLock){
                latestFrame_gray = depthGray;
                latestFrame_short = depthShort;
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


    /**
     * ROS node publishing depth message
     * Read current frame as gray / uint16
     * Send frame in sensor_msgs/Image (mono8 / mono16)
     */
    private NodeMain mCameraPublishNode = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("depth");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {

            Publisher<sensor_msgs.Image> imagePublisher = connectedNode.newPublisher("~image", sensor_msgs.Image._TYPE);
            sensor_msgs.Image img = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.Image._TYPE);
            ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());
            img.setHeight(mCameraParam.frameHeight);
            img.setWidth(mCameraParam.frameWidth);
            img.getHeader().setFrameId(topicName);
            img.setIsBigendian((byte) 0);

            // Camera info publisher
            Publisher<sensor_msgs.CameraInfo> infoPublisher = connectedNode.newPublisher("~camera_info", sensor_msgs.CameraInfo._TYPE);
            sensor_msgs.CameraInfo info = infoPublisher.newMessage();
            info.getHeader().setFrameId("color_camera");
            info.setHeight(mCameraParam.frameHeight);
            info.setWidth(mCameraParam.frameWidth);
            info.setDistortionModel("plumb_bob");
            info.setD(mCameraParam.getDistortionParam());
            info.setK(mCameraParam.getK());

            connectedNode.executeCancellableLoop(new CancellableLoop() {
                @Override
                protected void loop() throws InterruptedException {

                    Time timestamp = connectedNode.getCurrentTime();

                    try{

                        if(imageEncoding == "mono8"){
                            // Send depth in 8bit gray
                            img.setEncoding("8UC1");
                            img.setStep(mCameraParam.frameWidth);
                            dataStream.write(getLatestFrame_gray());

                        }else if(imageEncoding == "mono16"){
                            // Send depth in uint16
                            short[] depthShort = getLatestFrame_short();
//                            ByteBuffer depthByteBuffer = ByteBuffer.allocate(depthShort.length * 2);
//                            depthByteBuffer.asShortBuffer().put(depthShort);
//                            byte[] depthByte = Arrays.copyOf(depthByteBuffer.array(), depthByteBuffer.array().length);
                            byte[] depthByte = CameraUtil.convertShortToUint16(depthShort, depthShort.length);

                            img.setEncoding("16UC1");
                            img.setStep(mCameraParam.frameWidth * 2);
                            dataStream.write(depthByte);
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
                    Log.d(TAG, topicName + ": Image sent; " + timestamp);

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
