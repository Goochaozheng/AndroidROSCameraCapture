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
    private CameraDevice mCameraDevice;
    private CaptureRequest mCaptureRequest;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private Object frameLock = new Object();
    public boolean hasNext = false;
    public byte[] latestFrame;

    public float depthConfidenceThreshold = 0.1f;
    public short maxDepthThreshold = 5000;            // Max Depth, in millimeter

    public String topicName = "/mobile_camera/depth";

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
        nodeConfiguration.setNodeName("~depth");

        nodeMainExecutor.execute(mCameraPublishNode, nodeConfiguration);
    }


    // TODO get latest frame in byte[]
    public byte[] getLatestFrame() throws InterruptedException {
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
            short[] depthRaw = new short[mCameraParam.frameWidth * mCameraParam.frameHeight];
            depthShortBuffer.get(depthRaw);
//            depthRaw = Arrays.copyOf(depthShortBuffer.array(), depthShortBuffer.array().length);
            short[] depthShort = CameraUtil.parseDepth16(depthRaw, mCameraParam.frameWidth, mCameraParam.frameHeight, depthConfidenceThreshold);

//            depthShort = CameraUtil.undistortion(depthShort, mCameraParam);
//            depthShort = CameraUtil.depthRectify(depthShort, mCameraParam, CameraUtil.colorCameraParam);

            byte[] depthGray = CameraUtil.convertShortToGray(depthShort, depthShort.length, maxDepthThreshold);
//            int[] depthARGB = CameraUtil.convertGrayToARGB(depthGray);

            int[] depthARGB = CameraUtil.convertShortToARGB(depthShort, mCameraParam.frameWidth, mCameraParam.frameHeight, maxDepthThreshold);

            Bitmap depthBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
            depthBitmap.setPixels(depthARGB, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
            depthBitmap = Bitmap.createScaledBitmap(depthBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
            CameraUtil.renderBitmapToTextureview(depthBitmap, mTextureView);

            ByteBuffer depthByteBuffer = ByteBuffer.allocate(depthShort.length * 2);
            depthByteBuffer.asShortBuffer().put(depthShort);
            byte[] depthByte = Arrays.copyOf(depthByteBuffer.array(), depthByteBuffer.array().length);

            synchronized (frameLock){
                latestFrame = depthGray;
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


    private NodeMain mCameraPublishNode = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("depth");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {
            Publisher<std_msgs.String> stringPublisher = connectedNode.newPublisher("~str", std_msgs.String._TYPE);
            Publisher<sensor_msgs.Image> imagePublisher = connectedNode.newPublisher("~frame", sensor_msgs.Image._TYPE);

            connectedNode.executeCancellableLoop(new CancellableLoop() {
                @Override
                protected void loop() throws InterruptedException {

                    Time timestamp = connectedNode.getCurrentTime();

                    sensor_msgs.Image img = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.Image._TYPE);
                    ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());

                    try{
                        dataStream.write(getLatestFrame());
                    }catch (IOException e){
                        e.printStackTrace();
                    }catch (InterruptedException e){
                        e.printStackTrace();
                    }

                    img.setHeight(mCameraParam.frameHeight);
                    img.setWidth(mCameraParam.frameWidth);
                    img.setStep(mCameraParam.frameWidth);
                    img.setEncoding("mono8");
                    img.getHeader().setStamp(timestamp);
                    img.getHeader().setFrameId(topicName);
                    img.setIsBigendian((byte) 1);
                    img.setData(dataStream.buffer().copy());

                    std_msgs.String msg = stringPublisher.newMessage();
                    String meg_string = topicName + " " + timestamp + "\n";
//                    meg_string += dataStream.buffer();
                    msg.setData(meg_string);
                    stringPublisher.publish(msg);

                    dataStream.buffer().clear();
                    imagePublisher.publish(img);
                    Log.d(TAG, topicName + ": Image sent; " + timestamp);

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
