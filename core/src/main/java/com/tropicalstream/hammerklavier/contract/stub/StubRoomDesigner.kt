package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomDesigner
import com.tropicalstream.hammerklavier.contract.VenueGeometry

/** Returns [FixedRoom.PLAYER] for every request (PLAN §2.3). */
object StubRoomDesigner : RoomDesigner {
    override fun design(g: VenueGeometry, placement: Placement, sourcePiano: FloatArray, listener: ListenerPose,
                        mode: ReverbMode, benchDistanceM: Float, embeddedRoomDb: Float): RoomDesign = FixedRoom.PLAYER
}
