package com.example.evkgnssreceiver

import kotlin.math.abs

internal const val GRAVITY_MPS2 = 9.80665
private const val NS_PER_MS = 1_000_000L
private const val MAX_COAST_NS = 2_000 * NS_PER_MS
private const val HISTORY_NS = 3_000 * NS_PER_MS
internal const val MAX_IMU_GAP_NS = 100 * NS_PER_MS
private const val MAX_IMU_EXTRAPOLATION_NS = 50 * NS_PER_MS

/** Approximate GPS epoch to Android monotonic clock alignment over USB. */
internal class GnssEpochClock(private val fixedDelayMs: Long) {
    private data class Arrival(val gpsNs: Long, val offsetNs: Long)

    private val arrivals = ArrayDeque<Arrival>()
    private var lastTowMs = 0L
    private var lastArrivalNs = 0L
    private var gpsNs = 0L

    var generation = 0
        private set

    fun observe(towMs: Long, arrivalNs: Long): Long? {
        val weekMs = 604_800_000L
        if (towMs !in 0 until weekMs) return null
        if (arrivals.isNotEmpty()) {
            val deltaMs = Math.floorMod(towMs - lastTowMs + weekMs / 2, weekMs) - weekMs / 2
            val deltaNs = deltaMs * NS_PER_MS
            if (deltaMs in -1000L..0L || arrivalNs < lastArrivalNs) return null
            if (deltaMs < 0 || abs(arrivalNs - lastArrivalNs - deltaNs) > 5_000 * NS_PER_MS) {
                arrivals.clear()
                gpsNs = 0
                generation++
            } else {
                gpsNs += deltaNs
            }
        }
        lastTowMs = towMs
        lastArrivalNs = arrivalNs
        arrivals.addLast(Arrival(gpsNs, arrivalNs - gpsNs))
        while (gpsNs - arrivals.first().gpsNs > 10_000 * NS_PER_MS) arrivals.removeFirst()
        if (arrivals.size < 3) return null

        // shortcut: USB minimum-arrival timing estimates jitter; calibrate the residual delay or use a shared hardware clock for absolute timing.
        return gpsNs + arrivals.minOf { it.offsetNs } - fixedDelayMs * NS_PER_MS
    }
}

/** Six states: world-frame velocity and device-frame accelerometer bias. */
internal class VelocityFusion {
    internal data class Estimate(
        val timestampNs: Long,
        val gnssUpdateNs: Long,
        val velocityEnu: DoubleArray,
        val biasDevice: DoubleArray
    )

    private data class ImuFrame(
        val timestampNs: Long,
        val acceleration: DoubleArray,
        val deviceToEnu: DoubleArray
    )

    private data class State(val timestampNs: Long, val x: DoubleArray, val p: DoubleArray)

    private val history = ArrayDeque<ImuFrame>()
    private var posterior: State? = null

    fun addImu(timestampNs: Long, acceleration: DoubleArray, deviceToEnu: DoubleArray) {
        history.lastOrNull()?.let {
            if (timestampNs <= it.timestampNs) return
            if (timestampNs - it.timestampNs > MAX_IMU_GAP_NS) clearImu()
        }
        history.addLast(ImuFrame(timestampNs, acceleration, deviceToEnu))
        while (history.size > 1 && timestampNs - history[1].timestampNs > HISTORY_NS) {
            history.removeFirst()
        }
    }

    fun clearImu() {
        history.clear()
        resetEstimate()
    }

    fun resetEstimate() {
        posterior = null
    }

    fun update(epochNs: Long, velocityEnu: DoubleArray, speedAccuracyMps: Double): Estimate? {
        val oldest = history.firstOrNull()?.timestampNs ?: return null
        val newest = history.last().timestampNs
        if (epochNs < oldest || epochNs - newest > MAX_IMU_EXTRAPOLATION_NS) return null

        val base = posterior
        if (base != null && epochNs <= base.timestampNs) return null
        val variance = speedAccuracyMps.coerceAtLeast(0.05).let { it * it }
        val state = base?.takeIf { epochNs - it.timestampNs <= MAX_COAST_NS }
            ?.let { propagate(it, epochNs) }
        if (state == null) {
            val x = velocityEnu.copyOf(6)
            val p = DoubleArray(36)
            for (i in 0 until 6) p[6 * i + i] = if (i < 3) variance else 0.25
            posterior = State(epochNs, x, p)
        } else {
            // H observes velocity. The cross covariance transfers each innovation into device bias.
            for (axis in 0 until 3) correct(state, axis, velocityEnu[axis], variance)
            posterior = state
        }
        return predict()
    }

    fun predict(): Estimate? {
        val base = posterior ?: return null
        val latestNs = history.lastOrNull()?.timestampNs ?: return null
        val targetNs = maxOf(base.timestampNs, latestNs)
        if (targetNs - base.timestampNs > MAX_COAST_NS) return null
        val now = propagate(base, targetNs) ?: return null
        return Estimate(
            now.timestampNs, base.timestampNs, now.x.copyOfRange(0, 3), now.x.copyOfRange(3, 6)
        )
    }

    private fun propagate(base: State, targetNs: Long): State? {
        val result = State(targetNs, base.x.copyOf(), base.p.copyOf())
        if (base.timestampNs == targetNs) return result
        val first = history.indexOfLast { it.timestampNs <= base.timestampNs }
        if (first < 0) return null

        var fromNs = base.timestampNs
        for (index in first until history.size) {
            val untilNs = minOf(targetNs, history.getOrNull(index + 1)?.timestampNs ?: targetNs)
            if (untilNs > fromNs) {
                advance(result, history[index], (untilNs - fromNs) / 1e9)
                fromNs = untilNs
            }
            if (fromNs == targetNs) return result
        }
        return null
    }

    private fun advance(state: State, frame: ImuFrame, dt: Double) {
        val r = frame.deviceToEnu
        val p = state.p
        val fp = p.copyOf()
        for (row in 0 until 3) {
            for (axis in 0 until 3) {
                state.x[row] += r[3 * row + axis] * (frame.acceleration[axis] - state.x[3 + axis]) * dt
                for (col in 0 until 6) {
                    fp[6 * row + col] -= r[3 * row + axis] * dt * p[6 * (3 + axis) + col]
                }
            }
        }
        state.x[2] -= GRAVITY_MPS2 * dt

        // F = [ I, -R dt ; 0, I ]. First multiply F P above, then (F P) Fᵀ.
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                val index = 6 * row + col
                p[index] = fp[index]
                if (col < 3) {
                    for (axis in 0 until 3) {
                        p[index] -= fp[6 * row + 3 + axis] * r[3 * col + axis] * dt
                    }
                }
            }
            p[6 * row + row] += (if (row < 3) 0.09 else 0.0004) * dt
        }
    }

    private fun correct(state: State, axis: Int, measured: Double, variance: Double) {
        val p = state.p
        val column = DoubleArray(6) { p[6 * it + axis] }
        val innovationVariance = column[axis] + variance
        val innovation = measured - state.x[axis]
        for (row in 0 until 6) {
            val gain = column[row] / innovationVariance
            state.x[row] += gain * innovation
            for (col in 0 until 6) p[6 * row + col] -= gain * column[col]
        }
    }
}
