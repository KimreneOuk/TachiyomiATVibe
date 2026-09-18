package eu.kanade.translation.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class BenchmarkResultSerializer {

    fun write(result: PaddleBenchmarkResult): OutputFiles {
        result.config.outputDirectory.mkdirs()
        val jsonFile = File(result.config.outputDirectory, "paddle_benchmark.json")
        val csvFile = File(result.config.outputDirectory, "paddle_samples.csv")
        val reportFile = File(result.config.outputDirectory, "paddle_benchmark_report.md")
        jsonFile.writeText(toJson(result).toString(2))
        csvFile.writeText(toCsv(result))
        reportFile.writeText(toReport(result))
        return OutputFiles(jsonFile, csvFile, reportFile)
    }

    private fun toJson(result: PaddleBenchmarkResult): JSONObject = JSONObject().apply {
        put("schemaVersion", result.schemaVersion)
        put("benchmarkName", result.benchmarkName)
        put("commit", result.commit)
        put("startedAtEpochMs", result.startedAtEpochMs)
        put("durationMs", result.durationMs)
        put("config", configJson(result.config))
        put("device", deviceJson(result.device))
        put("models", JSONObject().put("files", stringMapJson(result.models.files)))
        put("provider", providerJson(result.provider))
        put("sessionCreationMs", result.sessionCreationMs)
        put("modelPreparationMs", result.modelPreparationMs)
        put("stages", JSONArray().apply { result.stages.forEach { put(stageJson(it)) } })
        put("samples", JSONArray().apply { result.samples.forEach { put(sampleJson(it)) } })
        put("buckets", JSONArray().apply { result.buckets.forEach { put(bucketJson(it)) } })
        put("pss", pssJson(result.pss))
        put("pagesAvailable", result.pagesAvailable)
        put("pagesProcessed", result.pagesProcessed)
        put("downloadedCorpusStatus", result.downloadedCorpusStatus)
        put("externalCorpusStatus", result.externalCorpusStatus)
        put("parity", result.parity?.let(::parityJson) ?: JSONObject.NULL)
    }

    private fun configJson(config: PaddleBenchmarkConfig): JSONObject = JSONObject().apply {
        put("corpusRoot", config.corpusRoot ?: JSONObject.NULL)
        put("pageLimit", config.pageLimit)
        put("sampleLimit", config.sampleLimit)
        put("includeFixtures", config.includeFixtures)
        put("includeDownloadedCorpus", config.includeDownloadedCorpus)
        put("includeExternalCorpus", config.includeExternalCorpus)
        put("outputDirectory", config.outputDirectory.absolutePath)
        put("parityMode", config.parityMode)
    }

    private fun deviceJson(device: DeviceMetadata): JSONObject = JSONObject().apply {
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("device", device.device)
        put("socManufacturer", device.socManufacturer)
        put("socModel", device.socModel)
        put("androidApi", device.androidApi)
        put("release", device.release)
        put("primaryAbi", device.primaryAbi)
        put("supportedAbis", JSONArray(device.supportedAbis))
        put("totalRamBytes", device.totalRamBytes)
        put("memoryClassMb", device.memoryClassMb)
        put("largeMemoryClassMb", device.largeMemoryClassMb)
        put("packageName", device.packageName)
        put("packageVersionName", device.packageVersionName)
        put("packageVersionCode", device.packageVersionCode)
        put("thermalStatusAtStart", device.thermalStatusAtStart)
        put("thermalStatusAtEnd", device.thermalStatusAtEnd)
        put("thermalStatusMax", device.thermalStatusMax)
    }

    private fun providerJson(provider: ProviderMetadata): JSONObject = JSONObject().apply {
        put("actualRegisteredProvider", provider.actualRegisteredProvider)
        put("requestedRoute", provider.requestedRoute)
        put("runtimeVersion", provider.runtimeVersion)
        put("availableProviders", JSONArray(provider.availableProviders))
        put("providerLibraryDigests", stringMapJson(provider.providerLibraryDigests))
        put("measuredBatchSize", provider.measuredBatchSize)
        put("batched", provider.batched)
    }

    private fun stageJson(stage: StageTiming): JSONObject = JSONObject().apply {
        put("stage", stage.stage)
        put("durationMs", stage.durationMs)
    }

    private fun sampleJson(sample: SampleTiming): JSONObject = JSONObject().apply {
        put("sampleId", sample.sampleId)
        put("pageId", sample.pageId)
        put("widthBucket", sample.widthBucket)
        put("inputShape", JSONArray(sample.inputShape))
        put("measuredBatchSize", sample.measuredBatchSize)
        put("batched", sample.batched)
        put("coldSession", sample.coldSession)
        put("coldInferenceMs", sample.coldInferenceMs)
        put("warmRun1Ms", sample.warmRun1Ms)
        put("warmRun2Ms", sample.warmRun2Ms)
        put("confidence", sample.confidence)
        put("text", sample.text)
    }

    private fun bucketJson(bucket: BucketSummary): JSONObject = JSONObject().apply {
        put("widthBucket", bucket.widthBucket)
        put("sampleCount", bucket.sampleCount)
        put("measuredBatchSizes", JSONArray(bucket.measuredBatchSizes))
        put("batchedValues", JSONArray(bucket.batchedValues))
        put("coldInferenceP50Ms", bucket.coldInferenceP50Ms ?: JSONObject.NULL)
        put("warmRun1P50Ms", bucket.warmRun1P50Ms ?: JSONObject.NULL)
        put("warmRun1P95Ms", bucket.warmRun1P95Ms ?: JSONObject.NULL)
        put("warmRun2P50Ms", bucket.warmRun2P50Ms ?: JSONObject.NULL)
        put("warmRun2P95Ms", bucket.warmRun2P95Ms ?: JSONObject.NULL)
        put("minMs", bucket.minMs ?: JSONObject.NULL)
        put("maxMs", bucket.maxMs ?: JSONObject.NULL)
    }

    private fun parityJson(parity: PaddleB1ParityResult): JSONObject = JSONObject().apply {
        put("mode", parity.mode)
        put("passed", parity.passed)
        put("detectorConfiguration", parity.detectorConfiguration)
        put("comparedSamples", parity.comparedSamples)
        put("samples", JSONArray().apply { parity.samples.forEach { put(paritySampleJson(it)) } })
    }

    private fun paritySampleJson(sample: PaddleB1ParitySample): JSONObject = JSONObject().apply {
        put("sampleId", sample.sampleId)
        put("pageId", sample.pageId)
        put("regionId", sample.regionId)
        put("leafId", sample.leafId)
        put("stage", sample.stage)
        put("widthBucket", sample.widthBucket)
        put("inputShape", JSONArray(sample.inputShape))
        put("referenceText", sample.referenceText)
        put("batchText", sample.batchText)
        put("referenceConfidence", sample.referenceConfidence)
        put("batchConfidence", sample.batchConfidence)
        put("referenceConfidenceBits", sample.referenceConfidenceBits)
        put("batchConfidenceBits", sample.batchConfidenceBits)
        put("exactText", sample.exactText)
        put("exactConfidence", sample.exactConfidence)
    }

    private fun pssJson(pss: PssSummary): JSONObject = JSONObject().apply {
        put("intervalMs", pss.intervalMs)
        put("sampleCount", pss.sampleCount)
        put("peakPssKb", pss.peakPssKb)
        put("peakJavaHeapBytes", pss.peakJavaHeapBytes)
        put("firstPssKb", pss.firstPssKb ?: JSONObject.NULL)
        put("lastPssKb", pss.lastPssKb ?: JSONObject.NULL)
        put("thermalStatusAtPeak", pss.thermalStatusAtPeak)
        put("samples", JSONArray().apply {
            pss.samples.forEach { sample ->
                put(
                    JSONObject().apply {
                        put("elapsedMs", sample.elapsedMs)
                        put("pssKb", sample.pssKb)
                        put("javaHeapBytes", sample.javaHeapBytes)
                        put("thermalStatus", sample.thermalStatus)
                    },
                )
            }
        })
    }

    private fun stringMapJson(values: Map<String, String>): JSONObject = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun toCsv(result: PaddleBenchmarkResult): String = buildString {
        appendLine("sample_id,page_id,width_bucket,input_shape,measured_batch_size,batched,cold_session,cold_inference_ms,warm_run_1_ms,warm_run_2_ms,confidence,text")
        result.samples.forEach { sample ->
            appendLine(
                listOf(
                    sample.sampleId,
                    sample.pageId,
                    sample.widthBucket.toString(),
                    sample.inputShape.joinToString("x"),
                    sample.measuredBatchSize.toString(),
                    sample.batched.toString(),
                    sample.coldSession.toString(),
                    format(sample.coldInferenceMs),
                    format(sample.warmRun1Ms),
                    format(sample.warmRun2Ms),
                    format(sample.confidence.toDouble()),
                    sample.text,
                ).joinToString(",", transform = ::csvCell),
            )
        }
    }

    private fun toReport(result: PaddleBenchmarkResult): String = buildString {
        appendLine(
            if (result.parity == null) {
                "# Paddle OCR v6 B1 Android baseline"
            } else {
                "# Paddle OCR v6 B1 Android parity"
            },
        )
        appendLine()
        appendLine("- Evidence: **CONFIRMED** on `${result.device.model}` / `${result.device.socModel}` (API ${result.device.androidApi}, `${result.device.primaryAbi}`).")
        appendLine("- Provider: **CONFIRMED** actual registered provider `${result.provider.actualRegisteredProvider}`; runtime `${result.provider.runtimeVersion}`; requested route `${result.provider.requestedRoute}`.")
        appendLine("- Batch: **CONFIRMED** measured batch size ${result.provider.measuredBatchSize}; `batched=${result.provider.batched}` is recorded from this B1 runner, not inferred from the engine name.")
        appendLine("- Memory: **CONFIRMED** PSS sampled every ${result.pss.intervalMs} ms (${result.pss.sampleCount} samples); peak ${result.pss.peakPssKb} KiB; peak Java heap ${result.pss.peakJavaHeapBytes} bytes; thermal at peak `${result.pss.thermalStatusAtPeak}`.")
        appendLine("- Session creation: `${format(result.sessionCreationMs)} ms`; model preparation: `${format(result.modelPreparationMs)} ms`; total: `${format(result.durationMs)} ms`.")
        appendLine("- Corpus: ${result.pagesProcessed}/${result.pagesAvailable} pages processed; downloaded corpus `${result.downloadedCorpusStatus}`; external corpus `${result.externalCorpusStatus}`.")
        result.parity?.let { parity ->
            appendLine("- B1 parity: **${if (parity.passed) "CONFIRMED" else "FAILED"}** exact text and confidence-bit equality for ${parity.comparedSamples} samples.")
            appendLine("- Detector matrix: `${parity.detectorConfiguration}`.")
            appendLine()
            appendLine("## B1 parity samples")
            appendLine()
            appendLine("| Page | Region | Leaf | Bucket | Text exact | Confidence exact | Reference confidence bits | B1 confidence bits |")
            appendLine("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |")
            parity.samples.forEach { sample ->
                appendLine(
                    "| ${sample.pageId} | ${sample.regionId} | ${sample.leafId} | ${sample.widthBucket} | " +
                        "${sample.exactText} | ${sample.exactConfidence} | ${sample.referenceConfidenceBits} | ${sample.batchConfidenceBits} |",
                )
            }
        }
        appendLine()
        appendLine("## Width buckets")
        appendLine()
        appendLine("| Bucket | Samples | Cold p50 (ms) | Warm 1 p50/p95 (ms) | Warm 2 p50/p95 (ms) | Range (ms) |")
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
        result.buckets.forEach { bucket ->
            appendLine(
                "| ${bucket.widthBucket} | ${bucket.sampleCount} | ${formatNullable(bucket.coldInferenceP50Ms)} | " +
                    "${formatNullable(bucket.warmRun1P50Ms)}/${formatNullable(bucket.warmRun1P95Ms)} | " +
                    "${formatNullable(bucket.warmRun2P50Ms)}/${formatNullable(bucket.warmRun2P95Ms)} | " +
                    "${formatNullable(bucket.minMs)}–${formatNullable(bucket.maxMs)} |",
            )
        }
        appendLine()
        appendLine("`UNTESTED` means the external corpus was not supplied or a bucket had no samples; no result is substituted for missing Android evidence.")
    }

    private fun csvCell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun formatNullable(value: Double?): String = value?.let(::format) ?: "UNTESTED"

    data class OutputFiles(
        val json: File,
        val csv: File,
        val report: File,
    )
}
