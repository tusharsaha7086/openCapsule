package dev.tushar.mycapsule

import kotlin.random.Random

/** What the pet is standing on, hanging from, flying through or held by. */
enum class PetSurface { FLOOR, LEFT_WALL, RIGHT_WALL, CEILING, AIR, HELD }

enum class PetAction { IDLE, SIT, SLEEP, WALK, HAPPY, FALL, GRAB }

/** How to draw the pet right now: which animation, turned how far, and whether mirrored first. */
class PetPose(val animation: String, val rotation: Float, val mirror: Boolean)

/**
 * What the pet does, with no Android in it: where it is, what it is doing, and when it changes its mind. It walks along the
 * bottom of the screen, climbs the side edges, crosses the top and drops back down, naps, sits, and falls when it is let go.
 * [x] and [y] are the top left of the pet's square in screen pixels; the overlay moves its window there.
 *
 * The art faces right with its feet at the bottom. Climbing turns that art a quarter turn so its feet are on the wall (and
 * half a turn on the ceiling), mirrored when it has to face the other way, so the pack needs no climbing frames of its own.
 */
class PetBrain(private val random: Random = Random.Default) {

    var x = 0f
        private set
    var y = 0f
        private set
    var surface = PetSurface.FLOOR
        private set
    var action = PetAction.IDLE
        private set

    /** Seconds the pet has been running, for the animation frames. */
    var clock = 0f
        private set

    private var width = 0f
    private var floor = 0f
    private var top = 0f
    private var size = 0f
    private var foot = 0f
    private var edge = 0f

    /** +1 is towards larger x on the floor and ceiling, and downwards on a wall; -1 is the other way (up on a wall). */
    private var dir = 1

    /** Which way it faces while on the floor or in the air: +1 right, -1 left. */
    private var facing = 1
    private var wait = 1f
    private var vy = 0f

    /** True while it hangs at the top of a wall for a moment before it lets go. */
    private var hanging = false

    private val minX get() = -edge
    private val maxX get() = width - size + edge
    private val floorY get() = floor - size + foot
    private val ceilY get() = top - foot
    private val leftX get() = -foot
    private val rightX get() = width - size + foot
    private val minY get() = top - edge
    private val maxY get() = floor - size + edge
    private val speed get() = size * RUN_SIZES_PER_SECOND
    private val climbSpeed get() = size * CLIMB_SIZES_PER_SECOND

    /** Tells the pet how big the screen is: its [width], the pixel row of the [floor] and of the [top], its [size] and [footShare]. */
    fun setWorld(width: Int, floor: Int, top: Int, size: Int, footShare: Float) {
        val first = this.size == 0f
        this.width = width.toFloat()
        this.floor = floor.toFloat()
        this.top = top.toFloat()
        this.size = size.toFloat()
        this.foot = size * footShare
        this.edge = size * EDGE_SHARE
        if (first) {
            x = random.nextFloat() * (maxX - minX) + minX
            y = floorY
            surface = PetSurface.FLOOR
            wait = 1f
        } else {
            place()
        }
    }

    /** Puts the pet back on its surface after the screen changed size or turned. */
    private fun place() {
        when (surface) {
            PetSurface.FLOOR -> { x = x.coerceIn(minX, maxX); y = floorY }
            PetSurface.CEILING -> { x = x.coerceIn(minX, maxX); y = ceilY }
            PetSurface.LEFT_WALL -> { x = leftX; y = y.coerceIn(minY, maxY) }
            PetSurface.RIGHT_WALL -> { x = rightX; y = y.coerceIn(minY, maxY) }
            PetSurface.AIR, PetSurface.HELD -> { x = x.coerceIn(minX, maxX); y = y.coerceIn(minY, floorY) }
        }
    }

    fun tick(dt: Float) {
        if (size == 0f) return
        clock += dt
        when (surface) {
            PetSurface.HELD -> Unit
            PetSurface.AIR -> fall(dt)
            else -> {
                wait -= dt
                if (action == PetAction.WALK) move(dt)
                if (wait <= 0f) decide()
            }
        }
    }

    // --- what to do next ---

    private fun decide() {
        if (hanging) {
            hanging = false
            startFall()
            return
        }
        val roll = random.nextInt(PERCENT)
        when (surface) {
            PetSurface.FLOOR -> when {
                roll < 55 -> walk(randomDir(), seconds(2f, 6f))
                roll < 75 -> rest(PetAction.SIT, seconds(3f, 6f))
                roll < 85 -> rest(PetAction.SLEEP, seconds(8f, 14f))
                else -> rest(PetAction.IDLE, seconds(1.5f, 3.5f))
            }
            PetSurface.LEFT_WALL, PetSurface.RIGHT_WALL -> when {
                roll < 65 -> walk(-1, seconds(1.5f, 4.5f))
                roll < 80 -> walk(1, seconds(1f, 3f))
                else -> rest(PetAction.IDLE, seconds(1f, 3f))
            }
            PetSurface.CEILING -> when {
                roll < 15 -> startFall()
                roll < 75 -> walk(randomDir(), seconds(1.5f, 4.5f))
                else -> rest(PetAction.IDLE, seconds(1f, 3f))
            }
            else -> Unit
        }
    }

    private fun walk(direction: Int, secs: Float) {
        action = PetAction.WALK
        dir = direction
        if (surface == PetSurface.FLOOR) facing = direction
        wait = secs
    }

    private fun rest(what: PetAction, secs: Float) {
        action = what
        wait = secs
    }

    private fun randomDir() = if (random.nextBoolean()) 1 else -1

    private fun seconds(from: Float, to: Float) = from + random.nextFloat() * (to - from)

    // --- moving ---

    private fun move(dt: Float) {
        when (surface) {
            PetSurface.FLOOR -> {
                x += dir * speed * dt
                facing = dir
                if (x <= minX) {
                    x = minX
                    atFloorEnd(left = true)
                } else if (x >= maxX) {
                    x = maxX
                    atFloorEnd(left = false)
                }
            }
            PetSurface.CEILING -> {
                x += dir * speed * dt
                if (x <= minX) {
                    x = minX
                    toWall(left = true, down = true)
                } else if (x >= maxX) {
                    x = maxX
                    toWall(left = false, down = true)
                }
            }
            PetSurface.LEFT_WALL, PetSurface.RIGHT_WALL -> {
                y += dir * climbSpeed * dt
                if (y <= minY) {
                    y = minY
                    atWallTop()
                } else if (y >= maxY) {
                    val left = surface == PetSurface.LEFT_WALL
                    surface = PetSurface.FLOOR
                    y = floorY
                    facing = if (left) 1 else -1
                    rest(PetAction.IDLE, seconds(0.5f, 1.5f))
                }
            }
            else -> Unit
        }
    }

    /** At either end of the floor it climbs the wall about half the time, and turns round the rest. */
    private fun atFloorEnd(left: Boolean) {
        if (random.nextInt(PERCENT) < 55) {
            toWall(left, down = false)
        } else {
            dir = if (left) 1 else -1
            facing = dir
        }
    }

    /** Onto the wall on the [left] or right side: from the floor going up, or from the ceiling going [down]. */
    private fun toWall(left: Boolean, down: Boolean) {
        surface = if (left) PetSurface.LEFT_WALL else PetSurface.RIGHT_WALL
        x = if (left) leftX else rightX
        y = if (down) minY else maxY
        walk(if (down) 1 else -1, seconds(2f, 5f))
    }

    /** At the top of a wall it crosses the ceiling, or hangs a moment and lets go. */
    private fun atWallTop() {
        val left = surface == PetSurface.LEFT_WALL
        if (random.nextInt(PERCENT) < 60) {
            surface = PetSurface.CEILING
            y = ceilY
            x = if (left) minX else maxX
            walk(if (left) 1 else -1, seconds(1.5f, 4f))
        } else {
            hanging = true
            rest(PetAction.IDLE, seconds(1.2f, 2.2f))
        }
    }

    private fun startFall() {
        hanging = false
        if (surface == PetSurface.LEFT_WALL) facing = 1 else if (surface == PetSurface.RIGHT_WALL) facing = -1
        surface = PetSurface.AIR
        action = PetAction.FALL
        vy = 0f
    }

    private fun fall(dt: Float) {
        vy += size * GRAVITY_SIZES_PER_SECOND_SQUARED * dt
        y += vy * dt
        x = x.coerceIn(minX, maxX)
        if (y >= floorY) {
            y = floorY
            vy = 0f
            surface = PetSurface.FLOOR
            rest(PetAction.IDLE, seconds(0.5f, 1.2f))
        }
    }

    // --- touch ---

    /** A finger has picked the pet up. */
    fun grab() {
        hanging = false
        surface = PetSurface.HELD
        action = PetAction.GRAB
    }

    /** The finger moved while holding it: the pet's centre follows. */
    fun dragTo(centerX: Float, centerY: Float) {
        if (surface != PetSurface.HELD) return
        x = centerX - size / 2
        y = centerY - size / 2
    }

    /** The finger let go: near a side or the top the pet grabs on to it, anywhere else it falls. */
    fun release() {
        if (surface != PetSurface.HELD) return
        val centerX = x + size / 2
        val centerY = y + size / 2
        val grip = size * GRIP_SIZES
        when {
            centerX < grip -> cling(PetSurface.LEFT_WALL)
            centerX > width - grip -> cling(PetSurface.RIGHT_WALL)
            centerY < top + grip -> cling(PetSurface.CEILING)
            else -> startFall()
        }
    }

    private fun cling(to: PetSurface) {
        surface = to
        dir = if (to == PetSurface.CEILING) 1 else -1
        place()
        rest(PetAction.IDLE, 1f)
    }

    /** A pat, or something to react to (a notification): it perks up for a moment. */
    fun cheer() {
        if (surface == PetSurface.AIR || surface == PetSurface.HELD) return
        hanging = false
        rest(PetAction.HAPPY, CHEER_SECONDS)
    }

    // --- drawing ---

    fun pose(): PetPose {
        val animation = when (action) {
            PetAction.IDLE -> "idle"
            PetAction.SIT -> "sit"
            PetAction.SLEEP -> "sleep"
            PetAction.WALK -> "walk"
            PetAction.HAPPY -> "happy"
            PetAction.FALL -> "fall"
            PetAction.GRAB -> "grab"
        }
        val rotation = when (surface) {
            PetSurface.LEFT_WALL -> 90f
            PetSurface.RIGHT_WALL -> -90f
            PetSurface.CEILING -> 180f
            else -> 0f
        }
        val mirror = when (surface) {
            PetSurface.LEFT_WALL -> dir < 0
            PetSurface.RIGHT_WALL -> dir > 0
            PetSurface.CEILING -> dir > 0
            else -> facing < 0
        }
        return PetPose(animation, rotation, mirror)
    }

    private companion object {
        const val PERCENT = 100
        const val RUN_SIZES_PER_SECOND = 0.9f
        const val CLIMB_SIZES_PER_SECOND = 0.7f
        const val GRAVITY_SIZES_PER_SECOND_SQUARED = 14f
        const val EDGE_SHARE = 3f / 32f
        const val GRIP_SIZES = 0.7f
        const val CHEER_SECONDS = 2.5f
    }
}
