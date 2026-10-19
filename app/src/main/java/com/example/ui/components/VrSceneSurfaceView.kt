package com.example.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Advanced VR 180° Half-Scene Magic Window SurfaceView.
 *
 * Solves two core VR Magic Window challenges:
 * 1. Half-Frame Scene Split & True Scene-Center Focusing:
 *    Splits the VR video frame in half (mapping a single eye's scene U in [0.0, 0.5] to the
 *    180° front hemisphere) so that camera recentering (Yaw = 0°, Pitch = 0°) focuses directly
 *    on the optical center of the scene (U = 0.25, V = 0.5) instead of the raw video split seam (U = 0.5).
 * 2. FOV-Aware Scene Boundary Clamping:
 *    Dynamically calculates the camera's horizontal and vertical Field of View (FOV) and clamps
 *    both gyroscope motion and touch panning strictly inside the active scene boundaries
 *    ([-maxYaw, +maxYaw], [-maxPitch, +maxPitch]) with zero dead-zone on direction reversal.
 */
class VrSceneSurfaceView(context: Context) : GLSurfaceView(context), SensorEventListener {

    interface VideoSurfaceListener {
        fun onVideoSurfaceCreated(surface: Surface)
        fun onVideoSurfaceDestroyed(surface: Surface)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val renderer: HalfSceneHemisphereRenderer
    private var surfaceListener: VideoSurfaceListener? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    @Volatile
    private var useSensorRotation: Boolean = true
    private var isSensorRegistered: Boolean = false
    private var isStarted: Boolean = false

    // Incremental local-screen-frame rotation matrices (posture-independent & gimbal-lock free)
    private val rawRotationMatrix = FloatArray(9)
    private val displayRotationMatrix = FloatArray(9)
    private val prevDisplayRotationMatrix = FloatArray(9)
    private var hasPrevRotationMatrix = false

    // Touch pan tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isTouchDragging = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        renderer = HalfSceneHemisphereRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setVideoSurfaceListener(listener: VideoSurfaceListener?) {
        this.surfaceListener = listener
        val existingSurface = videoSurface
        if (existingSurface != null && existingSurface.isValid) {
            listener?.onVideoSurfaceCreated(existingSurface)
        }
    }

    fun setStereoMode(stereoMode: Int) {
        renderer.setStereoMode(stereoMode)
    }

    fun setUseSensorRotation(enabled: Boolean) {
        if (this.useSensorRotation != enabled) {
            this.useSensorRotation = enabled
            hasPrevRotationMatrix = false
            updateSensorRegistration()
        }
    }

    /**
     * Smoothly recenters the camera directly onto the center of the active half-scene
     * (Yaw = 0°, Pitch = 0° -> U = 0.25, V = 0.5) and resets the gyroscope baseline
     * to the phone's current physical orientation without recreating the GL surface.
     */
    fun recenter() {
        hasPrevRotationMatrix = false
        renderer.recenterToSceneMiddle()
    }

    override fun onResume() {
        super.onResume()
        isStarted = true
        hasPrevRotationMatrix = false
        updateSensorRegistration()
    }

    override fun onPause() {
        isStarted = false
        updateSensorRegistration()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        isStarted = false
        updateSensorRegistration()
        mainHandler.post {
            val oldSurface = videoSurface
            val oldTexture = surfaceTexture
            videoSurface = null
            surfaceTexture = null
            if (oldSurface != null) {
                surfaceListener?.onVideoSurfaceDestroyed(oldSurface)
                oldSurface.release()
            }
            oldTexture?.release()
        }
        super.onDetachedFromWindow()
    }

    private fun updateSensorRegistration() {
        val shouldRegister = isStarted && useSensorRotation && rotationSensor != null
        if (shouldRegister && !isSensorRegistered) {
            hasPrevRotationMatrix = false
            sensorManager?.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            isSensorRegistered = true
        } else if (!shouldRegister && isSensorRegistered) {
            sensorManager?.unregisterListener(this)
            isSensorRegistered = false
            hasPrevRotationMatrix = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !useSensorRotation) return
        val type = event.sensor.type
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            type != Sensor.TYPE_ROTATION_VECTOR &&
            type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) {
            return
        }

        try {
            SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
        } catch (_: IllegalArgumentException) {
            if (event.values.size >= 3) {
                val truncated = floatArrayOf(event.values[0], event.values[1], event.values[2])
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, truncated)
            } else {
                return
            }
        }

        @Suppress("DEPRECATION")
        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_90

        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rawRotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                displayRotationMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rawRotationMatrix,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                displayRotationMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rawRotationMatrix,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                displayRotationMatrix
            )
            else -> System.arraycopy(rawRotationMatrix, 0, displayRotationMatrix, 0, 9)
        }

        if (!hasPrevRotationMatrix) {
            System.arraycopy(displayRotationMatrix, 0, prevDisplayRotationMatrix, 0, 9)
            hasPrevRotationMatrix = true
            return
        }

        // Compute relative rotation dR = R_prev^T * R_curr in the phone's local display frame.
        // Forward axis (-Z_curr) projected onto previous frame axes (X_prev, Y_prev, -Z_prev):
        // fx = X_prev . (-Z_curr) = -dR02
        // fy = Y_prev . (-Z_curr) = -dR12
        // fz = (-Z_prev) . (-Z_curr) = +dR22 (approx +1.0)
        val dR02 = prevDisplayRotationMatrix[0] * displayRotationMatrix[2] +
            prevDisplayRotationMatrix[3] * displayRotationMatrix[5] +
            prevDisplayRotationMatrix[6] * displayRotationMatrix[8]
        val dR12 = prevDisplayRotationMatrix[1] * displayRotationMatrix[2] +
            prevDisplayRotationMatrix[4] * displayRotationMatrix[5] +
            prevDisplayRotationMatrix[7] * displayRotationMatrix[8]
        val dR22 = (prevDisplayRotationMatrix[2] * displayRotationMatrix[2] +
            prevDisplayRotationMatrix[5] * displayRotationMatrix[5] +
            prevDisplayRotationMatrix[8] * displayRotationMatrix[8]).coerceAtLeast(1e-6f)

        System.arraycopy(displayRotationMatrix, 0, prevDisplayRotationMatrix, 0, 9)

        val deltaYawDeg = Math.toDegrees(atan2(-dR02.toDouble(), dR22.toDouble())).toFloat()
        val deltaPitchDeg = Math.toDegrees(atan2(-dR12.toDouble(), dR22.toDouble())).toFloat()

        // Filter out only extreme sensor spikes while preserving smooth micro-movements
        val cleanYaw = if (kotlin.math.abs(deltaYawDeg) > 0.0002f && kotlin.math.abs(deltaYawDeg) < 35f) deltaYawDeg else 0f
        val cleanPitch = if (kotlin.math.abs(deltaPitchDeg) > 0.0002f && kotlin.math.abs(deltaPitchDeg) < 35f) deltaPitchDeg else 0f

        if (cleanYaw != 0f || cleanPitch != 0f) {
            renderer.addCameraRotationDelta(cleanYaw, cleanPitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isTouchDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouchDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val widthPx = width.coerceAtLeast(1).toFloat()
                    val heightPx = height.coerceAtLeast(1).toFloat()
                    // Dragging finger left pans camera right; dragging down pans camera up
                    val deltaYaw = -(dx / widthPx) * renderer.currentHorizontalFovDeg
                    val deltaPitch = (dy / heightPx) * renderer.currentVerticalFovDeg
                    renderer.addCameraRotationDelta(deltaYaw, deltaPitch)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchDragging = false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onGlSurfaceTextureCreated(st: SurfaceTexture) {
        mainHandler.post {
            val oldSurface = videoSurface
            val oldTexture = surfaceTexture
            if (oldSurface != null) {
                surfaceListener?.onVideoSurfaceDestroyed(oldSurface)
                oldSurface.release()
            }
            if (oldTexture != null && oldTexture !== st) {
                oldTexture.release()
            }
            surfaceTexture = st
            val newSurface = Surface(st)
            videoSurface = newSurface
            surfaceListener?.onVideoSurfaceCreated(newSurface)
        }
    }

    private inner class HalfSceneHemisphereRenderer : Renderer {
        private val frameAvailable = AtomicBoolean(false)
        private var glSurfaceTexture: SurfaceTexture? = null
        private var oesTextureId: Int = 0

        private val stMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        // FOV and dynamic scene clamping limits
        val currentVerticalFovDeg: Float = 62f
        @Volatile
        var currentHorizontalFovDeg: Float = 94f
            private set

        // The hemisphere spans [-90°, +90°] horizontally & vertically; we keep a 2° margin inside the half-frame
        private val sceneHalfSpanYawDeg = 88f
        private val sceneHalfSpanPitchDeg = 86f

        @Volatile
        private var maxYawDeg: Float = 41f
        @Volatile
        private var maxPitchDeg: Float = 55f

        @Volatile
        private var targetYawDeg: Float = 0f
        @Volatile
        private var targetPitchDeg: Float = 0f

        private var smoothedYawDeg: Float = 0f
        private var smoothedPitchDeg: Float = 0f

        @Volatile
        private var stereoMode: Int = C.STEREO_MODE_LEFT_RIGHT
        private val meshRebuildNeeded = AtomicBoolean(true)

        private var programId: Int = 0
        private var mvpMatrixHandle: Int = 0
        private var texMatrixHandle: Int = 0
        private var positionHandle: Int = 0
        private var texCoordHandle: Int = 0

        private var vertexBuffer: FloatBuffer? = null
        private var uvBuffer: FloatBuffer? = null
        private var indexBuffer: ShortBuffer? = null
        private var indexCount: Int = 0

        init {
            Matrix.setIdentityM(stMatrix, 0)
            Matrix.setIdentityM(projectionMatrix, 0)
            Matrix.setIdentityM(viewMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)
        }

        fun setStereoMode(mode: Int) {
            if (this.stereoMode != mode) {
                this.stereoMode = mode
                meshRebuildNeeded.set(true)
            }
        }

        @Synchronized
        fun addCameraRotationDelta(deltaYawDeg: Float, deltaPitchDeg: Float) {
            targetYawDeg = (targetYawDeg + deltaYawDeg).coerceIn(-maxYawDeg, maxYawDeg)
            targetPitchDeg = (targetPitchDeg + deltaPitchDeg).coerceIn(-maxPitchDeg, maxPitchDeg)
        }

        @Synchronized
        fun recenterToSceneMiddle() {
            targetYawDeg = 0f
            targetPitchDeg = 0f
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            programId = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
            texMatrixHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")
            positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")

            oesTextureId = createExternalOesTexture()
            val st = SurfaceTexture(oesTextureId)
            st.setOnFrameAvailableListener {
                frameAvailable.set(true)
            }
            glSurfaceTexture = st
            buildHemisphereMesh(stereoMode)
            onGlSurfaceTextureCreated(st)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
            Matrix.perspectiveM(projectionMatrix, 0, currentVerticalFovDeg, aspect, 0.1f, 100f)

            val vFovRad = Math.toRadians(currentVerticalFovDeg.toDouble())
            val hFovRad = 2.0 * atan(tan(vFovRad / 2.0) * aspect.toDouble())
            val hFovDeg = Math.toDegrees(hFovRad).toFloat()
            currentHorizontalFovDeg = hFovDeg

            // Strict scene boundary limits: camera center + half FOV never exceeds the cropped half-frame scene
            maxYawDeg = (sceneHalfSpanYawDeg - hFovDeg / 2f).coerceIn(18f, 58f)
            maxPitchDeg = (sceneHalfSpanPitchDeg - currentVerticalFovDeg / 2f).coerceIn(18f, 58f)

            synchronized(this) {
                targetYawDeg = targetYawDeg.coerceIn(-maxYawDeg, maxYawDeg)
                targetPitchDeg = targetPitchDeg.coerceIn(-maxPitchDeg, maxPitchDeg)
                smoothedYawDeg = smoothedYawDeg.coerceIn(-maxYawDeg, maxYawDeg)
                smoothedPitchDeg = smoothedPitchDeg.coerceIn(-maxPitchDeg, maxPitchDeg)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (meshRebuildNeeded.getAndSet(false)) {
                buildHemisphereMesh(stereoMode)
            }

            if (frameAvailable.getAndSet(false)) {
                try {
                    glSurfaceTexture?.updateTexImage()
                    glSurfaceTexture?.getTransformMatrix(stMatrix)
                } catch (_: Throwable) {
                }
            }

            val goalYaw: Float
            val goalPitch: Float
            synchronized(this) {
                goalYaw = targetYawDeg.coerceIn(-maxYawDeg, maxYawDeg)
                goalPitch = targetPitchDeg.coerceIn(-maxPitchDeg, maxPitchDeg)
            }

            // Smooth interpolation for silky head-tracking and smooth recentering to (0°, 0°)
            smoothedYawDeg += (goalYaw - smoothedYawDeg) * 0.28f
            smoothedPitchDeg += (goalPitch - smoothedPitchDeg) * 0.28f
            smoothedYawDeg = smoothedYawDeg.coerceIn(-maxYawDeg, maxYawDeg)
            smoothedPitchDeg = smoothedPitchDeg.coerceIn(-maxPitchDeg, maxPitchDeg)

            val yawRad = Math.toRadians(smoothedYawDeg.toDouble())
            val pitchRad = Math.toRadians(smoothedPitchDeg.toDouble())

            val lookX = (cos(pitchRad) * sin(yawRad)).toFloat()
            val lookY = sin(pitchRad).toFloat()
            val lookZ = (-cos(pitchRad) * cos(yawRad)).toFloat()

            Matrix.setLookAtM(
                viewMatrix, 0,
                0f, 0f, 0f,
                lookX, lookY, lookZ,
                0f, 1f, 0f
            )
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val vBuf = vertexBuffer ?: return
            val tBuf = uvBuffer ?: return
            val iBuf = indexBuffer ?: return

            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, stMatrix, 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vBuf)

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, iBuf)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        /**
         * Builds a high-density 180° front hemisphere mesh (lon in [-90°, +90°], lat in [-90°, +90°])
         * where the video frame is split in half so only a single scene is mapped:
         * - Left-Right (SBS / Default VR): crops the Left Half U in [0.0, 0.5], V in [0.0, 1.0].
         *   At (lon = 0°, lat = 0°), the camera looks directly at (U = 0.25, V = 0.5) — the center of that half!
         * - Top-Bottom (Over-Under): crops the Top Half U in [0.0, 1.0], V in [0.5, 1.0].
         *   At (lon = 0°, lat = 0°), the camera looks directly at (U = 0.5, V = 0.75) — the center of that half!
         */
        private fun buildHemisphereMesh(mode: Int) {
            val stacks = 48
            val slices = 64
            val radius = 10.0f

            val vertexCount = (stacks + 1) * (slices + 1)
            val positions = FloatArray(vertexCount * 3)
            val uvs = FloatArray(vertexCount * 2)
            val indices = ShortArray(stacks * slices * 6)

            var posIdx = 0
            var uvIdx = 0

            val isTopBottom = (mode == C.STEREO_MODE_TOP_BOTTOM)

            for (i in 0..stacks) {
                val vNorm = i.toFloat() / stacks.toFloat() // 0.0 (bottom, -90°) to 1.0 (top, +90°)
                val lat = (vNorm - 0.5f) * Math.PI.toFloat() // -PI/2 .. +PI/2
                val cosLat = cos(lat.toDouble()).toFloat()
                val sinLat = sin(lat.toDouble()).toFloat()

                for (j in 0..slices) {
                    val uNorm = j.toFloat() / slices.toFloat() // 0.0 (left, -90°) to 1.0 (right, +90°)
                    val lon = (uNorm - 0.5f) * Math.PI.toFloat() // -PI/2 .. +PI/2
                    val sinLon = sin(lon.toDouble()).toFloat()
                    val cosLon = cos(lon.toDouble()).toFloat()

                    positions[posIdx++] = radius * cosLat * sinLon
                    positions[posIdx++] = radius * sinLat
                    positions[posIdx++] = -radius * cosLat * cosLon

                    if (isTopBottom) {
                        // Crop Top Half of Over-Under frame
                        uvs[uvIdx++] = uNorm
                        uvs[uvIdx++] = 0.5f + vNorm * 0.5f
                    } else {
                        // Crop Left Half of Side-by-Side frame (U in [0.0, 0.5], center at U = 0.25)
                        uvs[uvIdx++] = uNorm * 0.5f
                        uvs[uvIdx++] = vNorm
                    }
                }
            }

            var idx = 0
            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val first = (i * (slices + 1) + j).toShort()
                    val second = (first + slices + 1).toShort()

                    indices[idx++] = first
                    indices[idx++] = second
                    indices[idx++] = (first + 1).toShort()

                    indices[idx++] = second
                    indices[idx++] = (second + 1).toShort()
                    indices[idx++] = (first + 1).toShort()
                }
            }

            vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(positions)
                    position(0)
                }

            uvBuffer = ByteBuffer.allocateDirect(uvs.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(uvs)
                    position(0)
                }

            indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(indices)
                    position(0)
                }

            indexCount = indices.size
        }

        private fun createExternalOesTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            return texId
        }

        private fun createShaderProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexSampler;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexCoord);
            }
        """
    }
}
