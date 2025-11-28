package com.example.circletochat.ai.model.recommendation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Reads and collects actual hardware specifications from the device.
 * Uses real Android APIs to gather system information.
 */
object DeviceSpecsReader {

    /**
     * Reads the device specifications from the system.
     *
     * @param context Application context used to access system services
     * @return DeviceSpecs containing all detected hardware information
     */
    fun readDeviceSpecs(context: Context): DeviceSpecs {
        return DeviceSpecs(
            totalRamMB = getTotalRamMB(context),
            freeRamMB = getFreeRamMB(context),
            availableStorageMB = getAvailableStorageMB(),
            cpuCores = getCpuCoreCount(),
            cpuArch = getCpuArchitecture(),
            hasNPU = hasNeuralProcessingUnit(context)
        )
    }

    /**
     * Gets total RAM available on the device using Runtime.
     */
    private fun getTotalRamMB(context: Context): Int {
        return try {
            val runtime = Runtime.getRuntime()
            (runtime.maxMemory() / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets currently available free RAM using Runtime.
     */
    private fun getFreeRamMB(context: Context): Int {
        return try {
            val runtime = Runtime.getRuntime()
            (runtime.freeMemory() / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets available storage space on the device's internal storage.
     */
    private fun getAvailableStorageMB(): Int {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val availableBlocks = stat.availableBlocksLong
            val blockSize = stat.blockSizeLong
            ((availableBlocks * blockSize) / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets the number of CPU cores by counting cpu[0-9]+ directories in /sys/devices/system/cpu/.
     */
    private fun getCpuCoreCount(): Int {
        return try {
            val cpuDir = File("/sys/devices/system/cpu/")
            val cpuFiles = cpuDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("cpu\\d+"))
            }
            cpuFiles?.size ?: Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    /**
     * Gets the CPU architecture from Build.SUPPORTED_ABIS.
     */
    private fun getCpuArchitecture(): String? {
        return Build.SUPPORTED_ABIS.firstOrNull()
    }

    /**
     * Checks if the device has a Neural Processing Unit (NPU) using PackageManager.
     */
    private fun hasNeuralProcessingUnit(context: Context): Boolean {
        return try {
            context.packageManager.hasSystemFeature("android.hardware.neuralnetworks")
        } catch (e: Exception) {
            false
        }
    }
}
