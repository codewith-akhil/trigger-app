package com.example.service

import com.example.model.UploadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface UploadService {
    val activeUploads: StateFlow<List<UploadTask>>
    fun enqueueUpload(task: UploadTask)
    fun cancelUpload(taskId: String)
    fun retryUpload(taskId: String)
    fun getUploadTaskFlow(taskId: String): Flow<UploadTask?>
}
