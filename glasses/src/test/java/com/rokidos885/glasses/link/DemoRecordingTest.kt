package com.rokidos885.glasses.link

import com.rokidos885.glasses.state.HudStore
import com.rokidos885.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DemoRecordingTest {
    @Test fun shippedRecordingHasGeometryCountdownClearAndArrival() {
        val frames=javaClass.getResourceAsStream("/demo_trip.jsonl")!!.bufferedReader().use {DemoRecording.parse(it.lineSequence())}
        val store=HudStore {}
        val now=System.currentTimeMillis()
        var previousDistance=Int.MAX_VALUE
        var alerts=0
        var cleared=false
        frames.forEachIndexed {index,frame->
            val message=DemoRecording.rebase(frame.message,index.toLong()+1,now)
            store.onMessage(message)
            when(message){
                is PositionUpdate->assertTrue(message.timestampMs>=now)
                is Alert->{assertTrue(message.distanceAheadMeters<=previousDistance);previousDistance=message.distanceAheadMeters;alerts++;assertTrue(message.reportedAtMs>=now)}
                is AlertCleared->{cleared=true;assertTrue(store.state.value.alerts.isEmpty())}
                else->Unit
            }
        }
        assertTrue(store.state.value.route.size>2)
        assertTrue(alerts>5)
        assertTrue(cleared)
        assertTrue(frames.any {it.message is DetourOffer})
        assertTrue(frames.any {(it.message as? NavStateUpdate)?.state==NavState.ARRIVED})
    }
}
