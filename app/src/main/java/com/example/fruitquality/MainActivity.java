package com.example.fruitquality;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.view.ScaleGestureDetector;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ImageView imageView;
    private Executor cameraExecutor;
    private boolean isUsingFrontCamera = false;
    private int currentFlashMode = ImageCapture.FLASH_MODE_OFF;
    private Camera camera;
    private ScaleGestureDetector scaleGestureDetector;
    private ArrayList<Uri> photoList = new ArrayList<>();
    private Uri lastSavedUri;

    // RecyclerView và Adapter
    private RecyclerView photosRecyclerView;
    private PhotoAdapter photoAdapter;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) startCamera();
                        else Toast.makeText(this, "Cần quyền CAMERA", Toast.LENGTH_LONG).show();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        imageView = findViewById(R.id.imageView);
        Button captureButton = findViewById(R.id.captureButton);
        ImageButton switchCameraButton = findViewById(R.id.switchCameraButton);
        ImageButton flashButton = findViewById(R.id.flashButton);
        Button uploadButton = findViewById(R.id.uploadButton);
        Button viewPhotosButton = findViewById(R.id.viewPhotosButton);
        photosRecyclerView = findViewById(R.id.photosRecyclerView);
        cameraExecutor = ContextCompat.getMainExecutor(this);

        // Setup RecyclerView
        photoAdapter = new PhotoAdapter(photoList);
        photosRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        photosRecyclerView.setAdapter(photoAdapter);
        photosRecyclerView.setVisibility(View.GONE);

        // Xin quyền camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        // Nút chụp ảnh
        captureButton.setOnClickListener(v -> takePhoto());

        // Nút chuyển camera trước/sau
        switchCameraButton.setOnClickListener(v -> {
            isUsingFrontCamera = !isUsingFrontCamera;
            startCamera();
        });

        // Nút bật/tắt flash
        flashButton.setOnClickListener(v -> {
            currentFlashMode = (currentFlashMode == ImageCapture.FLASH_MODE_OFF)
                    ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
            Toast.makeText(this,
                    "Flash: " + (currentFlashMode == ImageCapture.FLASH_MODE_ON ? "Bật" : "Tắt"),
                    Toast.LENGTH_SHORT).show();
            startCamera();
        });

        // Pinch-to-zoom
        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (camera != null) {
                            float currentRatio = camera.getCameraInfo().getZoomState()
                                    .getValue().getZoomRatio();
                            camera.getCameraControl()
                                    .setZoomRatio(currentRatio * detector.getScaleFactor());
                        }
                        return true;
                    }
                });
        previewView.setOnTouchListener((v, e) -> {
            scaleGestureDetector.onTouchEvent(e);
            return true;
        });

        // Nút upload Firebase
        uploadButton.setOnClickListener(v -> {
            if (lastSavedUri != null) {
                StorageReference ref = FirebaseStorage.getInstance().getReference()
                        .child("images/" + System.currentTimeMillis() + ".jpg");
                ref.putFile(lastSavedUri)
                        .addOnSuccessListener(task ->
                                Toast.makeText(this, "✅ Upload thành công", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e ->
                                Toast.makeText(this, "❌ Upload lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } else {
                Toast.makeText(this, "📷 Chưa có ảnh để upload", Toast.LENGTH_SHORT).show();
            }
        });

        // Nút xem ảnh đã chụp
        viewPhotosButton.setOnClickListener(v -> {
            if (photoList.isEmpty()) {
                Toast.makeText(this, "Chưa có ảnh nào", Toast.LENGTH_SHORT).show();
            } else {
                photosRecyclerView.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Đang hiển thị ảnh đã chụp", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setFlashMode(currentFlashMode)
                        .build();
                CameraSelector cameraSelector = isUsingFrontCamera ?
                        CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Lỗi mở camera", Toast.LENGTH_SHORT).show();
            }
        }, cameraExecutor);
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME,
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/myAppPhotos");

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                .build();

        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                lastSavedUri = output.getSavedUri();
                photoList.add(lastSavedUri);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "📷 Ảnh đã lưu", Toast.LENGTH_SHORT).show();
                    imageView.setVisibility(ImageView.VISIBLE);
                    imageView.setImageURI(lastSavedUri);
                    imageView.setScaleX(isUsingFrontCamera ? -1f : 1f);
                    photoAdapter.notifyItemInserted(photoList.size() - 1);
                });
            }
            @Override
            public void onError(@NonNull ImageCaptureException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Lỗi khi chụp ảnh", Toast.LENGTH_SHORT).show());
            }
        });
    }
}
