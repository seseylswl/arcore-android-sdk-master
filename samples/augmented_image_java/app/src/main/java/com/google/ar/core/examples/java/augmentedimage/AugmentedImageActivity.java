/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.augmentedimage;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.examples.java.Answer;
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer;
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer2;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 *
 * <p>In this example, we assume all images are static or moving slowly with a large occupation of
 * the screen. If the target is actively moving, we recommend to check
 * AugmentedImage.getTrackingMethod() and render only when the tracking method equals to
 * FULL_TRACKING. See details in <a
 * href="https://developers.google.com/ar/develop/java/augmented-images/">Recognize and Augment
 * Images</a>.
 */
public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = AugmentedImageActivity.class.getSimpleName();

    private int AR = 0;

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private ImageView fitToScanView;
    private RequestManager glideRequestManager;

    // 버튼입력
    private Button btn_go_answer;

    private boolean installRequested;

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);


    /////// 렌더링 객체
    // 화면에 띄워줄 객체 생성부분
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

    // 1번 이미지를 위한 객체
    private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

    // 2번 이미지를 위한 객체체
    private final AugmentedImageRenderer2 augmentedImageRenderer2 = new AugmentedImageRenderer2();





    private boolean shouldConfigureSession = false;

    // Augmented image configuration and rendering.
    // Load a single image (true) or a pre-generated image database (false).
    private final boolean useSingleImage = false;
    // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
    // the
    // database.
    private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

        // 버튼입력
        btn_go_answer = (Button) findViewById(R.id.btn_go_answer);
        btn_go_answer.setOnClickListener(onClickListener);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);

        fitToScanView = findViewById(R.id.image_view_fit_to_scan1);
        glideRequestManager = Glide.with(this);
        glideRequestManager
                .load(Uri.parse("file:///android_asset/fit_to_scan1.png"))
                .into(fitToScanView);

        installRequested = false;


        // 인텐트 어떤 버튼에서 왔는지 받는 부분
        AR = (int) getIntent().getIntExtra("AR", 0);

    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (AR != 0) {

            if (session == null) {
                Exception exception = null;
                String message = null;
                try {
                    switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                        case INSTALL_REQUESTED:
                            installRequested = true;
                            return;
                        case INSTALLED:
                            break;
                    }

                    // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                    // permission on Android M and above, now is a good time to ask the user for it.
                    if (!CameraPermissionHelper.hasCameraPermission(this)) {
                        CameraPermissionHelper.requestCameraPermission(this);
                        return;
                    }

                    session = new Session(/* context = */ this);
                } catch (UnavailableArcoreNotInstalledException
                        | UnavailableUserDeclinedInstallationException e) {
                    message = "Please install ARCore";
                    exception = e;
                } catch (UnavailableApkTooOldException e) {
                    message = "Please update ARCore";
                    exception = e;
                } catch (UnavailableSdkTooOldException e) {
                    message = "Please update this app";
                    exception = e;
                } catch (Exception e) {
                    message = "This device does not support AR";
                    exception = e;
                }

                if (message != null) {
                    messageSnackbarHelper.showError(this, message);
                    Log.e(TAG, "Exception creating session", exception);
                    return;
                }

                shouldConfigureSession = true;
            }

            if (shouldConfigureSession) {
                configureSession();
                shouldConfigureSession = false;
            }

            // Note that order matters - see the note in onPause(), the reverse applies here.
            try {
                session.resume();
            } catch (CameraNotAvailableException e) {
                messageSnackbarHelper.showError(this, "카메라 사용 불가. 앱을 다시 실행해주세요");
                session = null;
                return;
            }
            surfaceView.onResume();
            displayRotationHelper.onResume();

            fitToScanView.setVisibility(View.VISIBLE);
        } else {
            messageSnackbarHelper.showError(this, "잘못된 접근입니다.");
//        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//        builder.setTitle("에러").setMessage("잘못된 접근입니다.");
//
//        builder.setPositiveButton("돌아가기", new DialogInterface.OnClickListener(){
//            @Override
//            public void onClick(DialogInterface dialog, int id)
//            {
//                finish();
//            }
//        });
//        AlertDialog alertDialog = builder.create();
//        alertDialog.show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                    this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().

            // 렌더링 객체 준비과정
            backgroundRenderer.createOnGlThread(/*context=*/ this);

            augmentedImageRenderer.createOnGlThread(/*context=*/ this);
            augmentedImageRenderer2.createOnGlThread(/*context=*/ this);

        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();
            Camera camera = frame.getCamera();

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame);

            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Visualize augmented images.
            drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba);
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void configureSession() {
        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        if (!setupAugmentedImageDatabase(config)) {
            messageSnackbarHelper.showError(this, "Could not setup augmented image database");
        }
        session.configure(config);
    }

    private void drawAugmentedImages(
            Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba) {
        Collection<AugmentedImage> updatedAugmentedImages =
                frame.getUpdatedTrackables(AugmentedImage.class);

        // Iterate to update augmentedImageMap, remove elements we cannot draw.
        for (AugmentedImage augmentedImage : updatedAugmentedImages) {
            switch (augmentedImage.getTrackingState()) {

                //처음 이미지를 감지
                case PAUSED:
                    Log.d("AugmentedImageActivityLog", "augmentedImage.getTrackingState() : PAUSED");
                    // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
                    // but not yet tracked.
                    // 정지 상태에서 이미지 인식한 상태


                    //String text = String.format("이미지의 인덱스 %d 번 입니다", augmentedImage.getIndex());

                    String text = null;

                    switch (AR) {

                        // 첫번째 이미지에 해당되는 3d오브젝트생성
                        case 1:
                            text = String.format("미니맵 인식", augmentedImage.getIndex());
                            break;

                        // 두번째 이미지에 해당되는 3d오브젝트생성
                        case 2:
                            text = String.format("지도 인식", augmentedImage.getIndex());

                            break;

                        // 세번째 이미지에 해당되는 3d오브젝트생성
                        case 3:
                            // 아직 안넣음 추후에 구현예정

                            break;
                    }





                    messageSnackbarHelper.showMessage(this, text);

                    break;

                //추적 중, pose 최신
                case TRACKING:
                    Log.d("AugmentedImageActivityLog", "augmentedImage.getTrackingState() : TRACKING");
                    // Have to switch to UI Thread to update View.
                    this.runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    fitToScanView.setVisibility(View.GONE);
                                }
                            });

                    // Create a new anchor for newly found images.
                    if (!augmentedImageMap.containsKey(augmentedImage.getIndex())) {
                        Log.d("AugmentedImageActivityLog", "if (!augmentedImageMap.containsKey(augmentedImage.getIndex()))");
                        Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
                        augmentedImageMap.put(
                                augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
                    }
                    break;

                //추적 중지
                case STOPPED:
                    Log.d("AugmentedImageActivityLog", "augmentedImage.getTrackingState() : STOPPED");
                    augmentedImageMap.remove(augmentedImage.getIndex());
                    break;

                default:
                    break;
            }
        }

        // Draw all images in augmentedImageMap
        for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {

            // augmentedImage 이건 오브젝트같고...
            // centerAnchor 이건 오브젝트의 중심값인건가???
            AugmentedImage augmentedImage = pair.first;
            Anchor centerAnchor = augmentedImageMap.get(augmentedImage.getIndex()).second;


            switch (augmentedImage.getTrackingState()) {
                case TRACKING:


                    switch (AR) {

                        // 첫번째 이미지에 해당되는 3d오브젝트생성
                        case 1:
                            augmentedImageRenderer.draw(
                                    viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
                            break;

                        // 두번째 이미지에 해당되는 3d오브젝트생성
                        case 2:
                            augmentedImageRenderer2.draw(
                                    viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
                            break;

                        // 세번째 이미지에 해당되는 3d오브젝트생성
                        case 3:
                            // 아직 안넣음 추후에 구현예정

                            break;
                    }




                default:
                    break;
            }
        }


    }


    // 말그대로 셋업이겠지?
    private boolean setupAugmentedImageDatabase(Config config) {
        AugmentedImageDatabase augmentedImageDatabase;

        // There are two ways to configure an AugmentedImageDatabase:
        // 1. Add Bitmap to DB directly
        // 2. Load a pre-built AugmentedImageDatabase
        // Option 2) has
        // * shorter setup time
        // * doesn't require images to be packaged in apk.

        // 한장만 쓸꺼냐 이거고 이미 false로 되어있음
        if (useSingleImage) {
            Bitmap augmentedImageBitmap = loadAugmentedImageBitmap();
            if (augmentedImageBitmap == null) {
                return false;
            }
            augmentedImageDatabase = new AugmentedImageDatabase(session);
            augmentedImageDatabase.addImage("image_name", augmentedImageBitmap);

        }

        // 실제 여러장을 쓸때는 이쪽으로 빠짐
        else {


            // 세션에 맞는 새로운 데이터베이스를 생성해 넣는다?
            augmentedImageDatabase = new AugmentedImageDatabase(session);
            Bitmap augmentedImageBitmap = loadAugmentedImageBitmap();

            // 메서드를 이용해 비트맵으로 만들고 데이터 베이스에 넣는다.
            switch (AR) {
                case 1:
                    augmentedImageDatabase.addImage("test01", augmentedImageBitmap);
                    break;
                case 2:
                    augmentedImageDatabase.addImage("test02", augmentedImageBitmap);
                    break;
                case 3:
                    augmentedImageDatabase.addImage("test03", augmentedImageBitmap);
                    break;
            }

// This is an alternative way to initialize an AugmentedImageDatabase instance,
// load a pre-existing augmented image database.
//      try (InputStream is = getAssets().open("sample_database.imgdb")) {
//        augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, is);
//      } catch (IOException e) {
//        Log.e(TAG, "IO exception loading augmented image database.", e);
//        return false;
//      }


        }

        config.setAugmentedImageDatabase(augmentedImageDatabase);
        return true;

    }


    // 비트맵 stream으로 쪼개주는 메서드
    private Bitmap loadAugmentedImageBitmap() {
        String fileName;
        switch (AR) {
            case 1:
                fileName = "test01.jpg";
                break;
            case 2:
                fileName = "test02.jpg";
                break;
            case 3:
                fileName = "test03.jpg";
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + AR);
        }
        try (InputStream is = getAssets().open(fileName)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Log.e(TAG, "IO exception loading augmented image bitmap.", e);
        }
        return null;
    }

//  private Bitmap loadAugmentedImageBitmap2() {
//    try (InputStream is = getAssets().open("test02.jpg")) {
//      return BitmapFactory.decodeStream(is);
//    } catch (IOException e) {
//      Log.e(TAG, "IO exception loading augmented image bitmap.", e);
//    }
//    return null;
//  }
//
//  private Bitmap loadAugmentedImageBitmap3() {
//    try (InputStream is = getAssets().open("test03.jpg")) {
//      return BitmapFactory.decodeStream(is);
//    } catch (IOException e) {
//      Log.e(TAG, "IO exception loading augmented image bitmap.", e);
//    }
//    return null;
//  }

    // 버튼입력 클릭 이벤트
    View.OnClickListener onClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                //답변 입력하러 가는 버튼

                case R.id.btn_go_answer:

                    Intent intent = new Intent(AugmentedImageActivity.this, Answer.class);
                    intent.putExtra("AR", 1);
                    startActivity(intent);
                    //break;

            }
        }
    };

}
