package dev.tushar.mycapsule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * A pet's art: one sprite sheet and a pet.json that says how it is cut up. A pack lives in assets/pets/<id>/ and is made of
 *
 *  - sheet.png: one row per animation, one square cell per frame, the pet facing right with its feet at the bottom;
 *  - pet.json: `{"name": "Cat", "frame": 32, "animations": {"walk": {"row": 3, "frames": 4, "fps": 8}, ...}}`.
 *
 * The animations the pet uses are idle, sit, sleep, walk, happy, fall and grab. The app mirrors the pet to face the other
 * way and turns it to climb, so nothing else needs drawing. tools/make_cat.py draws the default cat.
 */
class PetPack private constructor(
    val id: String,
    val name: String,
    private val sheet: Bitmap,
    private val cell: Int,
    private val animations: Map<String, Anim>,
    val traits: Traits,
) {
    class Anim(val row: Int, val frames: Int, val fps: Int)

    /**
     * How the pet carries itself, from the optional `"traits"` of pet.json: how fast it runs and climbs (1 is the cat's pace)
     * and how often, out of a hundred, it climbs the wall when it reaches the end of the floor.
     */
    class Traits(val run: Float = 1f, val climb: Float = 1f, val climbChance: Int = 55)

    private val cache = HashMap<String, Bitmap>()

    /** Whether the pack has [animation]; the pet falls back to its idle frames for one that is missing. */
    fun has(animation: String) = animations.containsKey(animation)

    fun frames(animation: String) = (animations[animation] ?: animations.getValue(IDLE)).frames

    fun fps(animation: String) = (animations[animation] ?: animations.getValue(IDLE)).fps

    /** The picture for frame [index] of [animation], looped when it runs past the last one. */
    fun frame(animation: String, index: Int): Bitmap {
        val name = if (has(animation)) animation else IDLE
        val anim = animations.getValue(name)
        val column = index.mod(anim.frames)
        return cache.getOrPut("$name/$column") { Bitmap.createBitmap(sheet, column * cell, anim.row * cell, cell, cell) }
    }

    /** How many rows of a cell, at the bottom of each picture, are empty under the pet's feet, as a share of the cell. */
    val footShare = 4f / 32f

    companion object {
        const val IDLE = "idle"
        const val DEFAULT = "cat"

        /** Reads the pack called [id] from the app's assets, or null when it is missing or broken. */
        fun load(context: Context, id: String = DEFAULT): PetPack? {
            return try {
                val dir = "pets/$id"
                val json = JSONObject(context.assets.open("$dir/pet.json").bufferedReader().use { it.readText() })
                val options = BitmapFactory.Options().apply { inScaled = false }
                val sheet = context.assets.open("$dir/sheet.png").use { BitmapFactory.decodeStream(it, null, options) }
                val animations = HashMap<String, Anim>()
                val list = json.getJSONObject("animations")
                list.keys().forEach { key ->
                    val a = list.getJSONObject(key)
                    animations[key] = Anim(a.getInt("row"), a.getInt("frames"), a.optInt("fps", 1).coerceAtLeast(1))
                }
                val traits = json.optJSONObject("traits")?.let {
                    Traits(it.optDouble("run", 1.0).toFloat(), it.optDouble("climb", 1.0).toFloat(), it.optInt("climbChance", 55))
                } ?: Traits()
                if (sheet == null || !animations.containsKey(IDLE)) null
                else PetPack(id, json.optString("name", id), sheet, json.getInt("frame"), animations, traits)
            } catch (e: Exception) {
                null
            }
        }
    }
}
