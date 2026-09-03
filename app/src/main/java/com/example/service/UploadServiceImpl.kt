package com.example.service

import com.example.model.UploadTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

class UploadServiceImpl(
    private val scope: CoroutineScope,
    private val onUploadComplete: suspend (UploadTask) -> Unit = {}
) : UploadService {

    private val _activeUploads = MutableStateFlow<List<UploadTask>>(emptyList())
    override val activeUploads = _activeUploads.asStateFlow()

    private val jobMap = ConcurrentHashMap<String, Job>()
    private val tasksMap = ConcurrentHashMap<String, UploadTask>()

    override fun enqueueUpload(task: UploadTask) {
        tasksMap[task.id] = task
        refreshState()

        val job = scope.launch {
            try {
                val total = task.totalBytes
                val chunkSize = (total / 10).coerceAtLeast(1024 * 100) // 10 steps
                var uploaded = 0L
                val startTime = System.currentTimeMillis()

                while (uploaded < total && isActive) {
                    delay(300)
                    uploaded = (uploaded + chunkSize).coerceAtMost(total)
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                    val speed = uploaded / elapsedSec
                    val remainingBytes = total - uploaded
                    val remainingSec = (remainingBytes / speed.coerceAtLeast(100.0)).toInt()

                    val updated = task.copy(
                        uploadedBytes = uploaded,
                        speedBytesPerSec = speed,
                        remainingSeconds = remainingSec,
                        isCompleted = uploaded >= total
                    )
                    tasksMap[task.id] = updated
                    refreshState()
                }

                if (isActive) {
                    val completedTask = task.copy(
                        uploadedBytes = total,
                        isCompleted = true,
                        remainingSeconds = 0
                    )
                    tasksMap[task.id] = completedTask
                    refreshState()
                    onUploadComplete(completedTask)
                    delay(1000)
                    tasksMap.remove(task.id)
                    refreshState()
                }
            } catch (e: CancellationException) {
                tasksMap.remove(task.id)
                refreshState()
            } catch (e: Exception) {
                val failedTask = task.copy(
                    isFailed = true,
                    errorMessage = e.localizedMessage ?: "Upload failed"
                )
                tasksMap[task.id] = failedTask
                refreshState()
            }
        }
        jobMap[task.id] = job
    }

    override fun cancelUpload(taskId: String) {
        jobMap[taskId]?.cancel()
        jobMap.remove(taskId)
        tasksMap.remove(taskId)
        refreshState()
    }

    override fun retryUpload(taskId: String) {
        val task = tasksMap[taskId] ?: return
        val fresh = task.copy(
            uploadedBytes = 0L,
            isFailed = false,
            isCompleted = false,
            errorMessage = null
        )
        enqueueUpload(fresh)
    }

    override fun getUploadTaskFlow(taskId: String): Flow<UploadTask?> {
        return activeUploads.map { list -> list.find { it.id == taskId } }
    }

    private fun refreshState() {
        _activeUploads.value = tasksMap.values.toList()
    }
}
