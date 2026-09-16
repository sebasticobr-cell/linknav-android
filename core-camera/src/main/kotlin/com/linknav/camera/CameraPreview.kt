package com.linknav.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import kotlin.math.atan
import kotlin.math.tan

data class CameraIntrinsics(
    val horizontalFovDeg:Float,
    val verticalFovDeg:Float,
    val measured:Boolean
) {
    companion object {
        fun fallback()=CameraIntrinsics(
            horizontalFovDeg=55f,
            verticalFovDeg=73f,
            measured=false
        )
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun resolveIntrinsics(camera:Camera,view:PreviewView):CameraIntrinsics {
    return runCatching {
        @Suppress("DEPRECATION")
        val info=Camera2CameraInfo.from(camera.cameraInfo)
        @Suppress("DEPRECATION")
        val focal=info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )?.firstOrNull() ?: return@runCatching CameraIntrinsics.fallback()
        @Suppress("DEPRECATION")
        val physical=info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        ) ?: return@runCatching CameraIntrinsics.fallback()
        @Suppress("DEPRECATION")
        val sensorOrientation=info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 90

        fun fov(sizeMm:Float):Float =
            Math.toDegrees(2.0*atan((sizeMm/(2f*focal)).toDouble())).toFloat()

        var h=fov(physical.width)
        var v=fov(physical.height)

        val displayDegrees=when(view.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relative=((sensorOrientation-displayDegrees)%360+360)%360
        if(relative==90 || relative==270) {
            val t=h
            h=v
            v=t
        }

        if(view.width>0 && view.height>0) {
            val viewAspect=view.width.toDouble()/view.height.toDouble()
            val tanH=tan(Math.toRadians(h.toDouble()/2.0))
            val tanV=tan(Math.toRadians(v.toDouble()/2.0))
            val sourceAspect=tanH/tanV
            if(viewAspect<sourceAspect) {
                h=Math.toDegrees(2.0*atan(tanV*viewAspect)).toFloat()
            } else if(viewAspect>sourceAspect) {
                v=Math.toDegrees(2.0*atan(tanH/viewAspect)).toFloat()
            }
        }

        CameraIntrinsics(
            horizontalFovDeg=h.coerceIn(28f,110f),
            verticalFovDeg=v.coerceIn(35f,125f),
            measured=true
        )
    }.getOrElse { CameraIntrinsics.fallback() }
}

@Composable
fun CameraPreview(
    modifier:Modifier=Modifier,
    torchEnabled:Boolean=false,
    onTorchAvailable:(Boolean)->Unit={},
    onIntrinsics:(CameraIntrinsics)->Unit={}
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
                        val active=camera
                        onTorchAvailable(active?.cameraInfo?.hasFlashUnit()==true)
                        view.post {
                            active?.let { onIntrinsics(resolveIntrinsics(it,view)) }
                        }
                    },ContextCompat.getMainExecutor(context))
                }
            }
        }
    )
}
