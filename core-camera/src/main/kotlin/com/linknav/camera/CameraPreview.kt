package com.linknav.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPreview(modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(owner){
        onDispose { provider?.unbindAll(); provider=null }
    }

    AndroidView(
        modifier=modifier,
        factory={ ctx ->
            PreviewView(ctx).also { view ->
                if(ctx.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){
                    val future=ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val cameraProvider=future.get()
                        provider=cameraProvider
                        val preview=Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview)
                    },ContextCompat.getMainExecutor(context))
                }
            }
        }
    )
}
