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

package com.example.androidx.webkit;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.ProcessGlobalConfig;
import androidx.webkit.WebViewFeature;


/**
 * An {@link Activity} which makes use of {@link androidx.webkit.ProcessGlobalConfig} to set up
 * process global configuration prior to loading WebView.
 */
public class ProcessGlobalConfigActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (WebViewFeature.isStartupFeatureSupported(WebViewFeature.SET_DATA_DIRECTORY_SUFFIX,
                this)) {
            ProcessGlobalConfig.createInstance()
                    .setDataDirectorySuffix("per_process_webview_data_0", this)
                    .apply();
        }
        WebView wv = new WebView(this);
        setContentView(wv);
        wv.setWebViewClient(new WebViewClient());
        wv.loadUrl("www.google.com");
    }
}
