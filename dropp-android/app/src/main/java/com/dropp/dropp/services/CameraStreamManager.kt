package com.dropp.dropp.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

data class CameraLensInfo(
    val id: String,
    val name: String,
    val facing: String,
    val lensType: String,
    val focalLength: Float,
    val fNumber: Float,
    val sensorOrientation: Int,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val supportedResolutions: List<String>
)

object CameraStreamManager {
    private const val TAG = "CameraStreamManager"
    const val CAMERA_STREAM_PORT = 8768
    private const val MAGIC_CAM1 = 0x43414D31 // "CAM1"

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private val isStreaming = AtomicBoolean(false)
    private var currentCameraId: String = "0"
    private var targetPhysicalCameraId: String? = null
    private var currentWidth: Int = 1920
    private var currentHeight: Int = 1080
    private var currentFps: Int = 30
    private var currentZoom: Float = 1.0f
    private var isTorchOn: Boolean = false
    private var sensorOrientation: Int = 90
    private var minZoomRatio: Float = 1.0f
    private var maxZoomRatio: Float = 10.0f

    private val writeLock = Any()

    fun getAvailableLenses(context: Context): List<CameraLensInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = manager
        val lenses = mutableListOf<CameraLensInfo>()

        try {
            val cameraIds = manager.cameraIdList
            Log.d(TAG, "Discovering camera lenses across ${cameraIds.size} logical camera IDs: ${cameraIds.joinToString()}")

            for (id in cameraIds) {
                try {
                    val chars = manager.getCameraCharacteristics(id)
                    val facingInt = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
                    val focalLength = if (focalLengths.isNotEmpty()) focalLengths[0] else 0f
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(0f)
                    val fNumber = if (apertures.isNotEmpty()) apertures[0] else 0f
                    val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                    val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    } else null
                    val minZoom = zoomRange?.lower ?: 1.0f
                    val maxZoom = zoomRange?.upper ?: 1.0f

                    val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                    val facingStr = when (facingInt) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        else -> "EXTERNAL"
                    }

                    val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val allSizes = configMap?.getOutputSizes(ImageFormat.JPEG)?.map { "${it.width}x${it.height}" } ?: emptyList()
                    val targetSizes = listOf("3840x2160", "1920x1080", "1280x720", "640x480")
                    val filteredSizes = targetSizes.filter { it in allSizes }.ifEmpty { listOf("1920x1080", "1280x720") }

                    // Check physical cameras if multi-camera
                    val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { chars.physicalCameraIds } catch (_: Exception) { emptySet() }
                    } else emptySet()

                    if (facingInt == CameraCharacteristics.LENS_FACING_FRONT) {
                        lenses.add(
                            CameraLensInfo(
                                id = id,
                                name = "Front Selfie (${focalLength}mm f/${fNumber})",
                                facing = facingStr,
                                lensType = "FRONT",
                                focalLength = focalLength,
                                fNumber = fNumber,
                                sensorOrientation = orientation,
                                maxZoom = maxZoom,
                                hasFlash = hasFlash,
                                supportedResolutions = filteredSizes
                            )
                        )
                    } else {
                        // Back logical camera
                        // 1. Back Main Wide 1x
                        lenses.add(
                            CameraLensInfo(
                                id = "$id:WIDE",
                                name = "Back Main Wide 1x (${focalLength}mm f/${fNumber})",
                                facing = facingStr,
                                lensType = "WIDE",
                                focalLength = focalLength,
                                fNumber = fNumber,
                                sensorOrientation = orientation,
                                maxZoom = maxZoom,
                                hasFlash = hasFlash,
                                supportedResolutions = filteredSizes
                            )
                        )

                        // 2. Back Ultra-Wide 0.5x
                        if (minZoom < 0.95f || physicalIds.size > 1) {
                            val uwZoom = if (minZoom < 1.0f) minZoom else 0.5f
                            lenses.add(
                                CameraLensInfo(
                                    id = "$id:UW",
                                    name = "Back Ultra-Wide 0.5x (f/${fNumber})",
                                    facing = facingStr,
                                    lensType = "ULTRA_WIDE",
                                    focalLength = (focalLength * 0.5f),
                                    fNumber = fNumber,
                                    sensorOrientation = orientation,
                                    maxZoom = maxZoom,
                                    hasFlash = hasFlash,
                                    supportedResolutions = filteredSizes
                                )
                            )
                        }

                        // 3. Back Telephoto (3x/5x)
                        if (maxZoom >= 3.0f || physicalIds.size > 2) {
                            lenses.add(
                                CameraLensInfo(
                                    id = "$id:TELE",
                                    name = "Back Telephoto 3x (Optical Tele)",
                                    facing = facingStr,
                                    lensType = "TELEPHOTO",
                                    focalLength = (focalLength * 3.0f),
                                    fNumber = fNumber,
                                    sensorOrientation = orientation,
                                    maxZoom = maxZoom,
                                    hasFlash = hasFlash,
                                    supportedResolutions = filteredSizes
                                )
                            )
                        }

                        // Also query individual physical cameras if available
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            for (pid in physicalIds) {
                                try {
                                    val pChars = manager.getCameraCharacteristics(pid)
                                    val pFocalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
                                    val pFocal = if (pFocalLengths.isNotEmpty()) pFocalLengths[0] else 0f
                                    val pApertures = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(0f)
                                    val pAperture = if (pApertures.isNotEmpty()) pApertures[0] else 0f
                                    val pOrient = pChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: orientation

                                    val (pType, pName) = when {
                                        pFocal in 0.1f..3.5f -> "ULTRA_WIDE" to "Physical Ultra-Wide (${pFocal}mm f/${pAperture})"
                                        pFocal >= 8.0f -> "TELEPHOTO" to "Physical Telephoto (${pFocal}mm f/${pAperture})"
                                        else -> "WIDE" to "Physical Lens ID $pid (${pFocal}mm f/${pAperture})"
                                    }

                                    // Add if not already present
                                    if (lenses.none { it.id == "$id:phys:$pid" }) {
                                        lenses.add(
                                            CameraLensInfo(
                                                id = "$id:phys:$pid",
                                                name = pName,
                                                facing = facingStr,
                                                lensType = pType,
                                                focalLength = pFocal,
                                                fNumber = pAperture,
                                                sensorOrientation = pOrient,
                                                maxZoom = maxZoom,
                                                hasFlash = hasFlash,
                                                supportedResolutions = filteredSizes
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying camera characteristics for ID '$id': ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing camera IDs: ${e.message}", e)
        }

        return lenses
    }

    fun handleCameraInfoRequest(context: Context, json: JSONObject) {
        val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }
        Log.d(TAG, "Handling CAMERA_INFO_REQ: requestId='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lenses = getAvailableLenses(context)
                val array = JSONArray()

                for (lens in lenses) {
                    val obj = JSONObject().apply {
                        put("id", lens.id)
                        put("name", lens.name)
                        put("facing", lens.facing)
                        put("lensType", lens.lensType)
                        put("focalLength", lens.focalLength.toDouble())
                        put("fNumber", lens.fNumber.toDouble())
                        put("sensorOrientation", lens.sensorOrientation)
                        put("maxZoom", lens.maxZoom.toDouble())
                        put("hasFlash", lens.hasFlash)
                        put("resolutions", JSONArray(lens.supportedResolutions))
                    }
                    array.put(obj)
                }

                val res = JSONObject().apply {
                    put("type", "CAMERA_INFO_RES")
                    put("Type", "CAMERA_INFO_RES")
                    put("requestId", requestId)
                    put("RequestId", requestId)
                    put("isStreaming", isStreaming.get())
                    put("currentCameraId", currentCameraId)
                    put("currentWidth", currentWidth)
                    put("currentHeight", currentHeight)
                    put("lenses", array)
                }

                Log.d(TAG, "Dispatching CAMERA_INFO_RES to Windows (${lenses.size} lenses found)")
                CommandTransferManager.sendCommand(context, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing CAMERA_INFO_REQ: ${e.message}", e)
            }
        }
    }

    fun handleCameraStartRequest(context: Context, json: JSONObject) {
        val cameraId = json.optString("cameraId").ifEmpty { json.optString("CameraId") }.ifEmpty { "0" }
        val width = if (json.has("width")) json.optInt("width", 1920) else json.optInt("Width", 1920)
        val height = if (json.has("height")) json.optInt("height", 1080) else json.optInt("Height", 1080)
        val fps = if (json.has("fps")) json.optInt("fps", 30) else json.optInt("Fps", 30)

        Log.i(TAG, "Received CAMERA_START_REQ: cameraId='$cameraId', resolution=${width}x${height}, fps=$fps")
        startStreaming(context, cameraId, width, height, fps)
    }

    fun handleCameraStopRequest(context: Context, json: JSONObject) {
        Log.i(TAG, "Received CAMERA_STOP_REQ. Stopping camera streaming...")
        stopStreaming()
    }

    fun handleCameraControlRequest(context: Context, json: JSONObject) {
        val zoom = if (json.has("zoom")) json.optDouble("zoom").toFloat() else if (json.has("Zoom")) json.optDouble("Zoom").toFloat() else null
        val torch = if (json.has("torch")) json.optBoolean("torch") else if (json.has("Torch")) json.optBoolean("Torch") else null
        val switchCameraId = json.optString("switchCameraId").ifEmpty { json.optString("SwitchCameraId") }

        if (switchCameraId.isNotEmpty() && switchCameraId != currentCameraId) {
            Log.i(TAG, "Switching camera lens to '$switchCameraId'")
            switchLens(context, switchCameraId)
        }

        if (zoom != null || torch != null) {
            updateControls(zoom, torch)
        }
    }

    @Synchronized
    fun startStreaming(context: Context, cameraId: String, width: Int, height: Int, fps: Int) {
        stopStreaming()

        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        currentFps = fps
        isStreaming.set(true)

        // 1. Update foreground service notification and type
        DroppBackgroundService.updateCameraForeground(true)

        // 2. Bring MainActivity to front to satisfy Android 14+ camera foreground restriction
        try {
            val intent = Intent(context, com.dropp.dropp.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring MainActivity to front: ${e.message}")
        }

        startBackgroundThread()
        startSocketServer()

        // Give the window manager ~350ms to establish top window focus before acquiring camera hardware
        backgroundHandler?.postDelayed({
            if (isStreaming.get()) {
                openCamera(context, cameraId, width, height, 0)
            }
        }, 350)
    }

    @Synchronized
    fun switchLens(context: Context, newCameraId: String) {
        if (!isStreaming.get()) return
        Log.i(TAG, "Switching lens from $currentCameraId to $newCameraId")

        closeCameraOnly()
        currentCameraId = newCameraId
        openCamera(context, newCameraId, currentWidth, currentHeight, 0)
    }

    fun updateControls(zoom: Float?, torch: Boolean?) {
        if (zoom != null) currentZoom = zoom
        if (torch != null) isTorchOn = torch

        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val clampedZoom = currentZoom.coerceIn(minZoomRatio, maxZoomRatio)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
                }

                if (isTorchOn) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }

            session.setRepeatingRequest(reqBuilder.build(), null, backgroundHandler)
            Log.d(TAG, "Updated camera controls: zoom=$currentZoom, torch=$isTorchOn")
        } catch (e: Exception) {
            Log.w(TAG, "Error updating camera controls: ${e.message}")
        }
    }

    @Synchronized
    fun stopStreaming() {
        isStreaming.set(false)
        closeCameraOnly()
        closeSocketServer()
        stopBackgroundThread()
        DroppBackgroundService.updateCameraForeground(false)
        Log.i(TAG, "Camera streaming stopped.")
    }

    private fun closeCameraOnly() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("DroppCameraBgThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @Volatile
    private var isServerListening = false

    fun startSocketServer() {
        if (isServerListening && serverSocket != null && !serverSocket!!.isClosed) {
            return
        }
        isServerListening = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = 512 * 1024
                    bind(InetSocketAddress(CAMERA_STREAM_PORT))
                }
                serverSocket = server
                Log.i(TAG, "Camera Video Streaming Server listening on port $CAMERA_STREAM_PORT...")

                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        client.sendBufferSize = 1024 * 1024
                        Log.i(TAG, "Accepted Windows client on camera video port: ${client.inetAddress.hostAddress}")

                        synchronized(writeLock) {
                            try { activeSocket?.close() } catch (_: Exception) {}
                            activeSocket = client
                            outputStream = client.getOutputStream()
                            dataOutputStream = DataOutputStream(outputStream)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Socket accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket fatal error: ${e.message}", e)
                isServerListening = false
            }
        }
    }

    private fun closeSocketServer() {
        synchronized(writeLock) {
            try { dataOutputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { activeSocket?.close() } catch (_: Exception) {}
            dataOutputStream = null
            outputStream = null
            activeSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(context: Context, rawCameraId: String, width: Int, height: Int, retryCount: Int = 0) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = manager

        // Parse lens identifier
        var logicalId = "0"
        targetPhysicalCameraId = null
        var defaultZoom = 1.0f

        when {
            rawCameraId.contains(":UW") -> {
                logicalId = rawCameraId.substringBefore(":UW")
                defaultZoom = 0.5f
            }
            rawCameraId.contains(":TELE") -> {
                logicalId = rawCameraId.substringBefore(":TELE")
                defaultZoom = 3.0f
            }
            rawCameraId.contains(":WIDE") -> {
                logicalId = rawCameraId.substringBefore(":WIDE")
                defaultZoom = 1.0f
            }
            rawCameraId.contains(":phys:") -> {
                logicalId = rawCameraId.substringBefore(":phys:")
                targetPhysicalCameraId = rawCameraId.substringAfter(":phys:")
            }
            else -> {
                logicalId = rawCameraId
            }
        }

        try {
            val chars = manager.getCameraCharacteristics(logicalId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                minZoomRatio = zoomRange?.lower ?: 1.0f
                maxZoomRatio = zoomRange?.upper ?: 10.0f
                currentZoom = defaultZoom.coerceIn(minZoomRatio, maxZoomRatio)
            } else {
                currentZoom = defaultZoom
            }

            // Choose optimal JPEG size matching width and height
            val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = configMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            var bestSize = android.util.Size(width, height)
            var minDiff = Int.MAX_VALUE
            for (size in jpegSizes) {
                val diff = Math.abs(size.width - width) + Math.abs(size.height - height)
                if (diff < minDiff) {
                    minDiff = diff
                    bestSize = size
                }
            }

            Log.i(TAG, "Configuring ImageReader at ${bestSize.width}x${bestSize.height} for camera $logicalId (phys=$targetPhysicalCameraId, zoom=$currentZoom, retry=$retryCount)")
            imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    onFrameAvailable(reader, bestSize.width, bestSize.height)
                }, backgroundHandler)
            }

            manager.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera '$logicalId' opened successfully.")
                    cameraDevice = camera
                    createCaptureSession(context, camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera '$logicalId' disconnected.")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera '$logicalId' error: $error (attempt $retryCount)")
                    camera.close()
                    cameraDevice = null
                    if ((error == 3 || error == 1) && retryCount < 3 && isStreaming.get()) {
                        Log.i(TAG, "Retrying openCamera for '$logicalId' in 500ms...")
                        backgroundHandler?.postDelayed({
                            if (isStreaming.get()) {
                                openCamera(context, rawCameraId, width, height, retryCount + 1)
                            }
                        }, 500)
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera '$logicalId': ${e.message}", e)
        }
    }

    private fun createCaptureSession(context: Context, camera: CameraDevice) {
        val reader = imageReader ?: return
        try {
            val surface = reader.surface

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Camera capture session configured.")
                    captureSession = session
                    try {
                        val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.JPEG_QUALITY, 85.toByte())

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val clampedZoom = currentZoom.coerceIn(minZoomRatio, maxZoomRatio)
                                set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
                            }

                            if (isTorchOn) {
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                        }
                        session.setRepeatingRequest(reqBuilder.build(), null, backgroundHandler)
                        Log.i(TAG, "Started repeating capture request: zoom=$currentZoom")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting repeating capture request: ${e.message}", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed.")
                }
            }

            // If physical camera specified and API >= 28, configure OutputConfiguration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && targetPhysicalCameraId != null) {
                try {
                    val outputConfig = OutputConfiguration(surface).apply {
                        setPhysicalCameraId(targetPhysicalCameraId)
                    }
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(outputConfig),
                        context.mainExecutor,
                        sessionCallback
                    )
                    camera.createCaptureSession(sessionConfig)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "SessionConfiguration failed, falling back to standard createCaptureSession: ${e.message}")
                }
            }

            camera.createCaptureSession(listOf(surface), sessionCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session: ${e.message}", e)
        }
    }

    private fun onFrameAvailable(reader: ImageReader, width: Int, height: Int) {
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            if (planes.isEmpty()) return

            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val now = System.currentTimeMillis()

            synchronized(writeLock) {
                val dos = dataOutputStream ?: return
                try {
                    // Write Frame Packet:
                    // [4B MAGIC][4B length][4B width][4B height][4B orientation][8B timestamp][data]
                    dos.writeInt(MAGIC_CAM1)
                    dos.writeInt(bytes.size)
                    dos.writeInt(width)
                    dos.writeInt(height)
                    dos.writeInt(sensorOrientation)
                    dos.writeLong(now)
                    dos.write(bytes)
                    dos.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "Socket write error (client disconnected): ${e.message}")
                    try { activeSocket?.close() } catch (_: Exception) {}
                    activeSocket = null
                    outputStream = null
                    dataOutputStream = null
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {}
        }
    }
}
