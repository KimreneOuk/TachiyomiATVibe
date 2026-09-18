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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = configFromIntent()
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
                executor.shutdown()
            }
        }
        finish()
    }

    override fun onDestroy() {
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
    }
}
