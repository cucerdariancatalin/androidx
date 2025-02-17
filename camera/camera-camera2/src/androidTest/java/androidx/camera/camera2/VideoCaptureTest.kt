/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.camera2

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Logger
import androidx.camera.core.Preview
import androidx.camera.core.impl.utils.executor.CameraXExecutors
import androidx.camera.core.internal.CameraUseCaseAdapter
import androidx.camera.testing.AudioUtil
import androidx.camera.testing.CameraUtil
import androidx.camera.testing.CameraUtil.PreTestCameraIdList
import androidx.camera.testing.CameraXUtil
import androidx.camera.testing.SurfaceTextureProvider.SurfaceTextureCallback
import androidx.camera.testing.SurfaceTextureProvider.createSurfaceTextureProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.testutils.assertThrows
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

@LargeTest
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
@SdkSuppress(minSdkVersion = 21)
class VideoCaptureTest {
    companion object {
        private const val TAG = "VideoCaptureTest"
    }

    @get:Rule
    val useRecordingResource = CameraUtil.checkVideoRecordingResource()

    @get:Rule
    val useCamera = CameraUtil.grantCameraPermissionAndPreTest(
        PreTestCameraIdList(Camera2Config.defaultConfig())
    )

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var cameraSelector: CameraSelector

    private lateinit var cameraUseCaseAdapter: CameraUseCaseAdapter

    private lateinit var contentResolver: ContentResolver

    @Before
    fun setUp() {
        // TODO(b/168175357): Fix VideoCaptureTest problems on CuttleFish API 29
        assumeFalse(
            "Cuttlefish has MediaCodec dequeueInput/Output buffer fails issue. Unable to test.",
            Build.MODEL.contains("Cuttlefish") && Build.VERSION.SDK_INT == 29
        )

        assumeTrue(CameraUtil.deviceHasCamera())
        assumeTrue(AudioUtil.canStartAudioRecord(MediaRecorder.AudioSource.CAMCORDER))

        cameraSelector = if (CameraUtil.hasCameraWithLensFacing(CameraSelector.LENS_FACING_BACK)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        CameraXUtil.initialize(
            context,
            Camera2Config.defaultConfig()
        ).get()
        cameraUseCaseAdapter = CameraUtil.createCameraUseCaseAdapter(context, cameraSelector)

        contentResolver = context.contentResolver
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            if (this::cameraUseCaseAdapter.isInitialized) {
                cameraUseCaseAdapter.removeUseCases(cameraUseCaseAdapter.useCases)
            }
        }

        CameraXUtil.shutdown().get(10000, TimeUnit.MILLISECONDS)
    }

    @Test
    @SdkSuppress(minSdkVersion = 21, maxSdkVersion = 25)
    fun buildFileOutputOptionsWithFileDescriptor_throwExceptionWhenAPILevelSmallerThan26() {
        val file = File.createTempFile("CameraX", ".tmp").apply {
            deleteOnExit()
        }

        val fileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).fileDescriptor

        assertThrows<IllegalArgumentException> {
            androidx.camera.core.VideoCapture.OutputFileOptions.Builder(fileDescriptor).build()
        }

        file.delete()
    }

    @Test(timeout = 30000)
    @SdkSuppress(minSdkVersion = 26)
    fun startRecordingWithFileDescriptor_whenAPILevelLargerThan26() {
        val file = File.createTempFile("CameraX", ".tmp").apply {
            deleteOnExit()
        }

        // It's needed to have a variable here to hold the parcel file descriptor reference which
        // returned from ParcelFileDescriptor.open(), the returned parcel descriptor reference might
        // be garbage collected unexpectedly. That will caused an "invalid file descriptor" issue.
        val parcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val fileDescriptor = parcelFileDescriptor.fileDescriptor

        val preview = Preview.Builder().build()
        val videoCapture = androidx.camera.core.VideoCapture.Builder().build()

        assumeTrue(
            "This combination (videoCapture, preview) is not supported.",
            cameraUseCaseAdapter.isUseCasesCombinationSupported(videoCapture, preview)
        )

        instrumentation.runOnMainSync {
            preview.setSurfaceProvider(
                CameraXExecutors.mainThreadExecutor(),
                getSurfaceProvider()
            )
            // b/168187087 if there is only VideoCapture , VideoCapture will failed when setting the
            // repeating request with the surface, the workaround is binding one more useCase
            // Preview.
            cameraUseCaseAdapter.addUseCases(listOf(videoCapture, preview))
        }

        val outputFileOptions =
            androidx.camera.core.VideoCapture.OutputFileOptions.Builder(fileDescriptor).build()

        val callback = mock(androidx.camera.core.VideoCapture.OnVideoSavedCallback::class.java)

        // Start recording with FileDescriptor
        videoCapture.startRecording(
            outputFileOptions,
            CameraXExecutors.mainThreadExecutor(),
            callback
        )

        // Recording for seconds
        recordingUntilKeyFrameArrived(videoCapture)

        // Stop recording
        videoCapture.stopRecording()

        verify(callback, timeout(10000)).onVideoSaved(any())
        parcelFileDescriptor.close()
        file.delete()
    }

    @FlakyTest // b/182165222
    @Test(timeout = 30000)
    fun unbind_shouldStopRecording() {
        val file = File.createTempFile("CameraX", ".tmp").apply {
            deleteOnExit()
        }

        val preview = Preview.Builder().build()
        val videoCapture = androidx.camera.core.VideoCapture.Builder().build()

        assumeTrue(
            "This combination (videoCapture, preview) is not supported.",
            cameraUseCaseAdapter.isUseCasesCombinationSupported(videoCapture, preview)
        )
        instrumentation.runOnMainSync {
            preview.setSurfaceProvider(
                CameraXExecutors.mainThreadExecutor(),
                getSurfaceProvider()
            )
            cameraUseCaseAdapter.addUseCases(listOf(videoCapture, preview))
        }

        val outputFileOptions =
            androidx.camera.core.VideoCapture.OutputFileOptions.Builder(file).build()

        val callback = mock(androidx.camera.core.VideoCapture.OnVideoSavedCallback::class.java)

        videoCapture.startRecording(
            outputFileOptions,
            CameraXExecutors.mainThreadExecutor(),
            callback
        )

        recordingUntilKeyFrameArrived(videoCapture)

        instrumentation.runOnMainSync {
            cameraUseCaseAdapter.removeUseCases(listOf(videoCapture, preview))
        }

        verify(callback, timeout(10000)).onVideoSaved(any())
        file.delete()
    }

    @Test(timeout = 30000)
    @SdkSuppress(minSdkVersion = 26)
    fun startRecordingWithUri_whenAPILevelLargerThan26() {
        val preview = Preview.Builder().build()
        val videoCapture = androidx.camera.core.VideoCapture.Builder().build()

        assumeTrue(
            "This combination (videoCapture, preview) is not supported.",
            cameraUseCaseAdapter.isUseCasesCombinationSupported(videoCapture, preview)
        )
        instrumentation.runOnMainSync {
            preview.setSurfaceProvider(
                CameraXExecutors.mainThreadExecutor(),
                getSurfaceProvider()
            )
            cameraUseCaseAdapter.addUseCases(listOf(videoCapture, preview))
        }

        val callback = mock(androidx.camera.core.VideoCapture.OnVideoSavedCallback::class.java)
        videoCapture.startRecording(
            getNewVideoOutputFileOptions(contentResolver),
            CameraXExecutors.mainThreadExecutor(),
            callback
        )
        recordingUntilKeyFrameArrived(videoCapture)

        videoCapture.stopRecording()

        // Assert: Wait for the signal that the image has been saved.
        val outputFileResultsArgumentCaptor =
            ArgumentCaptor.forClass(
                androidx.camera.core.VideoCapture.OutputFileResults::class.java
            )
        verify(callback, timeout(10000)).onVideoSaved(outputFileResultsArgumentCaptor.capture())

        // get file path to remove it
        val saveLocationUri =
            outputFileResultsArgumentCaptor.value.savedUri
        assertThat(saveLocationUri).isNotNull()

        // Remove temp test file
        contentResolver.delete(saveLocationUri!!, null, null)
    }

    @Test(timeout = 30000)
    fun videoCapture_saveResultToFile() {
        val file = File.createTempFile("CameraX", ".tmp").apply {
            deleteOnExit()
        }

        val preview = Preview.Builder().build()
        val videoCapture = androidx.camera.core.VideoCapture.Builder().build()

        assumeTrue(
            "This combination (videoCapture, preview) is not supported.",
            cameraUseCaseAdapter.isUseCasesCombinationSupported(videoCapture, preview)
        )
        instrumentation.runOnMainSync {
            preview.setSurfaceProvider(
                CameraXExecutors.mainThreadExecutor(),
                getSurfaceProvider()
            )
            cameraUseCaseAdapter.addUseCases(listOf(videoCapture, preview))
        }

        val callback = mock(androidx.camera.core.VideoCapture.OnVideoSavedCallback::class.java)
        videoCapture.startRecording(
            androidx.camera.core.VideoCapture.OutputFileOptions.Builder(file).build(),
            CameraXExecutors.mainThreadExecutor(),
            callback
        )

        recordingUntilKeyFrameArrived(videoCapture)
        videoCapture.stopRecording()

        // Wait for the signal that the video has been saved.
        verify(callback, timeout(10000)).onVideoSaved(any())
        file.delete()
    }

    /** Return a VideoOutputFileOption which is used to save a video.  */
    private fun getNewVideoOutputFileOptions(
        resolver: ContentResolver
    ): androidx.camera.core.VideoCapture.OutputFileOptions {
        val videoFileName = "video_" + System.currentTimeMillis()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.TITLE, videoFileName)
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName)
        }

        return androidx.camera.core.VideoCapture.OutputFileOptions.Builder(
            resolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()
    }

    private fun getSurfaceProvider(): Preview.SurfaceProvider {
        return createSurfaceTextureProvider(object : SurfaceTextureCallback {
            override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture, resolution: Size) {
                // No-op
            }

            override fun onSafeToRelease(surfaceTexture: SurfaceTexture) {
                surfaceTexture.release()
            }
        })
    }

    private fun recordingUntilKeyFrameArrived(videoCapture: androidx.camera.core.VideoCapture) {
        Logger.i(TAG, "recordingUntilKeyFrameArrived begins: " + System.nanoTime() / 1000)
        while (true) {
            if (videoCapture.mIsFirstVideoKeyFrameWrite.get() && videoCapture
                .mIsFirstAudioSampleWrite.get()
            ) {
                Logger.i(
                    TAG,
                    "Video Key Frame and audio frame Arrived: " + System.nanoTime() / 1000
                )
                break
            }
            Thread.sleep(100)
        }
        Logger.i(TAG, "recordingUntilKeyFrameArrived ends: " + System.nanoTime() / 1000)
    }
}
