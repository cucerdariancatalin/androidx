/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.graphics.lowlatency

import android.graphics.Color
import android.hardware.HardwareBuffer
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.opengl.egl.EGLManager
import androidx.graphics.opengl.egl.deviceSupportsNativeAndroidFence
import androidx.graphics.surface.SurfaceControlCompat
import androidx.graphics.surface.SurfaceControlUtils
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class GLFrontBufferedRendererTest {

    companion object {
        val TAG = "GLFrontBufferedRenderer"
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun testFrontBufferedLayerRender() {
        if (!deviceSupportsNativeAndroidFence()) {
            // If the Android device does not support the corresponding extensions to create
            // a file descriptor from an EGLSync object then skip the test
            Log.w(TAG, "Skipping testFrontBufferedLayerRender, no native android fence support")
            return
        }
        val renderLatch = CountDownLatch(1)
        val callbacks = object : GLFrontBufferedRenderer.Callback<Any> {
            override fun onDrawFrontBufferedLayer(eglManager: EGLManager, param: Any) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            override fun onDrawDoubleBufferedLayer(
                eglManager: EGLManager,
                params: Collection<Any>
            ) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            override fun onFrontBufferedLayerRenderComplete(
                frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                transaction: SurfaceControlCompat.Transaction
            ) {
                transaction.addTransactionCommittedListener(
                    Executors.newSingleThreadExecutor(),
                    object : SurfaceControlCompat.TransactionCommittedListener {
                        override fun onTransactionCommitted() {
                            renderLatch.countDown()
                        }
                    }
                )
            }
        }
        var renderer: GLFrontBufferedRenderer<Any>? = null
        var surfaceView: SurfaceView? = null
        try {
            val scenario = ActivityScenario.launch(FrontBufferedRendererTestActivity::class.java)
                .moveToState(Lifecycle.State.CREATED)
                .onActivity {
                    surfaceView = it.getSurfaceView()
                    renderer = GLFrontBufferedRenderer(surfaceView!!, callbacks)
                }

            scenario.moveToState(Lifecycle.State.RESUMED).onActivity {
                renderer?.renderFrontBufferedLayer(Any())
            }
            assertTrue(renderLatch.await(3000, TimeUnit.MILLISECONDS))

            val coords = IntArray(2)
            val width: Int
            val height: Int
            with(surfaceView!!) {
                getLocationOnScreen(coords)
                width = this.width
                height = this.height
            }

            SurfaceControlUtils.validateOutput { bitmap ->
                Color.RED ==
                    bitmap.getPixel(coords[0] + width / 2, coords[1] + height / 2)
            }
        } finally {
            renderer.blockingRelease()
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun testDoubleBufferedLayerRender() {
        if (!deviceSupportsNativeAndroidFence()) {
            // If the Android device does not support the corresponding extensions to create
            // a file descriptor from an EGLSync object then skip the test
            Log.w(TAG, "Skipping testDoubleBufferedLayerRender, no native android fence support")
            return
        }

        val renderLatch = CountDownLatch(1)
        val callbacks = object : GLFrontBufferedRenderer.Callback<Any> {
            override fun onDrawFrontBufferedLayer(eglManager: EGLManager, param: Any) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            override fun onDrawDoubleBufferedLayer(
                eglManager: EGLManager,
                params: Collection<Any>
            ) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            override fun onDoubleBufferedLayerRenderComplete(
                frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                transaction: SurfaceControlCompat.Transaction
            ) {
                transaction.addTransactionCommittedListener(
                    Executors.newSingleThreadExecutor(),
                    object : SurfaceControlCompat.TransactionCommittedListener {
                        override fun onTransactionCommitted() {
                            renderLatch.countDown()
                        }
                    })
            }
        }
        var renderer: GLFrontBufferedRenderer<Any>? = null
        var surfaceView: SurfaceView? = null
        try {
            val scenario = ActivityScenario.launch(FrontBufferedRendererTestActivity::class.java)
                .moveToState(Lifecycle.State.CREATED)
                .onActivity {
                    surfaceView = it.getSurfaceView()
                    renderer = GLFrontBufferedRenderer(surfaceView!!, callbacks)
                }

            scenario.moveToState(Lifecycle.State.RESUMED).onActivity {
                renderer?.renderFrontBufferedLayer(Any())
                renderer?.commit()
            }
            assertTrue(renderLatch.await(3000, TimeUnit.MILLISECONDS))

            val coords = IntArray(2)
            val width: Int
            val height: Int
            with(surfaceView!!) {
                getLocationOnScreen(coords)
                width = this.width
                height = this.height
            }

            SurfaceControlUtils.validateOutput { bitmap ->
                (Math.abs(
                    Color.red(Color.BLUE) - Color.red(
                        bitmap.getPixel(
                            coords[0] + width / 2,
                            coords[1] + height / 2
                        )
                    )
                ) < 2) &&
                    (Math.abs(
                        Color.green(Color.BLUE) - Color.green(
                            bitmap.getPixel(
                                coords[0] + width / 2,
                                coords[1] + height / 2
                            )
                        )
                    ) < 2) &&
                    (Math.abs(
                        Color.blue(Color.BLUE) - Color.blue(
                            bitmap.getPixel(
                                coords[0] + width / 2,
                                coords[1] + height / 2
                            )
                        )
                    ) < 2)
            }
        } finally {
            renderer.blockingRelease()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun testUsageFlagContainsFrontBufferUsage() {
        val usageFlags = GLFrontBufferedRenderer.obtainHardwareBufferUsageFlags()
        if (UsageFlagsVerificationHelper.isSupported(HardwareBuffer.USAGE_FRONT_BUFFER)) {
            assertNotEquals(0, usageFlags and HardwareBuffer.USAGE_FRONT_BUFFER)
        } else {
            assertEquals(0, usageFlags and HardwareBuffer.USAGE_FRONT_BUFFER)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun testUsageFlagContainsComposerOverlay() {
        val usageFlags = GLFrontBufferedRenderer.obtainHardwareBufferUsageFlags()
        if (UsageFlagsVerificationHelper.isSupported(HardwareBuffer.USAGE_COMPOSER_OVERLAY)) {
            assertNotEquals(
                0,
                usageFlags and HardwareBuffer.USAGE_COMPOSER_OVERLAY
            )
        } else {
            assertEquals(0, usageFlags and HardwareBuffer.USAGE_COMPOSER_OVERLAY)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun testBaseFlags() {
        assertNotEquals(
            0, GLFrontBufferedRenderer.BaseFlags and
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        assertNotEquals(
            0, GLFrontBufferedRenderer.BaseFlags and
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun testRenderFrontBufferSeveralTimes() {
        if (!deviceSupportsNativeAndroidFence()) {
            // If the Android device does not support the corresponding extensions to create
            // a file descriptor from an EGLSync object then skip the test
            Log.w(TAG, "Skipping testDoubleBufferedLayerRender, no native android fence support")
            return
        }

        val callbacks = object : GLFrontBufferedRenderer.Callback<Any> {

            var red = 1f
            var blue = 0f

            override fun onDrawFrontBufferedLayer(eglManager: EGLManager, param: Any) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(red, 0.0f, blue, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val tmp = red
                red = blue
                blue = tmp
            }

            override fun onDrawDoubleBufferedLayer(
                eglManager: EGLManager,
                params: Collection<Any>
            ) {
                GLES20.glViewport(
                    0,
                    0,
                    FrontBufferedRendererTestActivity.WIDTH,
                    FrontBufferedRendererTestActivity.HEIGHT
                )
                GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }
        var renderer: GLFrontBufferedRenderer<Any>? = null
        try {
            val scenario = ActivityScenario.launch(FrontBufferedRendererTestActivity::class.java)
                .moveToState(Lifecycle.State.CREATED)
                .onActivity {
                    renderer = GLFrontBufferedRenderer(it.getSurfaceView(), callbacks)
                }

            scenario.moveToState(Lifecycle.State.RESUMED).onActivity {
                val param = Any()
                repeat(4000) {
                    renderer?.renderFrontBufferedLayer(param)
                }
            }
        } finally {
            renderer.blockingRelease(10000)
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test
    fun testFrontBufferedLayerPersistence() {
        if (!deviceSupportsNativeAndroidFence()) {
            // If the Android device does not support the corresponding extensions to create
            // a file descriptor from an EGLSync object then skip the test
            Log.w(TAG, "Skipping testDoubleBufferedLayerRender, no native android fence support")
            return
        }
        val mMVPMatrix = FloatArray(16)
        val mLines = FloatArray(4)
        val mLineRenderer = LineRenderer()
        val screenWidth = FrontBufferedRendererTestActivity.WIDTH
        val screenHeight = FrontBufferedRendererTestActivity.HEIGHT

        val renderLatch = CountDownLatch(1)
        val firstDrawLatch = CountDownLatch(1)
        val callbacks = object : GLFrontBufferedRenderer.Callback<Any> {
            override fun onDrawFrontBufferedLayer(eglManager: EGLManager, param: Any) {
                mLineRenderer.initialize()
                GLES20.glViewport(0, 0, screenWidth, screenHeight)

                Matrix.orthoM(
                    mMVPMatrix,
                    0,
                    0f,
                    screenWidth.toFloat(),
                    0f,
                    screenHeight.toFloat(),
                    -1f,
                    1f
                )

                mLines[0] = screenWidth / 4 + (param as Float)
                mLines[1] = 0f
                mLines[2] = screenWidth / 4 + param
                mLines[3] = 100f

                mLineRenderer.drawLines(mMVPMatrix, mLines)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                firstDrawLatch.countDown()
                mLineRenderer.release()
            }

            override fun onDrawDoubleBufferedLayer(
                eglManager: EGLManager,
                params: Collection<Any>
            ) {
                mLineRenderer.initialize()
                GLES20.glViewport(0, 0, screenWidth, screenHeight)
                Matrix.orthoM(
                    mMVPMatrix,
                    0,
                    0f,
                    screenWidth.toFloat(),
                    0f,
                    screenHeight.toFloat(),
                    -1f,
                    1f
                )

                for (param in params) {
                    mLines[0] = screenWidth / 4 + (param as Float)
                    mLines[1] = 0f
                    mLines[2] = screenWidth / 4 + param
                    mLines[3] = 100f

                    mLineRenderer.drawLines(mMVPMatrix, mLines)
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                }
                mLineRenderer.release()
            }

            override fun onDoubleBufferedLayerRenderComplete(
                frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                transaction: SurfaceControlCompat.Transaction
            ) {
                transaction.addTransactionCommittedListener(
                    Executors.newSingleThreadExecutor(),
                    object : SurfaceControlCompat.TransactionCommittedListener {
                        override fun onTransactionCommitted() {
                            renderLatch.countDown()
                        }
                    })
            }
        }
        var renderer: GLFrontBufferedRenderer<Any>? = null
        var surfaceView: SurfaceView? = null
        try {
            val scenario = ActivityScenario.launch(FrontBufferedRendererTestActivity::class.java)
                .moveToState(Lifecycle.State.CREATED)
                .onActivity {
                    surfaceView = it.getSurfaceView()
                    renderer = GLFrontBufferedRenderer(surfaceView!!, callbacks)
                }

            scenario.moveToState(Lifecycle.State.RESUMED).onActivity {
                renderer?.renderFrontBufferedLayer(0f)
                renderer?.commit()
                renderer?.renderFrontBufferedLayer(screenWidth / 2f)
                renderer?.commit()
            }

            assertTrue(renderLatch.await(3000, TimeUnit.MILLISECONDS))

            val coords = IntArray(2)
            val width: Int
            val height: Int
            with(surfaceView!!) {
                getLocationOnScreen(coords)
                width = this.width
                height = this.height
            }

            SurfaceControlUtils.validateOutput { bitmap ->
                (bitmap.getPixel(
                    coords[0] + width / 4, coords[1] + height / 2
                ) == Color.RED) &&
                    (bitmap.getPixel(
                        coords[0] + 3 * width / 4,
                        coords[1] + height / 2
                    ) == Color.RED)
            }
        } finally {
            renderer.blockingRelease()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun GLFrontBufferedRenderer<*>?.blockingRelease(timeoutMillis: Long = 3000) {
        if (this != null) {
            val destroyLatch = CountDownLatch(1)
            release(false) {
                destroyLatch.countDown()
            }
            assertTrue(destroyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS))
        } else {
            fail("GLFrontBufferedRenderer is not initialized")
        }
    }
}