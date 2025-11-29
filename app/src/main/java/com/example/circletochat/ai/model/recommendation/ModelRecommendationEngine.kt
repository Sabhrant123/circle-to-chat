package com.example.circletochat.ai.model.recommendation

import android.content.Context
import android.util.Log

/**
 * Engine that recommends the best AI model based on device hardware specifications.
 * Uses a scoring system to evaluate device compatibility with available models.
 */
object ModelRecommendationEngine {

    private const val TAG = "ModelRecommendationEngine"

    /**
     * Metadata for available AI models with their hardware requirements.
     */
    private val models = listOf(
        ModelRequirement(
            name = "gemma-E2b",
            requiredRamMB = 3000,
            requiredStorageMB = 1000,
            requiredCpuCores = 4
        ),
        ModelRequirement(
            name = "gemma-E4b",
            requiredRamMB = 6000,
            requiredStorageMB = 2000,
            requiredCpuCores = 6,
            requiresNPU = true
        )
    )

    /**
     * Recommends the best AI model based on device specifications.
     *
     * Scoring logic:
     * - +30 points if device RAM >= required
     * - +30 points if device Storage >= required
     * - +20 points if CPU cores >= required
     * - +20 points if model does NOT require NPU OR device has NPU
     *
     * Returns the model with the highest total score.
     *
     * @param context Application context used to read device specs
     * @return Name of the recommended model ("gemma-E2b" or "gemma-E4b")
     */
    fun recommendBestModel(context: Context): String {
        val deviceSpecs = DeviceSpecsReader.readDeviceSpecs(context)
        
        Log.d(TAG, "Device Specs: $deviceSpecs")
        // Compute scores for each model
        val scores = models.associateWith { model ->
            val score = calculateScore(deviceSpecs, model)
            Log.d(TAG, "Model: ${model.name}, Score: $score")
            score
        }

        // Decision rule: prefer gemma-E4b only when its score is >= 75, otherwise recommend gemma-E2b
        val e2 = models.firstOrNull { it.name == "gemma-E2b" }
        val e4 = models.firstOrNull { it.name == "gemma-E4b" }

        val recommendedModel = when {
            e4 != null && (scores[e4] ?: 0) >= 75 -> e4.name
            e2 != null -> e2.name
            else -> models.first().name
        }

        Log.d(TAG, "Recommended model (threshold rule): $recommendedModel")
        return recommendedModel
    }

    /**
     * Recommends the best model and returns a detailed explanation string describing
     * why the model was selected (which device requirements are met or missing).
     *
     * @return Pair of recommended model name and explanation String
     */
    fun recommendBestModelWithDetails(context: Context): Pair<String, String> {
        val deviceSpecs = DeviceSpecsReader.readDeviceSpecs(context)

        val perModelDetails = models.map { model ->
            val parts = mutableListOf<String>()
            var score = 0

            if (deviceSpecs.totalRamMB >= model.requiredRamMB) {
                parts.add("RAM: OK (${deviceSpecs.totalRamMB}MB ≥ ${model.requiredRamMB}MB) [+30]")
                score += 30
            } else {
                parts.add("RAM: LOW (${deviceSpecs.totalRamMB}MB < ${model.requiredRamMB}MB) [+0]")
            }

            if (deviceSpecs.availableStorageMB >= model.requiredStorageMB) {
                parts.add("Storage: OK (${deviceSpecs.availableStorageMB}MB ≥ ${model.requiredStorageMB}MB) [+30]")
                score += 30
            } else {
                parts.add("Storage: LOW (${deviceSpecs.availableStorageMB}MB < ${model.requiredStorageMB}MB) [+0]")
            }

            if (deviceSpecs.cpuCores >= model.requiredCpuCores) {
                parts.add("CPU cores: OK (${deviceSpecs.cpuCores} ≥ ${model.requiredCpuCores}) [+20]")
                score += 20
            } else {
                parts.add("CPU cores: LOW (${deviceSpecs.cpuCores} < ${model.requiredCpuCores}) [+0]")
            }

            if (!model.requiresNPU || deviceSpecs.hasNPU) {
                parts.add("NPU: OK (required=${model.requiresNPU}, deviceHas=${deviceSpecs.hasNPU}) [+20]")
                score += 20
            } else {
                parts.add("NPU: MISSING (required=${model.requiresNPU}, deviceHas=${deviceSpecs.hasNPU}) [+0]")
            }

            val explanation = parts.joinToString("; ") + ". Total score: $score"
            Triple(model.name, score, explanation)
        }

        // Apply the same threshold rule to choose the recommended model for details
        val e2Detail = perModelDetails.firstOrNull { it.first == "gemma-E2b" }
        val e4Detail = perModelDetails.firstOrNull { it.first == "gemma-E4b" }

        val recommended = if (e4Detail != null && e4Detail.second >= 75) {
            e4Detail
        } else {
            e2Detail ?: perModelDetails.first()
        }

        return recommended.first to recommended.third
    }

    /**
     * Calculates a score for a model based on device specifications.
     * Higher score means better compatibility.
     *
     * @param deviceSpecs The device specifications
     * @param model The model requirements
     * @return Score between 0 and 100
     */
    private fun calculateScore(deviceSpecs: DeviceSpecs, model: ModelRequirement): Int {
        var score = 0
        
        // +30 points if device RAM >= required
        if (deviceSpecs.totalRamMB >= model.requiredRamMB) {
            score += 30
        }
        
        // +30 points if device Storage >= required
        if (deviceSpecs.availableStorageMB >= model.requiredStorageMB) {
            score += 30
        }
        
        // +20 points if CPU cores >= required
        if (deviceSpecs.cpuCores >= model.requiredCpuCores) {
            score += 20
        }
        
        // +20 points if model does NOT require NPU OR device has NPU
        if (!model.requiresNPU || deviceSpecs.hasNPU) {
            score += 20
        }
        
        return score
    }
}
