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

package androidx.room.compiler.processing.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.javapoet.KClassName
import com.squareup.kotlinpoet.javapoet.KTypeName
import com.squareup.kotlinpoet.javapoet.KTypeVariableName
import com.squareup.kotlinpoet.javapoet.KWildcardTypeName

internal val ERROR_KTYPE_NAME = KClassName("error", "NonExistentClass")

private typealias KTypeArgumentTypeLookup = LinkedHashMap<KSName, KTypeName>

internal fun KSTypeReference?.asKTypeName(resolver: Resolver): KTypeName =
    asKTypeName(
        resolver = resolver,
        typeArgumentTypeLookup = KTypeArgumentTypeLookup()
    )

private fun KSTypeReference?.asKTypeName(
    resolver: Resolver,
    typeArgumentTypeLookup: KTypeArgumentTypeLookup
): KTypeName {
    return if (this == null) {
        ERROR_KTYPE_NAME
    } else {
        resolve().asKTypeName(resolver, typeArgumentTypeLookup)
    }
}

internal fun KSDeclaration.asKTypeName(resolver: Resolver): KTypeName =
    asKTypeName(
        resolver = resolver,
        typeArgumentTypeLookup = KTypeArgumentTypeLookup()
    )

private fun KSDeclaration.asKTypeName(
    resolver: Resolver,
    typeArgumentTypeLookup: KTypeArgumentTypeLookup
): KTypeName {
    if (this is KSTypeAlias) {
        return this.type.asKTypeName(resolver, typeArgumentTypeLookup)
    }
    if (this is KSTypeParameter) {
        return this.asKTypeName(resolver, typeArgumentTypeLookup)
    }
    val qualified = qualifiedName?.asString() ?: return ERROR_KTYPE_NAME
    val pkg = getNormalizedPackageName()
    val shortNames = if (pkg == "") {
        qualified
    } else {
        qualified.substring(pkg.length + 1)
    }.split('.')
    return KClassName(pkg, shortNames.first(), *(shortNames.drop(1).toTypedArray()))
}

private fun KSTypeParameter.asKTypeName(
    resolver: Resolver,
    typeArgumentTypeLookup: KTypeArgumentTypeLookup
): KTypeName {
    typeArgumentTypeLookup[name]?.let {
        return it
    }
    val mutableBounds = mutableListOf(ANY.copy(nullable = true))
    val typeName = createModifiableTypeVariableName(name = name.asString(), bounds = mutableBounds)
    typeArgumentTypeLookup[name] = typeName
    val resolvedBounds = bounds.map {
        it.asKTypeName(resolver, typeArgumentTypeLookup)
    }.toList()
    if (resolvedBounds.isNotEmpty()) {
        mutableBounds.addAll(resolvedBounds)
        mutableBounds.remove(ANY.copy(nullable = true))
    }
    typeArgumentTypeLookup.remove(name)
    return typeName
}

internal fun KSTypeArgument.asKTypeName(
    resolver: Resolver
): KTypeName = asKTypeName(
    resolver = resolver,
    typeArgumentTypeLookup = KTypeArgumentTypeLookup()
)

private fun KSTypeArgument.asKTypeName(
    resolver: Resolver,
    typeArgumentTypeLookup: KTypeArgumentTypeLookup
): KTypeName {
    fun resolveTypeName() = type.asKTypeName(resolver, typeArgumentTypeLookup)
    return when (variance) {
        Variance.CONTRAVARIANT -> KWildcardTypeName.consumerOf(resolveTypeName())
        Variance.COVARIANT -> KWildcardTypeName.producerOf(resolveTypeName())
        Variance.STAR -> com.squareup.kotlinpoet.STAR
        else -> {
            if (hasJvmWildcardAnnotation()) {
                KWildcardTypeName.consumerOf(resolveTypeName())
            } else {
                resolveTypeName()
            }
        }
    }
}

internal fun KSType.asKTypeName(resolver: Resolver): KTypeName =
    asKTypeName(
        resolver = resolver,
        typeArgumentTypeLookup = KTypeArgumentTypeLookup()
    )

@OptIn(KspExperimental::class)
private fun KSType.asKTypeName(
    resolver: Resolver,
    typeArgumentTypeLookup: KTypeArgumentTypeLookup
): KTypeName {
    return if (this.arguments.isNotEmpty() && !resolver.isJavaRawType(this)) {
        val args: List<KTypeName> = this.arguments
            .map { typeArg ->
                typeArg.asKTypeName(
                    resolver = resolver,
                    typeArgumentTypeLookup = typeArgumentTypeLookup
                )
            }
        val typeName = declaration.asKTypeName(resolver, typeArgumentTypeLookup)
        check(typeName is KClassName) { "Unexpected type name for KSType: $typeName" }
        typeName.parameterizedBy(args)
    } else {
        this.declaration.asKTypeName(resolver, typeArgumentTypeLookup)
    }.copy(nullable = isMarkedNullable)
}

/**
 * Creates a TypeVariableName where we can change the bounds after constructor.
 * This is used to workaround a case for self referencing type declarations.
 */
private fun createModifiableTypeVariableName(
    name: String,
    bounds: List<KTypeName>
): KTypeVariableName = KTypeVariableNameFactory.newInstance(name, bounds)
