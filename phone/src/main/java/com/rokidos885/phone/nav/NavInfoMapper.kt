package com.rokidos885.phone.nav

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState as GoogleNavState
import com.rokidos885.protocol.*

/** SDK getters only; no Android state. Unknown future maneuvers degrade to the generic arrow. */
object NavInfoMapper {
    fun toManeuverUpdate(info:NavInfo,seq:Long=0):ManeuverUpdate? {
        val current=info.currentStep ?: return null
        return ManeuverUpdate(seq,toStep(current),info.distanceToCurrentStepMeters ?: 0,
            info.timeToCurrentStepSeconds ?: 0,info.remainingSteps?.firstOrNull()?.let(::toStep))
    }
    fun toStep(step:StepInfo)=Step(mapManeuver(step.maneuver),step.fullRoadName.orEmpty().ifBlank {step.simpleRoadName.orEmpty()},
        step.fullInstructionText.orEmpty(),step.exitNumber.orEmpty(),step.roundaboutTurnNumber ?: 0,
        step.lanes.orEmpty().map {lane->Lane(lane.laneDirections().map {mapLane(it.laneShape())},lane.laneDirections().any {it.isRecommended()==true})})
    fun toNavState(state:Int)=when(state) {
        GoogleNavState.ENROUTE->NavState.ACTIVE
        GoogleNavState.REROUTING->NavState.REROUTING
        GoogleNavState.STOPPED->NavState.ENDED
        else->NavState.ROUTING
    }
    fun mapLane(shape:Int):Maneuver=when(shape) {
        LaneDirection.LaneShape.STRAIGHT->Maneuver.STRAIGHT
        LaneDirection.LaneShape.SLIGHT_LEFT->Maneuver.TURN_SLIGHT_LEFT
        LaneDirection.LaneShape.SLIGHT_RIGHT->Maneuver.TURN_SLIGHT_RIGHT
        LaneDirection.LaneShape.NORMAL_LEFT->Maneuver.TURN_LEFT
        LaneDirection.LaneShape.NORMAL_RIGHT->Maneuver.TURN_RIGHT
        LaneDirection.LaneShape.SHARP_LEFT->Maneuver.TURN_SHARP_LEFT
        LaneDirection.LaneShape.SHARP_RIGHT->Maneuver.TURN_SHARP_RIGHT
        LaneDirection.LaneShape.U_TURN_LEFT->Maneuver.U_TURN_LEFT
        LaneDirection.LaneShape.U_TURN_RIGHT->Maneuver.U_TURN_RIGHT
        else->Maneuver.UNKNOWN
    }
    fun mapManeuver(value:Int):Maneuver=when(value) {
        GoogleManeuver.UNKNOWN->Maneuver.UNKNOWN
        GoogleManeuver.DEPART->Maneuver.DEPART
        GoogleManeuver.DESTINATION->Maneuver.DESTINATION
        GoogleManeuver.DESTINATION_LEFT->Maneuver.DESTINATION_LEFT
        GoogleManeuver.DESTINATION_RIGHT->Maneuver.DESTINATION_RIGHT
        GoogleManeuver.STRAIGHT->Maneuver.STRAIGHT
        GoogleManeuver.TURN_LEFT->Maneuver.TURN_LEFT
        GoogleManeuver.TURN_RIGHT->Maneuver.TURN_RIGHT
        GoogleManeuver.TURN_KEEP_LEFT->Maneuver.FORK_LEFT
        GoogleManeuver.TURN_KEEP_RIGHT->Maneuver.FORK_RIGHT
        GoogleManeuver.TURN_SLIGHT_LEFT->Maneuver.TURN_SLIGHT_LEFT
        GoogleManeuver.TURN_SLIGHT_RIGHT->Maneuver.TURN_SLIGHT_RIGHT
        GoogleManeuver.TURN_SHARP_LEFT->Maneuver.TURN_SHARP_LEFT
        GoogleManeuver.TURN_SHARP_RIGHT->Maneuver.TURN_SHARP_RIGHT
        GoogleManeuver.TURN_U_TURN_CLOCKWISE->Maneuver.U_TURN_RIGHT
        GoogleManeuver.TURN_U_TURN_COUNTERCLOCKWISE->Maneuver.U_TURN_LEFT
        GoogleManeuver.MERGE_UNSPECIFIED->Maneuver.MERGE_UNSPECIFIED
        GoogleManeuver.MERGE_LEFT->Maneuver.MERGE_LEFT
        GoogleManeuver.MERGE_RIGHT->Maneuver.MERGE_RIGHT
        GoogleManeuver.FORK_LEFT->Maneuver.FORK_LEFT
        GoogleManeuver.FORK_RIGHT->Maneuver.FORK_RIGHT
        GoogleManeuver.ON_RAMP_UNSPECIFIED->Maneuver.STRAIGHT
        GoogleManeuver.ON_RAMP_LEFT->Maneuver.ON_RAMP_LEFT
        GoogleManeuver.ON_RAMP_RIGHT->Maneuver.ON_RAMP_RIGHT
        GoogleManeuver.ON_RAMP_KEEP_LEFT->Maneuver.FORK_LEFT
        GoogleManeuver.ON_RAMP_KEEP_RIGHT->Maneuver.FORK_RIGHT
        GoogleManeuver.ON_RAMP_SLIGHT_LEFT->Maneuver.ON_RAMP_LEFT
        GoogleManeuver.ON_RAMP_SLIGHT_RIGHT->Maneuver.ON_RAMP_RIGHT
        GoogleManeuver.ON_RAMP_SHARP_LEFT->Maneuver.ON_RAMP_LEFT
        GoogleManeuver.ON_RAMP_SHARP_RIGHT->Maneuver.ON_RAMP_RIGHT
        GoogleManeuver.ON_RAMP_U_TURN_CLOCKWISE->Maneuver.U_TURN_RIGHT
        GoogleManeuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE->Maneuver.U_TURN_LEFT
        GoogleManeuver.OFF_RAMP_UNSPECIFIED->Maneuver.STRAIGHT
        GoogleManeuver.OFF_RAMP_LEFT->Maneuver.OFF_RAMP_LEFT
        GoogleManeuver.OFF_RAMP_RIGHT->Maneuver.OFF_RAMP_RIGHT
        GoogleManeuver.OFF_RAMP_KEEP_LEFT->Maneuver.FORK_LEFT
        GoogleManeuver.OFF_RAMP_KEEP_RIGHT->Maneuver.FORK_RIGHT
        GoogleManeuver.OFF_RAMP_SLIGHT_LEFT->Maneuver.OFF_RAMP_LEFT
        GoogleManeuver.OFF_RAMP_SLIGHT_RIGHT->Maneuver.OFF_RAMP_RIGHT
        GoogleManeuver.OFF_RAMP_SHARP_LEFT->Maneuver.OFF_RAMP_LEFT
        GoogleManeuver.OFF_RAMP_SHARP_RIGHT->Maneuver.OFF_RAMP_RIGHT
        GoogleManeuver.OFF_RAMP_U_TURN_CLOCKWISE->Maneuver.U_TURN_RIGHT
        GoogleManeuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE->Maneuver.U_TURN_LEFT
        GoogleManeuver.ROUNDABOUT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_STRAIGHT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_LEFT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_RIGHT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_U_TURN_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.ROUNDABOUT_EXIT_CLOCKWISE->Maneuver.ROUNDABOUT_CLOCKWISE
        GoogleManeuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE->Maneuver.ROUNDABOUT_COUNTERCLOCKWISE
        GoogleManeuver.FERRY_BOAT->Maneuver.FERRY
        GoogleManeuver.FERRY_TRAIN->Maneuver.FERRY
        GoogleManeuver.NAME_CHANGE->Maneuver.NAME_CHANGE
        else->Maneuver.UNKNOWN
    }
}
