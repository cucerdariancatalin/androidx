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

import androidx.privacysandbox.tools.core.generator.AidlGenerator
import androidx.privacysandbox.tools.core.generator.SpecNames
import androidx.privacysandbox.tools.core.generator.addCode
import androidx.privacysandbox.tools.core.generator.addControlFlow
import androidx.privacysandbox.tools.core.generator.addStatement
import androidx.privacysandbox.tools.core.generator.aidlName
import androidx.privacysandbox.tools.core.generator.build
import androidx.privacysandbox.tools.core.generator.toParcelableNameSpec
import androidx.privacysandbox.tools.core.generator.fromParcelableNameSpec
import androidx.privacysandbox.tools.core.generator.parcelableNameSpec
import androidx.privacysandbox.tools.core.generator.poetSpec
import androidx.privacysandbox.tools.core.generator.primaryConstructor
import androidx.privacysandbox.tools.core.generator.transactionCallbackName
import androidx.privacysandbox.tools.core.model.AnnotatedInterface
import androidx.privacysandbox.tools.core.model.Method
import androidx.privacysandbox.tools.core.model.ParsedApi
import androidx.privacysandbox.tools.core.model.Types
import androidx.privacysandbox.tools.core.model.getOnlyService
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode

class StubDelegatesGenerator(private val api: ParsedApi) {
    companion object {
        private val ATOMIC_BOOLEAN_CLASS = ClassName("java.util.concurrent.atomic", "AtomicBoolean")
    }

    fun generate(): List<FileSpec> {
        if (api.services.isEmpty()) {
            return emptyList()
        }
        return api.services.map(::generateServiceStubDelegate) +
            generateTransportCancellationCallback()
    }

    private fun generateServiceStubDelegate(service: AnnotatedInterface): FileSpec {
        val className = service.stubDelegateName()
        val aidlBaseClassName = ClassName(service.type.packageName, service.aidlName(), "Stub")

        val classSpec = TypeSpec.classBuilder(className).build {
            superclass(aidlBaseClassName)

            primaryConstructor(
                listOf(
                    PropertySpec.builder(
                        "delegate",
                        service.type.poetSpec(),
                    ).addModifiers(KModifier.PRIVATE).build()
                ), KModifier.INTERNAL
            )

            addFunctions(service.methods.map(::toFunSpec))
        }

        return FileSpec.builder(service.type.packageName, className).build {
            addType(classSpec)
        }
    }

    private fun toFunSpec(method: Method): FunSpec {
        if (method.isSuspend) return toSuspendFunSpec(method)
        return toNonSuspendFunSpec(method)
    }

    private fun toSuspendFunSpec(method: Method): FunSpec {
        return FunSpec.builder(method.name).build {
            addModifiers(KModifier.OVERRIDE)
            addParameters(getParameters(method))
            addCode {
                addControlFlow(
                    "val job = %T.%M(%T)",
                    SpecNames.globalScopeClass,
                    SpecNames.launchMethod,
                    SpecNames.dispatchersMainClass
                ) {
                    addControlFlow("try") {
                        addStatement {
                            add("val result = ")
                            add(getDelegateCallBlock(method))
                        }
                        val value = api.valueMap[method.returnType]
                        when {
                            value != null -> {
                                addStatement(
                                    "transactionCallback.onSuccess(%M(result))",
                                    value.toParcelableNameSpec()
                                )
                            }
                            method.returnType == Types.unit -> {
                                addStatement("transactionCallback.onSuccess()")
                            }
                            else -> addStatement("transactionCallback.onSuccess(result)")
                        }
                    }
                    addControlFlow("catch (t: Throwable)") {
                        addStatement("transactionCallback.onFailure(404, t.message)")
                    }
                }
                addStatement(
                    "val cancellationSignal = TransportCancellationCallback() { job.cancel() }"
                )
                addStatement("transactionCallback.onCancellable(cancellationSignal)")
            }
        }
    }

    private fun toNonSuspendFunSpec(method: Method) = FunSpec.builder(method.name).build {
        addModifiers(KModifier.OVERRIDE)
        addParameters(getParameters(method))
        addStatement { add(getDelegateCallBlock(method)) }
    }

    private fun getParameters(method: Method) = buildList {
        addAll(method.parameters.map { parameter ->
            api.valueMap[parameter.type]?.let { value ->
                ParameterSpec(parameter.name, value.parcelableNameSpec())
            } ?: ParameterSpec(parameter.name, parameter.type.poetSpec())
        })
        if (method.isSuspend) add(
            ParameterSpec(
                "transactionCallback", ClassName(
                    api.getOnlyService().type.packageName,
                    method.returnType.transactionCallbackName()
                )
            )
        )
    }

    private fun getDelegateCallBlock(method: Method) = CodeBlock.builder().build {
        val parameters = method.parameters.map {
            val value = api.valueMap[it.type]
            if (value != null) {
                CodeBlock.of("%M(${it.name})", value.fromParcelableNameSpec())
            } else {
                CodeBlock.of(it.name)
            }
        }
        add("delegate.${method.name}(")
        add(parameters.joinToCode())
        add(")")
    }

    private fun generateTransportCancellationCallback(): FileSpec {
        val packageName = api.getOnlyService().type.packageName
        val className = "TransportCancellationCallback"
        val cancellationSignalStubName =
            ClassName(packageName, AidlGenerator.cancellationSignalName, "Stub")

        val classSpec = TypeSpec.classBuilder(className).build {
            superclass(cancellationSignalStubName)
            addModifiers(KModifier.INTERNAL)
            primaryConstructor(
                listOf(
                    PropertySpec.builder(
                        "onCancel",
                        LambdaTypeName.get(returnType = Unit::class.asTypeName()),
                    ).addModifiers(KModifier.PRIVATE).build()
                ), KModifier.INTERNAL
            )
            addProperty(
                PropertySpec.builder(
                    "hasCancelled", ATOMIC_BOOLEAN_CLASS, KModifier.PRIVATE
                ).initializer("%T(false)", ATOMIC_BOOLEAN_CLASS).build()
            )
            addFunction(FunSpec.builder("cancel").build {
                addModifiers(KModifier.OVERRIDE)
                addCode {
                    addControlFlow("if (hasCancelled.compareAndSet(false, true))") {
                        addStatement("onCancel()")
                    }
                }
            })
        }

        return FileSpec.builder(packageName, className).addType(classSpec).build()
    }
}

internal fun AnnotatedInterface.stubDelegateName() = "${type.simpleName}StubDelegate"