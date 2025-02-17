/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.LayoutDirection;
import android.view.WindowMetrics;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Predicate;

/**
 * Split configuration rules for activities that are launched to side in a split. Define when an
 * activity that was launched in a side container from another activity should be shown
 * side-by-side or on top of it, as well as the visual properties of the split. Can be applied to
 * new activities started from the same process automatically by the embedding implementation on
 * the device.
 */
public abstract class SplitRule extends EmbeddingRule {
    @NonNull
    private final Predicate<WindowMetrics> mParentWindowMetricsPredicate;
    private final float mSplitRatio;
    @LayoutDir
    private final int mLayoutDirection;

    @IntDef({
            LayoutDirection.LTR,
            LayoutDirection.RTL,
            LayoutDirection.LOCALE
    })
    @Retention(RetentionPolicy.SOURCE)
    // Not called LayoutDirection to avoid conflict with android.util.LayoutDirection
    @interface LayoutDir {}
    /**
     * Never finish the associated container.
     * @see SplitFinishBehavior
     */
    public static final int FINISH_NEVER = 0;
    /**
     * Always finish the associated container independent of the current presentation mode.
     * @see SplitFinishBehavior
     */
    public static final int FINISH_ALWAYS = 1;
    /**
     * Only finish the associated container when displayed side-by-side/adjacent to the one
     * being finished. Does not finish the associated one when containers are stacked on top of
     * each other.
     * @see SplitFinishBehavior
     */
    public static final int FINISH_ADJACENT = 2;

    /**
     * Determines what happens with the associated container when all activities are finished in
     * one of the containers in a split.
     * <p>
     * For example, given that {@link SplitPairRule#getFinishPrimaryWithSecondary()} is
     * {@link #FINISH_ADJACENT} and secondary container finishes. The primary associated
     * container is finished if it's side-by-side with secondary container. The primary
     * associated container is not finished if it occupies entire task bounds.</p>
     *
     * @see SplitPairRule#getFinishPrimaryWithSecondary()
     * @see SplitPairRule#getFinishSecondaryWithPrimary()
     * @see SplitPlaceholderRule#getFinishPrimaryWithSecondary()
     */
    @IntDef({
            FINISH_NEVER,
            FINISH_ALWAYS,
            FINISH_ADJACENT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SplitFinishBehavior {}

    SplitRule(@NonNull Predicate<WindowMetrics> parentWindowMetricsPredicate, float splitRatio,
            @LayoutDir int layoutDirection) {
        mParentWindowMetricsPredicate = parentWindowMetricsPredicate;
        mSplitRatio = splitRatio;
        mLayoutDirection = layoutDirection;
    }

    /**
     * Verifies if the provided parent bounds allow to show the split containers side by side.
     */
    @SuppressLint("ClassVerificationFailure") // Only called by Extensions implementation on device.
    @RequiresApi(api = Build.VERSION_CODES.N)
    public boolean checkParentMetrics(@NonNull WindowMetrics parentMetrics) {
        return mParentWindowMetricsPredicate.test(parentMetrics);
    }

    public float getSplitRatio() {
        return mSplitRatio;
    }

    @LayoutDir
    public int getLayoutDirection() {
        return mLayoutDirection;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SplitRule)) return false;
        SplitRule that = (SplitRule) o;
        return Float.compare(that.mSplitRatio, mSplitRatio) == 0
                && mParentWindowMetricsPredicate.equals(that.mParentWindowMetricsPredicate)
                && mLayoutDirection == that.mLayoutDirection;
    }

    @Override
    public int hashCode() {
        int result = (int) (mSplitRatio * 17);
        result = 31 * result + mParentWindowMetricsPredicate.hashCode();
        result = 31 * result + mLayoutDirection;
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "SplitRule{"
                + "mSplitRatio=" + mSplitRatio
                + ", mLayoutDirection=" + mLayoutDirection
                + '}';
    }
}
