package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.FlameField
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.TextureRecipe
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import com.tropicalstream.hammerklavier.contract.VenueScene
import com.tropicalstream.hammerklavier.venue.tex.Atlas

/**
 * The Konzertzimmer as a [VenueScene] (PLAN §2.3, §5.5): room-frame meshes built on HKLoader
 * (RoomShell + Fixtures, lights baked per vertex), the atlas and parquet recipes, one
 * [FlameFieldImpl] for the GL thread and the reflection probe. WP0's `Wiring` wraps it as the
 * SceneFactory's venue.
 */
class VenueSceneImpl : VenueScene {
    override val geometry: VenueGeometry = Konzertzimmer.GEOMETRY

    private val flameField by lazy { FlameFieldImpl() }

    override fun meshes(palette: Palette): List<BakedMesh> = RoomShell.build(palette) + Fixtures.build()

    override fun textures(): List<TextureRecipe> = Atlas.recipes()

    override fun flames(): FlameField = flameField

    override fun bakeProbe(centerRoom: FloatArray, out: ByteArray) = ProbeBake.bake(centerRoom, out)
}
