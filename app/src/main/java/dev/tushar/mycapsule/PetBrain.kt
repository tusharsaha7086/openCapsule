package dev.tushar.mycapsule

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * What the pet is standing on, hanging from, flying through or held by. A [PLATFORM] is a ledge somewhere on the screen (the
 * top edge of the capsule or the deck while one is open) that the pet can land on and walk along. While [ROAM] it is free of
 * the edges: there is no gravity, and it wanders about the whole screen, wherever it likes.
 */
enum class PetSurface { FLOOR, LEFT_WALL, RIGHT_WALL, CEILING, PLATFORM, ROAM, AIR, HELD }

enum class PetAction { IDLE, SIT, SLEEP, WALK, HAPPY, FALL, GRAB, DANCE, TIRED, YAWN, ALERT, PURR, DIZZY }

/** How to draw the pet right now: which animation, turned how far, and whether mirrored first. */
class PetPose(val animation: String, val rotation: Float, val mirror: Boolean)

/**
 * What the pet does, with no Android in it: where it is, what it is doing, and when it changes its mind. It walks along the
 * bottom of the screen, climbs the side edges, crosses the top and drops back down, naps, sits, and falls when it is let go.
 * It can be thrown and bounces off the edges, lands on a ledge such as the open deck, purrs when it is held still, gets dizzy
 * when it is shaken or lands hard, and its mood follows the phone: it dances to music, drags about when the battery is low,
 * naps through a focus session and jumps at an alert. [x] and [y] are the top left of the pet's square in screen pixels; the
 * overlay moves its window there.
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

    /** Which way it faces while on the floor, a ledge or in the air: +1 right, -1 left. */
    private var facing = 1
    private var wait = 1f
    private var vx = 0f
    private var vy = 0f

    /** True while it hangs at the top of a wall for a moment before it lets go. */
    private var hanging = false

    /** True from the moment it is thrown until it lands: it spins in the air and gets dizzy on landing. */
    private var thrown = false
    private var spin = 0f
    private var spinSpeed = 0f

    /** True while the pet roams the whole screen instead of keeping to its edges. */
    private var roaming = false
    private var targetX = 0f
    private var targetY = 0f

    // The ledge: its left and right ends and its top, in screen pixels.
    private var hasPlatform = false
    private var platformLeft = 0f
    private var platformRight = 0f
    private var platformTop = 0f

    // What the phone is doing.
    private var music = false
    private var focus = false
    private var lowBattery = false
    private var charging = false
    private var moodKnown = false

    private val minX get() = -edge
    private val maxX get() = width - size + edge
    private val floorY get() = floor - size + foot
    private val ceilY get() = top - foot
    private val leftX get() = -foot
    private val rightX get() = width - size + foot
    private val minY get() = top - edge
    private val maxY get() = floor - size + edge

    /** In the air it can go all the way to the top of the screen, over the status bar, so it can land on a ledge near the top. */
    private val airMinY get() = -edge

    /** The highest and lowest the pet's square goes while it roams. */
    private val roamMinY get() = top
    private val roamMaxY get() = floorY
    private val ledgeY get() = platformTop - size + foot
    private val ledgeMinX get() = platformLeft - edge
    private val ledgeMaxX get() = maxOf(platformRight - size + edge, ledgeMinX)
    private val pace get() = if (lowBattery) TIRED_PACE else 1f
    private val speed get() = size * RUN_SIZES_PER_SECOND * pace * runPace
    private val climbSpeed get() = size * CLIMB_SIZES_PER_SECOND * pace * climbPace

    // The pet's own character: how fast it runs and climbs, and how keen it is on climbing the wall at the end of the floor.
    private var runPace = 1f
    private var climbPace = 1f
    private var climbChance = DEFAULT_CLIMB_CHANCE

    /** Gives the pet its character: [run] and [climb] are paces (1 is the usual), [climbChance] is a percentage. */
    fun setPersonality(run: Float, climb: Float, climbChance: Int) {
        runPace = run
        climbPace = climb
        this.climbChance = climbChance
    }

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

    /**
     * Lets the pet roam the whole screen ([on]) or keep to the edges. Roaming starts from wherever it is. When it ends, a pet in
     * the middle of the screen falls to the floor, where it began.
     */
    fun setRoaming(on: Boolean) {
        if (on == roaming) return
        roaming = on
        if (on) {
            when (surface) {
                PetSurface.FLOOR, PetSurface.LEFT_WALL, PetSurface.RIGHT_WALL, PetSurface.CEILING, PetSurface.PLATFORM -> {
                    hanging = false
                    surface = PetSurface.ROAM
                    place()
                    rest(PetAction.IDLE, 0.5f)
                }
                else -> Unit
            }
        } else if (surface == PetSurface.ROAM) {
            startFall()
        }
    }

    /** Puts the pet back on its surface after the screen changed size or turned. */
    private fun place() {
        when (surface) {
            PetSurface.ROAM -> { x = x.coerceIn(minX, maxX); y = y.coerceIn(roamMinY, roamMaxY) }
            PetSurface.FLOOR -> { x = x.coerceIn(minX, maxX); y = floorY }
            PetSurface.CEILING -> { x = x.coerceIn(minX, maxX); y = ceilY }
            PetSurface.LEFT_WALL -> { x = leftX; y = y.coerceIn(minY, maxY) }
            PetSurface.RIGHT_WALL -> { x = rightX; y = y.coerceIn(minY, maxY) }
            PetSurface.PLATFORM -> if (hasPlatform) { x = x.coerceIn(ledgeMinX, ledgeMaxX); y = ledgeY }
            PetSurface.AIR, PetSurface.HELD -> { x = x.coerceIn(minX, maxX); y = y.coerceIn(airMinY, floorY) }
        }
    }

    /** A ledge is on the screen: its [left] and [right] ends and its [top]. Call again whenever it moves. */
    fun setPlatform(left: Float, top: Float, right: Float) {
        hasPlatform = true
        platformLeft = left
        platformRight = right
        platformTop = top
        if (surface == PetSurface.PLATFORM) place()
    }

    /** The ledge is gone: a pet standing on it falls. */
    fun clearPlatform() {
        if (!hasPlatform) return
        hasPlatform = false
        if (surface == PetSurface.PLATFORM) startFall()
    }

    /**
     * How the phone is doing: music playing, a focus session running, the battery low, the charger in. A change (the charger
     * going in or out) is something the pet notices; the rest only changes what it likes to do next.
     */
    fun setMood(music: Boolean, focus: Boolean, lowBattery: Boolean, charging: Boolean) {
        if (moodKnown) {
            if (charging && !this.charging) cheer()
            if (!charging && this.charging) yawn()
        }
        this.music = music
        this.focus = focus
        this.lowBattery = lowBattery
        this.charging = charging
        moodKnown = true
        if (!music && action == PetAction.DANCE) rest(PetAction.IDLE, 0.5f)
    }

    fun tick(dt: Float) {
        if (size == 0f) return
        clock += dt
        when (surface) {
            PetSurface.HELD -> Unit
            PetSurface.AIR -> fall(dt)
            else -> {
                if (surface == PetSurface.PLATFORM) {
                    if (!hasPlatform) {
                        startFall()
                        return
                    }
                    place()
                }
                if (surface == PetSurface.CEILING && hasPlatform) maybeDropOnto(dt)
                wait -= dt
                if (action == PetAction.WALK) move(dt)
                if (wait <= 0f) decide()
            }
        }
    }

    /** From the ceiling above a ledge, the pet sometimes lets go and lands on it. */
    private fun maybeDropOnto(dt: Float) {
        val centerX = x + size / 2
        if (action != PetAction.HAPPY && action != PetAction.ALERT && centerX in platformLeft..platformRight &&
            ledgeY >= airMinY && random.nextFloat() < dt * DROP_PER_SECOND
        ) {
            // The ledge is only a little way below the ceiling: it hops up a touch so that it lands on the ledge's top.
            startFall()
            y = ledgeY - size * HOP_SIZES
        }
    }

    // --- what to do next ---

    private fun decide() {
        if (hanging) {
            hanging = false
            startFall()
            return
        }
        if (focus) {
            decideQuietly()
            return
        }
        val roll = random.nextInt(PERCENT)
        when (surface) {
            PetSurface.FLOOR -> when {
                music && roll < 50 -> rest(PetAction.DANCE, seconds(5f, 9f))
                roll < 55 -> walk(randomDir(), seconds(2f, 6f))
                roll < 75 -> rest(PetAction.SIT, seconds(3f, 6f))
                roll < 85 -> rest(PetAction.SLEEP, seconds(8f, 14f))
                else -> rest(idle(), seconds(1.5f, 3.5f))
            }
            PetSurface.PLATFORM -> when {
                music && roll < 50 -> rest(PetAction.DANCE, seconds(5f, 9f))
                roll < 35 -> walk(randomDir(), seconds(1.5f, 4f))
                roll < 80 -> rest(PetAction.SIT, seconds(3f, 7f))
                else -> rest(idle(), seconds(1.5f, 3.5f))
            }
            PetSurface.ROAM -> when {
                music && roll < 50 -> rest(PetAction.DANCE, seconds(5f, 9f))
                roll < 60 -> wander()
                roll < 78 -> rest(PetAction.SIT, seconds(3f, 6f))
                roll < 88 -> rest(PetAction.SLEEP, seconds(8f, 14f))
                else -> rest(idle(), seconds(1.5f, 3.5f))
            }
            PetSurface.LEFT_WALL, PetSurface.RIGHT_WALL -> when {
                roll < 65 -> walk(-1, seconds(1.5f, 4.5f))
                roll < 80 -> walk(1, seconds(1f, 3f))
                else -> rest(idle(), seconds(1f, 3f))
            }
            PetSurface.CEILING -> when {
                roll < 15 -> startFall()
                roll < 75 -> walk(randomDir(), seconds(1.5f, 4.5f))
                else -> rest(idle(), seconds(1f, 3f))
            }
            else -> Unit
        }
    }

    /** During a focus session the pet keeps quiet: it comes down and sleeps or sits where it is. */
    private fun decideQuietly() {
        when (surface) {
            PetSurface.FLOOR, PetSurface.PLATFORM, PetSurface.ROAM ->
                if (random.nextInt(PERCENT) < 70) rest(PetAction.SLEEP, seconds(20f, 40f)) else rest(PetAction.SIT, seconds(10f, 20f))
            PetSurface.LEFT_WALL, PetSurface.RIGHT_WALL, PetSurface.CEILING -> startFall()
            else -> Unit
        }
    }

    /** Standing about: worn out while the battery is low. */
    private fun idle() = if (lowBattery) PetAction.TIRED else PetAction.IDLE

    private fun walk(direction: Int, secs: Float) {
        action = PetAction.WALK
        dir = direction
        if (surface == PetSurface.FLOOR || surface == PetSurface.PLATFORM) facing = direction
        wait = secs
    }

    private fun rest(what: PetAction, secs: Float) {
        action = what
        wait = secs
    }

    /** Sets off for a spot somewhere on the screen, not too near: it walks until it gets there. */
    private fun wander() {
        action = PetAction.WALK
        do {
            targetX = minX + random.nextFloat() * (maxX - minX)
            targetY = roamMinY + random.nextFloat() * (roamMaxY - roamMinY)
        } while (hypot(targetX - x, targetY - y) < size * MIN_WANDER_SIZES)
        wait = WANDER_LIMIT_SECONDS
    }

    private fun randomDir() = if (random.nextBoolean()) 1 else -1

    private fun seconds(from: Float, to: Float) = from + random.nextFloat() * (to - from)

    // --- moving ---

    private fun move(dt: Float) {
        when (surface) {
            PetSurface.ROAM -> {
                val dx = targetX - x
                val dy = targetY - y
                val distance = hypot(dx, dy)
                val step = speed * dt
                if (distance <= step) {
                    x = targetX
                    y = targetY
                    rest(idle(), seconds(1f, 3f))
                } else {
                    x += dx / distance * step
                    y += dy / distance * step
                    if (abs(dx) > size * FACING_DEAD_ZONE) facing = if (dx > 0f) 1 else -1
                }
            }
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
            PetSurface.PLATFORM -> {
                x += dir * speed * dt
                facing = dir
                if (x <= ledgeMinX) {
                    x = ledgeMinX
                    atLedgeEnd(left = true)
                } else if (x >= ledgeMaxX) {
                    x = ledgeMaxX
                    atLedgeEnd(left = false)
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
                    rest(idle(), seconds(0.5f, 1.5f))
                }
            }
            else -> Unit
        }
    }

    /** At either end of the floor it climbs the wall about half the time, and turns round the rest. */
    private fun atFloorEnd(left: Boolean) {
        if (random.nextInt(PERCENT) < climbChance) {
            toWall(left, down = false)
        } else {
            dir = if (left) 1 else -1
            facing = dir
        }
    }

    /** At the end of a ledge it sometimes steps off, and otherwise turns round. */
    private fun atLedgeEnd(left: Boolean) {
        if (random.nextInt(PERCENT) < 40) {
            facing = if (left) -1 else 1
            startFall()
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
            rest(idle(), seconds(1.2f, 2.2f))
        }
    }

    private fun startFall() {
        hanging = false
        if (surface == PetSurface.LEFT_WALL) facing = 1 else if (surface == PetSurface.RIGHT_WALL) facing = -1
        surface = PetSurface.AIR
        action = PetAction.FALL
        vx = 0f
        vy = 0f
        thrown = false
    }

    /** While roaming a thrown pet has no gravity: it coasts, slowing down, bounces off the edges, and stops where it is. */
    private fun coast(dt: Float) {
        val slow = (1f - COAST_DAMPING * dt).coerceAtLeast(0f)
        vx *= slow
        vy *= slow
        spinSpeed *= slow
        x += vx * dt
        y += vy * dt
        if (thrown) spin += spinSpeed * dt
        if (x < minX) {
            x = minX
            vx = abs(vx) * SIDE_BOUNCE
        } else if (x > maxX) {
            x = maxX
            vx = -abs(vx) * SIDE_BOUNCE
        }
        if (y < roamMinY) {
            y = roamMinY
            vy = abs(vy) * SIDE_BOUNCE
        } else if (y > roamMaxY) {
            y = roamMaxY
            vy = -abs(vy) * SIDE_BOUNCE
        }
        if (vx != 0f) facing = if (vx > 0f) 1 else -1
        if (hypot(vx, vy) < size * COAST_STOP_SIZES) {
            val wasThrown = thrown
            surface = PetSurface.ROAM
            vx = 0f
            vy = 0f
            thrown = false
            spin = 0f
            spinSpeed = 0f
            if (wasThrown) rest(PetAction.DIZZY, DIZZY_SECONDS) else rest(idle(), seconds(0.5f, 1.2f))
        }
    }

    /** In the air: gravity, bouncing off the sides and top when thrown, landing on a ledge or the floor. */
    private fun fall(dt: Float) {
        if (roaming) {
            coast(dt)
            return
        }
        val before = y
        vy += size * GRAVITY_SIZES_PER_SECOND_SQUARED * dt
        x += vx * dt
        y += vy * dt
        if (thrown) spin += spinSpeed * dt
        if (x < minX) {
            x = minX
            vx = abs(vx) * SIDE_BOUNCE
            spinSpeed = -spinSpeed * 0.6f
        } else if (x > maxX) {
            x = maxX
            vx = -abs(vx) * SIDE_BOUNCE
            spinSpeed = -spinSpeed * 0.6f
        }
        if (y < airMinY) {
            y = airMinY
            vy = abs(vy) * TOP_BOUNCE
        }
        if (vx != 0f) facing = if (vx > 0f) 1 else -1
        if (hasPlatform && vy > 0f) {
            val feetBefore = before + size - foot
            val feetNow = y + size - foot
            val centerX = x + size / 2
            if (centerX in platformLeft..platformRight && feetBefore <= platformTop + 1f && feetNow >= platformTop) {
                y = ledgeY
                land(PetSurface.PLATFORM)
                return
            }
        }
        if (y >= floorY) {
            y = floorY
            if (vy > size * BOUNCE_SPEED_SIZES) {
                vy = -vy * FLOOR_BOUNCE
                vx *= FLOOR_FRICTION
                spinSpeed *= 0.5f
            } else {
                land(PetSurface.FLOOR)
            }
        }
    }

    private fun land(on: PetSurface) {
        val wasThrown = thrown
        surface = on
        vx = 0f
        vy = 0f
        thrown = false
        spin = 0f
        spinSpeed = 0f
        if (wasThrown) rest(PetAction.DIZZY, DIZZY_SECONDS) else rest(idle(), seconds(0.5f, 1.2f))
    }

    // --- touch ---

    /** A finger has picked the pet up. */
    fun grab() {
        hanging = false
        thrown = false
        surface = PetSurface.HELD
        action = PetAction.GRAB
    }

    /** The finger moved while holding it: the pet's centre follows. */
    fun dragTo(centerX: Float, centerY: Float) {
        if (surface != PetSurface.HELD) return
        x = centerX - size / 2
        y = centerY - size / 2
    }

    /**
     * The finger let go, moving at ([velocityX], [velocityY]) pixels a second. Fast, the pet is thrown; slow, it grabs on to a
     * side or the top if it is near one, and otherwise falls.
     */
    fun release(velocityX: Float = 0f, velocityY: Float = 0f) {
        if (surface != PetSurface.HELD) return
        if (hypot(velocityX, velocityY) > size * THROW_SIZES_PER_SECOND) {
            throwIt(velocityX, velocityY)
            return
        }
        if (roaming) {
            // Let go anywhere and it stays there: with no edges to keep to, nothing makes it fall.
            surface = PetSurface.ROAM
            place()
            rest(idle(), 1f)
            return
        }
        val centerX = x + size / 2
        val centerY = y + size / 2
        val grip = size * GRIP_SIZES
        // Let go just above a ledge (the open deck) and the pet drops onto it, instead of clinging to the ceiling.
        if (hasPlatform && centerX in platformLeft..platformRight && y + size - foot <= platformTop + size * LEDGE_REACH_SIZES) {
            y = minOf(y, ledgeY - size * HOP_SIZES)
            startFall()
            return
        }
        when {
            centerX < grip -> cling(PetSurface.LEFT_WALL)
            centerX > width - grip -> cling(PetSurface.RIGHT_WALL)
            centerY < top + grip -> cling(PetSurface.CEILING)
            else -> startFall()
        }
    }

    private fun throwIt(velocityX: Float, velocityY: Float) {
        val limit = size * MAX_THROW_SIZES_PER_SECOND
        surface = PetSurface.AIR
        action = PetAction.FALL
        vx = velocityX.coerceIn(-limit, limit)
        vy = velocityY.coerceIn(-limit, limit)
        thrown = true
        spin = 0f
        spinSpeed = (vx / size * SPIN_DEGREES_PER_SIZE).coerceIn(-MAX_SPIN, MAX_SPIN)
        if (vx != 0f) facing = if (vx > 0f) 1 else -1
    }

    private fun cling(to: PetSurface) {
        surface = to
        dir = if (to == PetSurface.CEILING) 1 else -1
        place()
        rest(idle(), 1f)
    }

    /** A pat: it perks up for a moment. */
    fun cheer() {
        if (surface == PetSurface.AIR || surface == PetSurface.HELD) return
        hanging = false
        rest(PetAction.HAPPY, CHEER_SECONDS)
    }

    /** Held still under a finger, or let go: it purrs while [on], and settles into a happy moment when the finger lifts. */
    fun purr(on: Boolean) {
        if (surface == PetSurface.AIR || surface == PetSurface.HELD) return
        if (on) {
            hanging = false
            rest(PetAction.PURR, PURR_HOLD_SECONDS)
        } else if (action == PetAction.PURR) {
            rest(PetAction.HAPPY, 1.2f)
        }
    }

    /** Something needs attention (a notification, a call, a finished timer): it sits up and shows it, for [seconds]. */
    fun alert(seconds: Float) {
        if (surface == PetSurface.AIR || surface == PetSurface.HELD) return
        hanging = false
        rest(PetAction.ALERT, seconds)
    }

    /** The phone was shaken: the pet is left dizzy. */
    fun dizzy() {
        if (surface == PetSurface.AIR || surface == PetSurface.HELD) return
        hanging = false
        rest(PetAction.DIZZY, DIZZY_SECONDS)
    }

    private fun yawn() {
        if (surface == PetSurface.FLOOR || surface == PetSurface.PLATFORM) rest(PetAction.YAWN, YAWN_SECONDS)
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
            PetAction.DANCE -> "dance"
            PetAction.TIRED -> "tired"
            PetAction.YAWN -> "yawn"
            PetAction.ALERT -> "alert"
            PetAction.PURR -> "purr"
            PetAction.DIZZY -> "dizzy"
        }
        val rotation = when (surface) {
            PetSurface.LEFT_WALL -> 90f
            PetSurface.RIGHT_WALL -> -90f
            PetSurface.CEILING -> 180f
            PetSurface.AIR -> if (thrown) spin else 0f
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
        const val DEFAULT_CLIMB_CHANCE = 55
        const val RUN_SIZES_PER_SECOND = 0.9f
        const val CLIMB_SIZES_PER_SECOND = 0.7f
        const val TIRED_PACE = 0.5f
        const val GRAVITY_SIZES_PER_SECOND_SQUARED = 14f
        const val EDGE_SHARE = 3f / 32f
        const val GRIP_SIZES = 0.7f
        const val CHEER_SECONDS = 2.5f
        const val PURR_HOLD_SECONDS = 3600f
        const val DIZZY_SECONDS = 2.5f
        const val YAWN_SECONDS = 1.8f
        const val DROP_PER_SECOND = 0.7f
        const val MIN_WANDER_SIZES = 2f
        const val WANDER_LIMIT_SECONDS = 40f
        const val FACING_DEAD_ZONE = 0.05f
        const val COAST_DAMPING = 2.2f
        const val COAST_STOP_SIZES = 0.6f
        const val HOP_SIZES = 0.1f
        const val LEDGE_REACH_SIZES = 0.35f
        const val THROW_SIZES_PER_SECOND = 4f
        const val MAX_THROW_SIZES_PER_SECOND = 30f
        const val SPIN_DEGREES_PER_SIZE = 60f
        const val MAX_SPIN = 900f
        const val SIDE_BOUNCE = 0.7f
        const val TOP_BOUNCE = 0.5f
        const val FLOOR_BOUNCE = 0.45f
        const val FLOOR_FRICTION = 0.7f
        const val BOUNCE_SPEED_SIZES = 3f
    }
}
