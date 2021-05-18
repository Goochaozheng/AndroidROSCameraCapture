package com.MobileSLAM.RosCameraCapture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.TextureView;

import org.ros.android.RosActivity;
import org.ros.node.NodeMainExecutor;

import java.net.URI;

//import rosjava_custom_srv.

public class MainActivity extends RosActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private ColorCameraCapture mColorCameraCapture;
    private DepthCameraCapture mDepthCameraCapture;
    private TextureView colorView;
    private TextureView depthView;

    private static final int REQUEST_CAMERA_PERMISSION = 200;

    static {
        System.loadLibrary("camera-util");
    }

    public MainActivity() {
        super("mobile_camera", "mobile_camera", URI.create("http://192.168.34.147:11311"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check for camera permission
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        colorView = (TextureView) findViewById(R.id.colorPreview);
        depthView = (TextureView) findViewById(R.id.depthPreivew);


        mColorCameraCapture = new ColorCameraCapture(this, colorView);
        mDepthCameraCapture = new DepthCameraCapture(this, depthView);

        mColorCameraCapture.startCamera();
        mDepthCameraCapture.startCamera();
    }

    @Override
    public void init(NodeMainExecutor nodeMainExecutor) {

        Log.d(TAG, "Main Thread: " + String.valueOf(Thread.currentThread().getId()));

        mColorCameraCapture.startRosNode(nodeMainExecutor);
        mDepthCameraCapture.startRosNode(nodeMainExecutor);
    }



}