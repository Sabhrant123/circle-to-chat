package com.example.circletochat.ai.model.recommendation

/**
 * Represents the hardware requirements for a specific AI model.
 *
 * @property name Name of the AI model (e.g., "gemma-E2b", "gemma-E4b")
 * @property requiredRamMB Minimum RAM required to run the model in megabytes
 * @property requiredStorageMB Minimum storage space required for the model in megabytes
 * @property requiredCpuCores Minimum number of CPU cores needed
 * @property requiresNPU Whether the model requires a Neural Processing Unit for optimal performance
 */
data class ModelRequirement(
    val name: String,
    val requiredRamMB: Int,
    val requiredStorageMB: Int,
    val requiredCpuCores: Int,
    val requiresNPU: Boolean = false
)
