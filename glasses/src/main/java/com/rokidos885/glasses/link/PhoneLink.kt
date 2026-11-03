package com.rokidos885.glasses.link

import android.content.Context
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import com.rokidos885.protocol.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

interface PhoneLink {
    val connected:StateFlow<Boolean>
    val incoming:Flow<NavMessage>
    fun send(message:NavMessage)
    fun close()
}

/** One application-lifetime bridge: this SDK exposes neither unsubscribe nor a bridge destructor. */
class CxrPhoneLink private constructor(context:Context):PhoneLink {
    private val mutableConnected=MutableStateFlow(false)
    override val connected=mutableConnected.asStateFlow()
    private val messages=MutableSharedFlow<NavMessage>(extraBufferCapacity=128,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    override val incoming=messages.asSharedFlow()
    private val bridge=CXRServiceBridge(context.applicationContext)
    init {
        bridge.subscribe(Channels.PHONE_TO_GLASSES,object:CXRServiceBridge.MsgCallback {
            override fun onReceive(name:String?,args:Caps?,bytes:ByteArray?){
                if(name!=Channels.PHONE_TO_GLASSES || args==null)return
                val message=runCatching {if(args.size()!=2)null else ProtocolCodec.decode(args.at(0).string,args.at(1).string)}.getOrNull() ?: return
                messages.tryEmit(message)
            }
        })
        bridge.setStatusListener(object:CXRServiceBridge.StatusListener {
            override fun onConnected(a:String?,b:String?,type:Int){mutableConnected.value=true}
            override fun onDisconnected(){mutableConnected.value=false}
            override fun onConnecting(a:String?,b:String?,type:Int){}
            override fun onARTCStatus(value:Float,enabled:Boolean){}
            override fun onRokidAccountChanged(account:String?){}
            override fun onAudioNoise(value:Float){}
        })
    }
    override fun send(message:NavMessage) {
        if(!connected.value)return
        runCatching {val (magic,body)=ProtocolCodec.encode(message);bridge.sendMessage(Channels.GLASSES_TO_PHONE,Caps().apply {write(magic);write(body)})}
    }
    override fun close()=Unit // Owned by the application; collectors end with their ViewModel.
    companion object {
        @Volatile private var instance:CxrPhoneLink?=null
        fun get(context:Context):CxrPhoneLink=instance ?: synchronized(this){instance ?: CxrPhoneLink(context).also {instance=it}}
    }
}
