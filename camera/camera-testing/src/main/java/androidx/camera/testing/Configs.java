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

package androidx.camera.testing;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.camera.core.UseCase;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.core.impl.UseCaseConfig;
import androidx.camera.core.impl.UseCaseConfigFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility functions related to operating on androidx.camera.core.impl.Config instances.
 */
@RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java
public final class Configs {
    /** Return a map that associates UseCases to UseCaseConfigs with default settings. */
    @NonNull
    public static Map<UseCase, UseCaseConfig<?>> useCaseConfigMapWithDefaultSettingsFromUseCaseList(
            @NonNull CameraInfoInternal cameraInfo, @NonNull List<UseCase> useCases,
            @NonNull UseCaseConfigFactory useCaseConfigFactory) {
        Map<UseCase, UseCaseConfig<?>> useCaseToConfigMap = new HashMap<>();

        for (UseCase useCase : useCases) {
            // Combine with default configuration.
            UseCaseConfig<?> combinedUseCaseConfig = useCase.mergeConfigs(cameraInfo, null,
                    useCase.getDefaultConfig(true, useCaseConfigFactory));
            useCaseToConfigMap.put(useCase, combinedUseCaseConfig);
        }

        return useCaseToConfigMap;
    }

    private Configs() {
    }
}
