package com.example.gabriel.moymerchallenge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

public class CameraFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {

    // Video permissions constants
    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    // Default orientations. Font: Google Samples.
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    // Angle of device, in degrees
    private Integer mSensorOrientation;

    // Class name, for use in Permission Request
    private static final String TAG = "CameraFragment";

    // Constant for requesting video permissions
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;

    // Dialog Fragment name
    private static final String FRAGMENT_DIALOG = "dialog";

    // A MoymerTextureView for camera preview.
    private MoymerTextureView mTextureView;

    // Selected camera. 0 for back, 1 for front
    private int selectedCamera = 0;

    // The camera device reference
    private CameraDevice mCameraDevice;

    // The capture session reference
    private CameraCaptureSession mPreviewSession;

    // Builder for Camera Preview
    private CaptureRequest.Builder mPreviewBuilder;

    // Size fo camera preview
    private Size mPreviewSize;

    // Size of recorded video (output)
    private Size mVideoSize;

    // Thread that run tasks, preventing the UI block
    private HandlerThread mBackgroundThread;

    // Handler of background tasks
    private Handler mBackgroundHandler;

    // The video recorder which will output our video
    private MediaRecorder videoRecorder;

    //Handlers for swiping
    private float x0, x1;
    private final static float MIN_SWIPE = 100;

    // The video absolute path inside the phone
    private String mNextVideoAbsolutePath;

    // Semaphore to prevent the app for exiting without closing the camera
    private Semaphore cameraSemaphore = new Semaphore(1);

    // Recording button
    private FloatingActionButton mRecordingFAB;

    // Feed, Shoot and rotate buttons
    private Button mFeed, mShoot, mRotate;

    // Handles events of the MoymerTextureView
    private TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    transformTextureView(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                }
            };

    // Events of CameraDevice, that will be treated here
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            cameraSemaphore.release();
            if (mTextureView != null) {
                transformTextureView(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
            getActivity().finish();
        }
    };

    // onClick for buttons. Not used.
    @Override
    public void onClick(View view) { }

    // Inflate camera preview after creating, inserting the TextureView
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.camera_preview, container, false);
    }

    // Get TextureView after the view creation
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = view.findViewById(R.id.camera_preview);
    }

    // On exiting the activity, close the camera safely
    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        //UI customizing
        mRecordingFAB = getActivity().findViewById(R.id.recording_fab);
        mRecordingFAB.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        mShoot = getActivity().findViewById(R.id.buttonShoot);
        mFeed = getActivity().findViewById(R.id.buttonFeed);
        mRotate = getActivity().findViewById(R.id.buttonRotate);

        getActivity().findViewById(R.id.buttonContainer).bringToFront();
        getActivity().findViewById(R.id.rotateContainer).bringToFront();

        // Toggle between front and back cameras
        mRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateCamera();
            }
        });

        // Setting up Feed Button objective
        mFeed.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                Intent goToFeed = new Intent(getActivity(), FeedActivity.class);
                startActivity(goToFeed);
                getActivity().finish();
            }
        });

        // Setting up "hold to record, release to stop" feature
        mRecordingFAB.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startRecordingVideo();
                        mRecordingFAB.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
                        mRotate.setVisibility(View.INVISIBLE);
                        mFeed.setVisibility(View.INVISIBLE);
                        mShoot.setVisibility(View.INVISIBLE);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stopRecordingVideo();
                        return true;
                }
                return false;
            }
        });

        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(surfaceTextureListener);
        }

        mTextureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        x0 = event.getX();
                        break;
                    case MotionEvent.ACTION_UP:
                        x1 = event.getX();
                        float deltaX = x1 - x0;
                        if(Math.abs(deltaX) > MIN_SWIPE){
                            //If swipe left
                            if(deltaX > 0){
                                Intent goToFeed = new Intent(getActivity().getApplicationContext(),
                                                            FeedActivity.class);
                                startActivity(goToFeed);
                                getActivity().finish();
                            }
                        }
                        break;
                }
                return true;
            }
        });
    }

    // Function for Rotate Camera button. Changes from front camera to back camera and vice-versa.
    private void rotateCamera(){
        closeCamera();
        stopBackgroundThread();
        selectedCamera = -selectedCamera + 1;
        startBackgroundThread();
        openCamera(mTextureView.getWidth(), mTextureView.getHeight());
    }

    // Returns an instance for the CameraFragment
    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    // Set up the Capture Request for calling the Android camera
    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    // Close our camera preview session
    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    // Calculates the best scale and rotation for our camera preview
    private void transformTextureView(int textureViewWidth, int textureViewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, textureViewWidth, textureViewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) textureViewHeight / mPreviewSize.getHeight(),
                    (float) textureViewWidth/ mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    // Open the preview camera and prepare the recording settings
    private void openCamera(int cameraWidth, int cameraHeight) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[selectedCamera];

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mVideoSize = map.getOutputSizes(MediaRecorder.class)[0];
            mPreviewSize = map.getOutputSizes(SurfaceTexture.class)[0];

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            transformTextureView(cameraWidth, cameraHeight);
            videoRecorder = new MediaRecorder();
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(getActivity(), "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.video_perms_request))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    // Closes the camera preview, and ends the recording
    private void closeCamera(){
        try {
            cameraSemaphore.acquire();
            closePreviewSession();
            if(mCameraDevice != null){
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if(videoRecorder != null){
                videoRecorder.release();
                videoRecorder = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cameraSemaphore.release();
        }
    }

    // Starts our TextureView with the camera preview
    private void startPreview(){
        closePreviewSession();
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            Surface prevSurface = new Surface(texture);
            mPreviewBuilder.addTarget(prevSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(prevSurface),
                    new CameraCaptureSession.StateCallback(){

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(getActivity(), "Preview configuration failed!", Toast.LENGTH_LONG).show();
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Update the preview frames
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //Checks if our application already has video permissions
    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    //Checks if our application should show the request permission
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    // Asks the user if he permits video recording
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    // Shows the warning if the video recording permission is not granted by the user
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.video_perms_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.video_perms_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // Makes the camera wait for recording, initializing the camera preview
    private void startBackgroundThread(){
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    // Quits the camera preview, removing the camera from preview
    private void stopBackgroundThread(){
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    //Recording functions
    //Configure the media recorder we will use
    private void configureVideoRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }

        videoRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoOutputPath(getActivity());
        }
        videoRecorder.setOutputFile(mNextVideoAbsolutePath);
        videoRecorder.setVideoEncodingBitRate(10000000);
        videoRecorder.setVideoFrameRate(30);
        videoRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        videoRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                videoRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                videoRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        videoRecorder.prepare();
    }

    //Get the path where the video will be saved
    private String getVideoOutputPath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".mp4";
    }

    // Starts recording the video
    private void startRecordingVideo() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            configureVideoRecorder();
            videoRecorder.start();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = videoRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Activity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }

    }

    // Stops the video recording, saving the video and then
    // going to ShowVideoActivity to show the video in loop
    private void stopRecordingVideo() {
        // Stop recording, back to initial state
        videoRecorder.stop();

        //Go to ShowVideoActivity, sending the recently recorded video via Intent
        Intent showRecordedVideo = new Intent(getActivity().getApplicationContext(),ShowVideoActivity.class);
        showRecordedVideo.putExtra("path", mNextVideoAbsolutePath);
        startActivity(showRecordedVideo);
        getActivity().finish();
    }

    //The error dialog fragment class
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    //The confirmation dialog fragment class
    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.video_perms_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }
    }


}
