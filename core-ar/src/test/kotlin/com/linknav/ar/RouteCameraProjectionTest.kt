package com.linknav.ar

import com.linknav.camera.CameraIntrinsics
import com.linknav.location.GeoPoint
import com.linknav.navigation.DeviceOrientation
import kotlin.test.Test
import kotlin.test.assertTrue

class RouteCameraProjectionTest {
    @Test fun straightRouteConvergesAndDepthOrders() {
        val route=listOf(
            GeoPoint(-11.300000,-41.860000),
            GeoPoint(-11.299000,-41.860000)
        )
        val current=route.first()
        val samples=RouteCameraProjection.resample(route,current,0.0)
        assertTrue(samples.size>=5)
        val p=RouteCameraProjection.project(
            samples,
            DeviceOrientation(
                headingDeg=0f,
                forwardEast=0f,forwardNorth=1f,forwardUp=0f,
                rightEast=1f,rightNorth=0f,rightUp=0f,
                upEast=0f,upNorth=0f,upUp=1f
            ),
            CameraIntrinsics(55f,73f,true),1080f,1920f
        ).filter { it.depthM>.45f }
        assertTrue(p.size>=4)
        assertTrue(p.zipWithNext().all { (a,b) -> b.depthM>a.depthM })
        val maxX=p.maxOf { it.screenX }
        val minX=p.minOf { it.screenX }
        assertTrue((maxX-minX)/1080f < .02f)
    }
}
