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

package androidx.privacysandbox.tools.apicompiler.generator

import androidx.privacysandbox.tools.core.generator.build
import androidx.privacysandbox.tools.core.generator.poetSpec
import androidx.privacysandbox.tools.core.model.AnnotatedInterface
import androidx.privacysandbox.tools.core.model.ParsedApi
import androidx.privacysandbox.tools.core.model.getOnlyService
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec

class AbstractSdkProviderGenerator(private val api: ParsedApi) {
    companion object {
        private val SANDBOXED_SDK_PROVIDER_CLASS =
            ClassName("android.app.sdksandbox", "SandboxedSdkProvider")
        private val DATA_RECEIVED_CALLBACK_CLASS =
            ClassName("android.app.sdksandbox", "SandboxedSdkProvider", "DataReceivedCallback")
        private val SANDBOXED_SDK_CLASS =
            ClassName("android.app.sdksandbox", "SandboxedSdk")
        private val CONTEXT_CLASS = ClassName("android.content", "Context")
        private val BUNDLE_CLASS = ClassName("android.os", "Bundle")
        private val VIEW_CLASS = ClassName("android.view", "View")
    }

    fun generate(): FileSpec? {
        if (api.services.isEmpty()) {
            return null
        }
        val packageName = api.getOnlyService().type.packageName
        val className = "AbstractSandboxedSdkProvider"
        val classSpec =
            TypeSpec.classBuilder(className)
                .superclass(SANDBOXED_SDK_PROVIDER_CLASS)
                .addModifiers(KModifier.ABSTRACT)
                .addFunction(generateOnLoadSdkFunction())
                .addFunction(generateGetViewFunction())
                .addFunction(generateOnDataReceivedFunction())
                .addFunction(generateCreateServiceFunction(api.getOnlyService()))

        return FileSpec.builder(packageName, className)
            .addType(classSpec.build())
            .build()
    }

    private fun generateOnLoadSdkFunction(): FunSpec {
        return FunSpec.builder("onLoadSdk").build {
            addModifiers(KModifier.OVERRIDE)
            addParameter("params", BUNDLE_CLASS)
            returns(SANDBOXED_SDK_CLASS)
            addStatement(
                "val sdk = ${getCreateServiceFunctionName(api.getOnlyService())}(context!!)"
            )
            addStatement(
                "return ${SANDBOXED_SDK_CLASS.simpleName}" +
                    "(${api.getOnlyService().stubDelegateName()}(sdk))"
            )
        }
    }

    private fun generateGetViewFunction(): FunSpec {
        return FunSpec.builder("getView").build {
            addModifiers(KModifier.OVERRIDE)
            addParameter("windowContext", CONTEXT_CLASS)
            addParameter("params", BUNDLE_CLASS)
            addParameter("width", Int::class)
            addParameter("height", Int::class)
            returns(VIEW_CLASS)
            addStatement("TODO(\"Implement\")")
        }
    }

    private fun generateCreateServiceFunction(service: AnnotatedInterface): FunSpec {
        return FunSpec.builder(getCreateServiceFunctionName(service))
            .addModifiers(KModifier.ABSTRACT, KModifier.PROTECTED)
            .addParameter("context", CONTEXT_CLASS)
            .returns(service.type.poetSpec())
            .build()
    }

    private fun generateOnDataReceivedFunction(): FunSpec {
        return FunSpec.builder("onDataReceived")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("data", BUNDLE_CLASS)
            .addParameter("callback", DATA_RECEIVED_CALLBACK_CLASS)
            .build()
    }

    private fun getCreateServiceFunctionName(service: AnnotatedInterface) =
        "create${service.type.simpleName}"
}