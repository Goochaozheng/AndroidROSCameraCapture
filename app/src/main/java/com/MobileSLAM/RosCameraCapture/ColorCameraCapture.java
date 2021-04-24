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
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.ros.android.RosActivity;
import org.ros.concurrent.CancellableLoop;
import org.ros.internal.message.Message;
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
import java.util.Calendar;
import java.util.Date;

import sensor_msgs.CameraInfo;
import std_msgs.*;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ColorCameraCapture{

    final static private String TAG = ColorCameraCapture.class.getSimpleName();

    private RosActivity mMainActivity;
    private TextureView mTextureView;
    private ImageReader mImageReader;

    private CameraManager mCameraManager;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private int imageEncoding = ImageFormat.JPEG;

    private Object frameLock = new Object();
    public boolean hasNext = false;


    /**
     * frame stored in YUV format
     */
    private byte[] latestFrame_y;
    private byte[] latestFrame_u;
    private byte[] latestFrame_v;
    private int yRowStride;
    private int uvRowStride;
    private int uvPixelStride;

    /**
     * frame stored in jpeg format
     */
    private byte[] latestFrame_jpeg;


    public static final String mCameraId = "0";             // Fixed camera id used for samsung s20+
    public static final String topicName = "/color";
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
     * Start camera once the preview surface is ready
     */
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

    /**
     * Start ROS node with image publisher
     * Started node will running in new thread
     * @param nodeMainExecutor ROS activity node executor
     */
    public void startRosNode(NodeMainExecutor nodeMainExecutor){

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mMainActivity.getRosHostname());
        nodeConfiguration.setMasterUri(mMainActivity.getMasterUri());
        nodeConfiguration.setNodeName("~rgb");

        nodeMainExecutor.execute(mCameraPublishNode, nodeConfiguration);
    }

    /**
     * Return color frame in YUV bytes
     */
    public byte[][] getLatestFrame_yuv() throws InterruptedException {

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

    /**
     * Return color frame in JPEG encoding bytes
     */
    public byte[] getLatestFrame_jpeg() throws InterruptedException{
        byte[] copyData;
        synchronized (frameLock){
            if(!hasNext){
                frameLock.wait();
            }
            copyData = Arrays.copyOf(latestFrame_jpeg, latestFrame_jpeg.length);
            hasNext = false;
        }
        return copyData;
    }

    // Return frame in RGB arrays
//    public byte[] getLatestFrame() throws InterruptedException{
//        byte[] copyData;
//        synchronized (frameLock){
//            if(!hasNext){
//                frameLock.wait();
//            }
//            copyData = Arrays.copyOf(latestFrame, latestFrame.length);
//            hasNext = false;
//        }
//        return copyData;
//    }

    /**
     * Callback for camera device state change
     */
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
            mImageReader = ImageReader.newInstance(mCameraParam.frameWidth, mCameraParam.frameHeight, imageEncoding, 2);
            mImageReader.setOnImageAvailableListener(colorImageAvailableListener, null);

            // Configure capture request
            try{
                mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                mCaptureRequestBuilder.addTarget(previewSurface);
                mCaptureRequestBuilder.addTarget(mImageReader.getSurface());

                // create capture session
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), colorCameraCaptureSessionStateCallback, null);

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
     * Callback for image reader, handle incoming color frame
     * Convert parse YUV Image and store current frame
     * */
    private ImageReader.OnImageAvailableListener colorImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {

            Long t1 = System.nanoTime();

            Image img = imageReader.acquireLatestImage();
            if(img == null) return;

            if(img.getFormat() == ImageFormat.JPEG) {
                // Handle JPEG compressed image

                ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                byte[] jpegByte = new byte[buffer.capacity()];
                buffer.get(jpegByte);

                synchronized (frameLock){
                    latestFrame_jpeg = jpegByte;
                    hasNext = true;
                    frameLock.notifyAll();
                }

            } else if(img.getFormat() == ImageFormat.YUV_420_888){
                // Handle raw YUV image

                Image.Plane[] planes = img.getPlanes();
                byte[][] yuvBytes = new byte[planes.length][];
                for(int i = 0; i < planes.length; i++){
                    yuvBytes[i] = new byte[planes[i].getBuffer().capacity()];
                    planes[i].getBuffer().get(yuvBytes[i]);
                }

                yRowStride = planes[0].getRowStride();
                uvRowStride = planes[1].getRowStride();
                uvPixelStride = planes[1].getPixelStride();

//                int[] argbUint32 = CameraUtil.convertByteToUint32(argbByte, mCameraParam.frameWidth, mCameraParam.frameHeight, 4);
//
//                int[] argbUint32 = CameraUtil.convertYUVToARGBUint32(yuvBytes[0], yuvBytes[1], yuvBytes[2],
//                                            yRowStride, uvRowStride, uvPixelStride,
//                                            mCameraParam.frameWidth, mCameraParam.frameHeight);
//
//                Bitmap colorBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
//                colorBitmap.setPixels(argbUint32, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
//                colorBitmap = Bitmap.createScaledBitmap(colorBitmap, mTextureView.getWidth(), mTextureView.getHeight(), false);
//                CameraUtil.renderBitmapToTextureview(colorBitmap, mTextureView);

                synchronized (frameLock){

                    latestFrame_y = yuvBytes[0];
                    latestFrame_u = yuvBytes[1];
                    latestFrame_v = yuvBytes[2];

                    hasNext = true;
                    frameLock.notifyAll();
                }

            } else {
                Log.e(TAG, "Unsupported Image Format");
                return;
            }

            img.close();

            Long t2 = System.nanoTime();
            Log.d("Timing", "Image Reader Process Time: " + String.valueOf((float)(t2 - t1) / 1000000000));
        }
    };


    /**
     * Callback for capture session state change
     * Create repeat capture request
     */
    private CameraCaptureSession.StateCallback colorCameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
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
     * ROS node publishing image message
     * Read current frame and parse YUV into RGB
     * Send frame in sensor_msgs/Image (rgba8)
     */
    private NodeMain mCameraPublishNode = new NodeMain() {
        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("color");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {

            // Image publisher for raw YUV image
            Publisher<sensor_msgs.Image> rawImagePublisher = connectedNode.newPublisher("~image_raw", sensor_msgs.Image._TYPE);
            sensor_msgs.Image raw = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.Image._TYPE);
            raw.setHeight(mCameraParam.frameHeight);
            raw.setWidth(mCameraParam.frameWidth);
            raw.setStep(mCameraParam.frameWidth * 4);
            raw.setEncoding("rgba8");
            raw.getHeader().setFrameId("rgb_raw");
            raw.setIsBigendian((byte) 1);

            // Image publisher for compressed JPEG image
            Publisher<sensor_msgs.CompressedImage> compressedImagePublisher = connectedNode.newPublisher("~image_compressed", sensor_msgs.CompressedImage._TYPE);
            sensor_msgs.CompressedImage compressed = connectedNode.getTopicMessageFactory().newFromType(sensor_msgs.CompressedImage._TYPE);
            compressed.setFormat("jpeg");
            compressed.getHeader().setFrameId("rgb_compressed");

            ChannelBufferOutputStream dataStream = new ChannelBufferOutputStream(MessageBuffers.dynamicBuffer());

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
                protected void loop() {

                    Time timestamp = connectedNode.getCurrentTime();

                    if(imageEncoding == ImageFormat.YUV_420_888){
                        // Send raw image message

                        try {
                            byte[][] yuvBytes = getLatestFrame_yuv();
//                            byte[] argbByte = CameraUtil.convertYUVToRGBA(yuvBytes[0], yuvBytes[1], yuvBytes[2],
//                                    yRowStride, uvRowStride, uvPixelStride,
//                                    mCameraParam.frameWidth, mCameraParam.frameHeight);
//
//                            dataStream.write(argbByte);

                            int[] argbUint32 = CameraUtil.convertYUVToARGBUint32(yuvBytes[0], yuvBytes[1], yuvBytes[2],
                                    yRowStride, uvRowStride, uvPixelStride,
                                    mCameraParam.frameWidth, mCameraParam.frameHeight);

                            Bitmap colorBitmap = Bitmap.createBitmap(mCameraParam.frameWidth, mCameraParam.frameHeight, Bitmap.Config.ARGB_8888);
                            colorBitmap.setPixels(argbUint32, 0, mCameraParam.frameWidth, 0, 0, mCameraParam.frameWidth, mCameraParam.frameHeight);
                            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
                            colorBitmap.compress(Bitmap.CompressFormat.JPEG, 10, byteOutputStream);

                            dataStream.write(byteOutputStream.toByteArray());


                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        compressed.getHeader().setStamp(timestamp);
                        compressed.setData(dataStream.buffer().copy());
                        compressedImagePublisher.publish(compressed);
                        dataStream.buffer().clear();
                        Log.d(TAG, topicName + ": Bitmap JPEG Image Sent; " + timestamp);

//                        raw.getHeader().setStamp(timestamp);
//                        raw.setData(dataStream.buffer().copy());
//                        rawImagePublisher.publish(raw);
//                        dataStream.buffer().clear();
//                        Log.d(TAG, topicName + ": Raw Image Sent; " + timestamp);

                    }else if(imageEncoding == ImageFormat.JPEG){
                        // Send compressed image message

                        try{
                            byte[] jpegByte = getLatestFrame_jpeg();
                            dataStream.write(jpegByte);
                        } catch (IOException e){
                            e.printStackTrace();
                        } catch (InterruptedException e){
                            e.printStackTrace();
                        }

                        compressed.getHeader().setStamp(timestamp);
                        compressed.setData(dataStream.buffer().copy());
                        compressedImagePublisher.publish(compressed);
                        dataStream.buffer().clear();
                        Log.d(TAG, topicName + ": JPEG Image Sent; " + timestamp);

                    }

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
