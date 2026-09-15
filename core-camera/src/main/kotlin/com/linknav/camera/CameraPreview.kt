package com.linknav.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.Camera
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
fun CameraPreview(
    modifier:Modifier=Modifier,
    torchEnabled:Boolean=false,
    onTorchAvailable:(Boolean)->Unit={}
) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(torchEnabled,camera) {
        val c=camera ?: return@LaunchedEffect
        val hasFlash=c.cameraInfo.hasFlashUnit()
        onTorchAvailable(hasFlash)
        if(hasFlash) runCatching {
            c.cameraControl.enableTorch(torchEnabled)
        }
    }

    DisposableEffect(owner) {
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            provider?.unbindAll()
            camera=null
            provider=null
        }
    }

    AndroidView(
        modifier=modifier,
        factory={ ctx ->
            PreviewView(ctx).also { view ->
                view.scaleType=PreviewView.ScaleType.FILL_CENTER
                view.implementationMode=PreviewView.ImplementationMode.COMPATIBLE

                if(
                    ctx.checkSelfPermission(Manifest.permission.CAMERA)==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    val future=ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val cameraProvider=future.get()
                        provider=cameraProvider
                        val preview=Preview.Builder().build().also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }

                        cameraProvider.unbindAll()
                        camera=cameraProvider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                        onTorchAvailable(camera?.cameraInfo?.hasFlashUnit()==true)
                    },ContextCompat.getMainExecutor(context))
                }
            }
        }
    )
}
