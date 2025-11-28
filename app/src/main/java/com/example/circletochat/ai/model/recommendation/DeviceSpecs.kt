package com.example.circletochat.ai.model.recommendation

/**
 * Represents the hardware specifications of the device.
 *
 * @property totalRamMB Total RAM available on the device in megabytes
 * @property freeRamMB Currently available free RAM in megabytes
 * @property availableStorageMB Available storage space in megabytes
 * @property cpuCores Number of CPU cores available
 * @property cpuArch CPU architecture (e.g., "arm64-v8a", "armeabi-v7a")
 * @property hasNPU Whether the device has a Neural Processing Unit
 */
data class DeviceSpecs(
    val totalRamMB: Int,
    val freeRamMB: Int,
    val availableStorageMB: Int,
    val cpuCores: Int,
    val cpuArch: String?,
    val hasNPU: Boolean
)
