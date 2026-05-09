package com.example.gpspressurelogger.sensor

/**
 * 加速度センサーの計測と trK/stK 判定を専門に管理するクラス。
 *
 * Android の SensorEvent は LoggingService で受け、MotionStateManager の owner thread 上で
 * 本クラスへ投入する。これにより状態判定の更新スレッドを 1 本に保つ。
 */
class AccelManager(
    private val paramsProvider: MotionStateParamsProvider,
    private val onTrKChanged: (snapshot: TrKTransitionSnapshot) -> Unit
) {

    private val trKDetector = TrKDetector(paramsProvider)
    private val stKSlotClassifier = StKSlotClassifier(paramsProvider)
    private val accelerationTransformer = WorldAccelerationTransformer()
    private var lastTrKSnapshot: TrKSnapshot? = null
    private var pendingTrKImmediate: TrKTransitionSnapshot? = null

    /**
     * 前回 base_cycle 以降の加速度列を消費し、stK を取得する。
     */
    fun consumeStKSnapshot(now: Long = System.currentTimeMillis()): StKSnapshot = synchronized(this) {
        stKSlotClassifier.consumeSlot(now)
    }

    /**
     * 現在の 1 秒窓 trK スナップショットを返す。
     *
     * base cycle 時点の補助ログ保存用であり、GPS 即時取得フラグは更新しない。
     */
    fun snapshotTrK(now: Long = System.currentTimeMillis()): TrKSnapshot = synchronized(this) {
        val snapshot = trKDetector.update(now)
        lastTrKSnapshot = snapshot
        snapshot
    }

    /**
     * 前回 base_cycle 以降に trK4 遷移で GPS 即時取得を要求したかを返す。
     *
     * 実際の即時取得は trK callback で行い、この値は3秒補助ログの GpsImmediate と
     * 状態スナップショットの辻褄を合わせるために使う。
     */
    fun consumeTrKImmediateForSlot(): TrKTransitionSnapshot? = synchronized(this) {
        val immediate = pendingTrKImmediate
        pendingTrKImmediate = null
        immediate
    }

    fun addLinearAccelerationSample(ax: Float, ay: Float, az: Float, timestampMs: Long) {
        val changedSnapshot = synchronized(this) {
            val sample = accelerationTransformer.transformLinearAcceleration(ax, ay, az, timestampMs)
            trKDetector.addSample(sample)
            stKSlotClassifier.addSample(sample)
            updateTrKAndNotifyLocked(timestampMs)
        }
        changedSnapshot?.let(onTrKChanged)
    }

    fun updateRotationVector(values: FloatArray) = synchronized(this) {
        accelerationTransformer.updateRotationVector(values)
    }

    fun updateOrientationAcceleration(ax: Float, ay: Float, az: Float) = synchronized(this) {
        accelerationTransformer.updateOrientationAcceleration(ax, ay, az)
    }

    fun updateMagneticField(x: Float, y: Float, z: Float) = synchronized(this) {
        accelerationTransformer.updateMagneticField(x, y, z)
    }

    fun addAccelerationNormSample(norm: Float, timestampMs: Long) {
        val changedSnapshot = synchronized(this) {
            trKDetector.addNormSample(norm.coerceAtLeast(0f), timestampMs)
            stKSlotClassifier.addNormSample(norm.coerceAtLeast(0f), timestampMs)
            updateTrKAndNotifyLocked(timestampMs)
        }
        changedSnapshot?.let(onTrKChanged)
    }

    private fun updateTrKAndNotifyLocked(timestampMs: Long): TrKTransitionSnapshot? {
        val snapshot = trKDetector.update(timestampMs)
        val previousStatus = lastTrKSnapshot?.status
        lastTrKSnapshot = snapshot
        if (previousStatus == null || snapshot.status != previousStatus) {
            val transition = TrKTransitionSnapshot(
                trK = snapshot,
                stK = stKSlotClassifier.peekSlot(timestampMs)
            )
            if (snapshot.status == TrKStatus.TRK4) {
                pendingTrKImmediate = transition
            }
            return transition
        }
        return null
    }
}
