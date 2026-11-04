package com.rokidos885.phone.nav

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.rokidos885.protocol.*
import org.junit.Assert.*
import org.junit.Test

class NavInfoMapperTest {
    @Test fun everyPublishedManeuverHasAUsefulMapping() {
        GoogleManeuver::class.java.fields.filter {it.type==Int::class.javaPrimitiveType && it.name!="UNKNOWN"}.forEach {
            assertNotEquals(it.name,Maneuver.UNKNOWN,NavInfoMapper.mapManeuver(it.getInt(null)))
        }
        assertEquals(Maneuver.UNKNOWN,NavInfoMapper.mapManeuver(99999))
        assertEquals(Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,NavInfoMapper.mapManeuver(GoogleManeuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE))
    }
    @Test fun realFeedBuildersMapCurrentAndNextWithMissingOptionalFields() {
        val current=StepInfo.builder().setManeuver(GoogleManeuver.TURN_RIGHT).setFullRoadName("Bay Street").build()
        val next=StepInfo.builder().setManeuver(GoogleManeuver.TURN_LEFT).build()
        val info=NavInfo.builder().setCurrentStep(current).setDistanceToCurrentStepMeters(200)
            .setRemainingSteps(arrayOf(next)).build()
        val mapped=NavInfoMapper.toManeuverUpdate(info)!!
        assertEquals(200,mapped.distanceToStepMeters);assertEquals(0,mapped.timeToStepSeconds)
        assertEquals("Bay Street",mapped.current.roadName);assertEquals(Maneuver.TURN_LEFT,mapped.next!!.maneuver)
        assertTrue(mapped.current.lanes.isEmpty())
    }
}
