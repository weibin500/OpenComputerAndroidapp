package com.pcpowercard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.pcpowercard.R
import com.pcpowercard.data.Device
import com.pcpowercard.data.DeviceRepository
import com.pcpowercard.util.LogWriter

class HomeActivity : AppCompatActivity() {

    private lateinit var repo: DeviceRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var networkStatus: TextView
    private lateinit var cameraPreview: PreviewView
    private lateinit var scanStatus: TextView
    private lateinit var btnStopScan: Button
    private lateinit var btnManualBind: Button
    private val devices = mutableListOf<Device>()

    private var scanning = false
    private var lastScanMs = 0L
    private var lastHandledRaw: String? = null
    private var coreProvider: ProcessCameraProvider? = null
    private lateinit var barcodeScanner: BarcodeScanner

    companion object {
        const val REQ_CAMERA = 1001
        const val SCAN_INTERVAL_MS = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        repo = DeviceRepository(this)
        recyclerView = findViewById(R.id.deviceList)
        networkStatus = findViewById(R.id.networkStatusTextView)
        cameraPreview = findViewById(R.id.cameraPreview)
        scanStatus = findViewById(R.id.tvScanStatus)
        btnStopScan = findViewById(R.id.btnStopScan)
        btnManualBind = findViewById(R.id.btnManualBind)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = DeviceAdapter(devices, ::onDeviceClick, ::onDeleteDevice)

        bindDevices()

        findViewById<Button>(R.id.btnShowBind).setOnClickListener {
            showBind()
        }

        btnStopScan.setOnClickListener {
            stopCameraScan()
            findSection(R.id.bindSection, false)
        }

        btnManualBind.setOnClickListener { handleManualBind() }

        barcodeScanner = BarcodeScanning.getClient()
    }

    override fun onResume() {
        super.onResume()
        bindDevices()
    }

    private fun onDeviceClick(device: Device) {
        LogWriter.log("Enter device " + device.name)
        val intent = Intent(this, DeviceActivity::class.java)
        intent.putExtra("mac", device.mac)
        intent.putExtra("name", device.name)
        intent.putExtra("sn", device.sn)
        startActivity(intent)
    }

    private fun onDeleteDevice(device: Device) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_format, device.name, device.mac))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteDevice(device.mac)
                LogWriter.log("Deleted ${device.name}")
                bindDevices()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun bindDevices() {
        devices.clear()
        devices.addAll(repo.loadDevices())
        (recyclerView.adapter as DeviceAdapter).notifyDataSetChanged()
    }

    private fun showBind() {
        findSection(R.id.bindSection, true)
        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startCameraScan()
        }
    }

    private fun startCameraScan() {
        if (scanning) {
            LogWriter.log("startCameraScan skipped: already scanning")
            return
        }
        scanning = true
        lastScanMs = 0L
        lastHandledRaw = null
        LogWriter.log("Starting camera (CameraX) for QR scan")

        cameraPreview.visibility = View.VISIBLE
        scanStatus.visibility = View.VISIBLE
        btnStopScan.visibility = View.VISIBLE
        scanStatus.setText(R.string.scan_starting)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                coreProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(cameraPreview.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                            analyzeFrame(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                scanStatus.setText(R.string.scan_point_camera)
                LogWriter.log("CameraX bound, scanning for QR")
            } catch (e: Exception) {
                scanStatus.text = getString(R.string.camera_error_format, e.message ?: "")
                LogWriter.log("CameraX bind fail: ${e.message}")
                scanning = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraScan() {
        if (!scanning) {
            // 仍尝试解绑（防止手动触发 stop 时 CameraX 残留）
            try { coreProvider?.unbindAll() } catch (_: Exception) {}
        }
        scanning = false
        coreProvider?.let { p ->
            try { p.unbindAll() } catch (_: Exception) {}
        }
        cameraPreview.visibility = View.GONE
        scanStatus.visibility = View.GONE
        btnStopScan.visibility = View.GONE
        lastHandledRaw = null
        lastScanMs = 0L
        LogWriter.log("Camera (CameraX) stopped")
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!scanning) {
            imageProxy.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastScanMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val task = barcodeScanner.process(inputImage)
        task.addOnSuccessListener { barcodes ->
            imageProxy.close()
            for (bar in barcodes) {
                val raw = bar.rawValue
                if (raw != null && raw.isNotEmpty() && raw != lastHandledRaw) {
                    lastHandledRaw = raw
                    runOnUiThread { handleQrScan(raw) }
                    break
                }
            }
        }.addOnFailureListener { e ->
            imageProxy.close()
            LogWriter.log("Barcode process failed: ${e.message}")
        }
    }

    private fun handleQrScan(raw: String) {
        LogWriter.log("QR detected: $raw")
        // 防御：若不在扫码中（用户手动 stop 过快），直接忽略
        // 但通常已在 stopCameraScan 之后进入，lastHandledRaw 已记
        if (!scanning) {
            // 正常路径：stopCameraScan 已把 scanning 置 false，
            // 但 lastHandledRaw 仍需保证本次 raw 不被重复触发
            return
        }
        // 移除超时兜底（扫码成功/失败都关闭预览）
        stopCameraScan()
        val parts = raw.split(",")
        // 所有分支都重置节流 + 上一次 raw，保证下次 startCameraScan 能立即重新捕获
        lastScanMs = 0L
        lastHandledRaw = null
        if (parts.size < 2) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qr_error_title)
                .setMessage(getString(R.string.qr_error_format, raw))
                .show()
            LogWriter.log("QR rejected: bad format ($raw)")
            return
        }
        val macRaw = parts[0].trim()
        val sn = parts[1].trim()
        val name = if (parts.size > 2) parts[2].trim() else "PC-" + sn
        if (macRaw.isEmpty() || sn.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qr_error_title)
                .setMessage(getString(R.string.qr_empty_error_format, raw))
                .show()
            LogWriter.log("QR rejected: empty MAC/SN")
            return
        }
        val hex = macRaw.replace(":", "").replace("-", "")
        if (hex.length != 12 || !hex.all { it in "0123456789abcdef" }) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mac_error_title)
                .setMessage(getString(R.string.mac_error_format, macRaw))
                .show()
            LogWriter.log("QR rejected: bad MAC ($macRaw)")
            return
        }
        val dashedMac = (0 until 6).map { i -> hex.substring(i * 2, i * 2 + 2) }.joinToString("-")
        val device = Device(mac = dashedMac, sn = sn, name = name)
        val added = repo.addIfNotExists(device)
        bindDevices()
        if (added) {
            LogWriter.log("QR bind success: " + device.name)
            findSection(R.id.bindSection, false)
            onDeviceClick(device)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.duplicate_device_title)
                .setMessage(getString(R.string.duplicate_device_format, name, dashedMac))
                .show()
            LogWriter.log("QR rejected: already exists ($dashedMac)")
        }
    }

    private fun handleManualBind() {
        val macInput = findViewById<EditText>(R.id.etMac)
        val snInput = findViewById<EditText>(R.id.etSn)
        val mac = macInput.text.toString().trim().lowercase()
        val sn = snInput.text.toString().trim()
        if (mac.isEmpty() || sn.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.input_error_title)
                .setMessage(R.string.mac_or_sn_required)
                .show()
            return
        }
        val hex = mac.replace(":", "").replace("-", "")
        if (hex.length != 12 || !hex.all { it in "0123456789abcdef" }) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mac_error_title)
                .setMessage(R.string.mac_format_error)
                .show()
            return
        }
        val dashedMac = (0 until 6).map { i -> hex.substring(i * 2, i * 2 + 2) }.joinToString("-")
        val device = Device(mac = dashedMac, sn = sn, name = "PC-" + sn)
        val added = repo.addIfNotExists(device)
        bindDevices()
        if (added) {
            LogWriter.log("Manual bind success: " + device.name)
            findSection(R.id.bindSection, false)
            onDeviceClick(device)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.duplicate_device_title)
                .setMessage(R.string.mac_duplicate_error)
                .show()
        }
    }

    private fun findSection(id: Int, show: Boolean) {
        val view = findViewById<android.view.View>(id)
        view?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraScan()
            } else {
                LogWriter.log("Camera permission denied, use manual binding")
                scanStatus.setText(R.string.permission_denied_manual)
                scanStatus.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraScan()
    }

    inner class DeviceAdapter(
        private val items: List<Device>,
        private val onClick: (Device) -> Unit,
        private val onDelete: (Device) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        inner class VH(val card: android.view.View) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = devices[position]
            val nameTv = holder.itemView.findViewById<TextView>(R.id.tvDeviceName)
            val macTv = holder.itemView.findViewById<TextView>(R.id.tvDeviceMac)
            val delBtn = holder.itemView.findViewById<Button>(R.id.btnDeleteDevice)
            nameTv?.text = device.name
            macTv?.text = getString(R.string.device_mac_sn_format, device.mac, device.sn)
            holder.itemView.setOnClickListener { onClick(device) }
            delBtn?.setOnClickListener { onDelete(device) }
        }

        override fun getItemCount() = devices.size
    }
}
