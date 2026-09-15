package com.linknav.offline

import java.io.File

data class OfflineBounds(val north:Double,val south:Double,val east:Double,val west:Double)
data class OfflineMapPackage(val id:String,val name:String,val bounds:OfflineBounds,val minZoom:Int,val maxZoom:Int,val directory:File,val createdAtMs:Long=System.currentTimeMillis(),val bytes:Long=0)
interface OfflineMapStore { fun list():List<OfflineMapPackage>; fun delete(id:String):Boolean }
class FileOfflineMapStore(private val root:File):OfflineMapStore {
    override fun list():List<OfflineMapPackage> = root.listFiles()?.filter{it.isDirectory}?.map{d->OfflineMapPackage(d.name,d.name,OfflineBounds(0.0,0.0,0.0,0.0),0,0,d,bytes=d.walkTopDown().filter{it.isFile}.sumOf{it.length()})} ?: emptyList()
    override fun delete(id:String)=File(root,id).deleteRecursively()
}
