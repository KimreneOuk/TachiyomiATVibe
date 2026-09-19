package eu.kanade.translation.benchmark

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class PaddleBenchmarkActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "paddle-benchmark-runner")
    }
    private var runLease: PaddleBenchmarkRunLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = configFromIntent()
        runLease = PaddleBenchmarkRunLease(this).also { it.start() }
        executor.execute {
            try {
                val result = PaddleBenchmarkRunner(applicationContext).run(config)
                val outputs = BenchmarkResultSerializer().write(result)
                File(config.outputDirectory, "complete.marker").writeText("ok\n")
                Log.i(TAG, "Paddle benchmark complete: json=${outputs.json} csv=${outputs.csv} report=${outputs.report}")
            } catch (error: Throwable) {
                config.outputDirectory.mkdirs()
                File(config.outputDirectory, "paddle_benchmark_failure.txt").writeText(
                    "${error::class.java.name}: ${error.message}\n" +
                        error.stackTraceToString(),
                )
                Log.e(TAG, "Paddle benchmark failed; see ${config.outputDirectory}", error)
            } finally {
                runLease?.close()
                runLease = null
                executor.shutdown()
                runOnUiThread { finish() }
            }
        }
    }

    override fun onDestroy() {
        runLease?.close()
        runLease = null
        super.onDestroy()
    }

    private fun configFromIntent(): PaddleBenchmarkConfig {
        val externalFiles = getExternalFilesDir(null) ?: filesDir
        val defaultCorpus = File(externalFiles, "paddle-corpus")
        val defaultOutput = File(externalFiles, "paddle-benchmark/${System.currentTimeMillis()}")
        return PaddleBenchmarkConfig(
            corpusRoot = intent.getStringExtra(EXTRA_CORPUS_ROOT) ?: defaultCorpus.absolutePath,
            pageLimit = intent.getIntExtra(EXTRA_PAGE_LIMIT, 0).coerceAtLeast(0),
            sampleLimit = intent.getIntExtra(EXTRA_SAMPLE_LIMIT, 0).coerceAtLeast(0),
            includeFixtures = intent.getBooleanExtra(EXTRA_INCLUDE_FIXTURES, true),
            includeDownloadedCorpus = intent.getBooleanExtra(EXTRA_INCLUDE_DOWNLOADED, true),
            includeExternalCorpus = intent.getBooleanExtra(EXTRA_INCLUDE_EXTERNAL, false),
            outputDirectory = File(intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: defaultOutput.absolutePath),
            parityMode = intent.getBooleanExtra(EXTRA_PARITY_MODE, false),
            matrixMode = intent.getBooleanExtra(EXTRA_MATRIX_MODE, false),
            matrixIterations = intent.getIntExtra(EXTRA_MATRIX_ITERATIONS, 3).coerceIn(1, 20),
        )
    }

    private companion object {
        const val TAG = "PaddleBenchmark"
        const val EXTRA_CORPUS_ROOT = "corpusRoot"
        const val EXTRA_PAGE_LIMIT = "pageLimit"
        const val EXTRA_SAMPLE_LIMIT = "sampleLimit"
        const val EXTRA_INCLUDE_FIXTURES = "includeFixtures"
        const val EXTRA_INCLUDE_DOWNLOADED = "includeDownloadedCorpus"
        const val EXTRA_INCLUDE_EXTERNAL = "includeExternalCorpus"
        const val EXTRA_OUTPUT_DIR = "outputDir"
        const val EXTRA_PARITY_MODE = "parityMode"
        const val EXTRA_MATRIX_MODE = "matrixMode"
        const val EXTRA_MATRIX_ITERATIONS = "matrixIterations"
    }
}
