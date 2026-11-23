package de.zapko.spur.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.zapko.spur.repo
import java.io.IOException

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.repo
        return try {
            repo.syncNow()
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 20) Result.retry() else Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
