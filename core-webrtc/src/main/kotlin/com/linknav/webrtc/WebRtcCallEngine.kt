package com.linknav.webrtc

import android.content.Context
import org.webrtc.*

class WebRtcCallEngine(private val context:Context, private val listener:Listener){
    interface Listener {
        fun onLocalSdp(type:String,sdp:String)
        fun onIce(candidate:IceCandidate)
        fun onState(state:CallState)
        fun onRemoteVideo(track:VideoTrack){}
    }
    private val egl=EglBase.create()
    private val factory:PeerConnectionFactory
    private var pc:PeerConnection?=null
    private var audioSource:AudioSource?=null; private var audioTrack:AudioTrack?=null
    private var videoSource:VideoSource?=null; private var videoTrack:VideoTrack?=null; private var capturer:VideoCapturer?=null
    private var surfaceTextureHelper:SurfaceTextureHelper?=null
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setEnableInternalTracer(false).createInitializationOptions())
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
    }
    fun start(config:CallConfig,createOffer:Boolean){
        closePeer()
        val servers=config.iceServers.map{ c -> PeerConnection.IceServer.builder(c.urls).setUsername(c.username).setPassword(c.credential).createIceServer() }
        val rtc=PeerConnection.RTCConfiguration(servers).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN; continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY }
        pc=factory.createPeerConnection(rtc,object:PeerConnection.Observer{
            override fun onIceCandidate(c:IceCandidate){listener.onIce(c)}
            override fun onConnectionChange(s:PeerConnection.PeerConnectionState){ listener.onState(when(s){PeerConnection.PeerConnectionState.CONNECTED->CallState.CONNECTED;PeerConnection.PeerConnectionState.CONNECTING,PeerConnection.PeerConnectionState.NEW->CallState.CONNECTING;PeerConnection.PeerConnectionState.DISCONNECTED->CallState.RECONNECTING;PeerConnection.PeerConnectionState.CLOSED->CallState.ENDED;PeerConnection.PeerConnectionState.FAILED->CallState.FAILED}) }
            override fun onTrack(t:RtpTransceiver?){ (t?.receiver?.track() as? VideoTrack)?.let(listener::onRemoteVideo) }
            override fun onSignalingChange(x:PeerConnection.SignalingState?){}
            override fun onIceConnectionChange(x:PeerConnection.IceConnectionState?){}
            override fun onIceConnectionReceivingChange(x:Boolean){}
            override fun onIceGatheringChange(x:PeerConnection.IceGatheringState?){}
            override fun onIceCandidatesRemoved(x:Array<out IceCandidate>?){}
            override fun onAddStream(x:MediaStream?){}
            override fun onRemoveStream(x:MediaStream?){}
            override fun onDataChannel(x:DataChannel?){}
            override fun onRenegotiationNeeded(){}
            override fun onAddTrack(x:RtpReceiver?,y:Array<out MediaStream>?){}
        })
        if(config.audioEnabled){ audioSource=factory.createAudioSource(MediaConstraints()); audioTrack=factory.createAudioTrack("audio",audioSource); pc?.addTrack(audioTrack) }
        if(config.videoEnabled){ startVideo() }
        if(createOffer) createOffer()
    }
    private fun startVideo(){
        val e=Camera2Enumerator(context); val name=e.deviceNames.firstOrNull{e.isFrontFacing(it)} ?: e.deviceNames.firstOrNull() ?: return
        capturer=e.createCapturer(name,null); videoSource=factory.createVideoSource(false)
        surfaceTextureHelper=SurfaceTextureHelper.create("LinkNavCamera",egl.eglBaseContext)
        capturer?.initialize(surfaceTextureHelper,context,videoSource?.capturerObserver); capturer?.startCapture(1280,720,30)
        videoTrack=factory.createVideoTrack("video",videoSource); pc?.addTrack(videoTrack)
    }
    fun createOffer(){ pc?.createOffer(sdp("offer"),MediaConstraints()) }
    fun setRemoteDescription(type:String,sdpText:String,thenAnswer:Boolean=false){
        val typeEnum=if(type.lowercase()=="offer")SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        pc?.setRemoteDescription(object:SdpObserver{
            override fun onSetSuccess(){if(thenAnswer)pc?.createAnswer(sdp("answer"),MediaConstraints())}
            override fun onSetFailure(e:String?) {listener.onState(CallState.FAILED)}
            override fun onCreateSuccess(s:SessionDescription?){}
            override fun onCreateFailure(e:String?){}
        },SessionDescription(typeEnum,sdpText))
    }
    fun addIce(sdpMid:String?,sdpMLineIndex:Int,candidate:String){pc?.addIceCandidate(IceCandidate(sdpMid,sdpMLineIndex,candidate))}
    fun setMuted(muted:Boolean){audioTrack?.setEnabled(!muted)}
    fun setVideoEnabled(enabled:Boolean){videoTrack?.setEnabled(enabled)}
    fun switchCamera(){(capturer as? CameraVideoCapturer)?.switchCamera(null)}
    private fun sdp(kind:String)=object:SdpObserver{
        override fun onCreateSuccess(s:SessionDescription?){ if(s==null)return; pc?.setLocalDescription(object:SdpObserver{override fun onSetSuccess(){listener.onLocalSdp(kind,s.description)};override fun onSetFailure(e:String?){};override fun onCreateSuccess(x:SessionDescription?){};override fun onCreateFailure(e:String?){}},s) }
        override fun onSetSuccess(){}
        override fun onCreateFailure(e:String?){listener.onState(CallState.FAILED)}
        override fun onSetFailure(e:String?){}
    }
    fun close(){ closePeer(); factory.dispose(); egl.release() }
    private fun closePeer(){
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer=null
        surfaceTextureHelper?.dispose(); surfaceTextureHelper=null
        videoTrack?.dispose(); videoTrack=null
        videoSource?.dispose(); videoSource=null
        audioTrack?.dispose(); audioTrack=null
        audioSource?.dispose(); audioSource=null
        pc?.close(); pc?.dispose(); pc=null
    }
}
