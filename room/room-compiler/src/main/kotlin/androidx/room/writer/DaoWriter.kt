/*
 * Copyright (C) 2016 The Android Open Source Project
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

package androidx.room.writer

import androidx.room.compiler.codegen.CodeLanguage
import androidx.room.compiler.codegen.VisibilityModifier
import androidx.room.compiler.codegen.XClassName
import androidx.room.compiler.codegen.XCodeBlock
import androidx.room.compiler.codegen.XCodeBlock.Builder.Companion.apply
import androidx.room.compiler.codegen.XFunSpec
import androidx.room.compiler.codegen.XFunSpec.Builder.Companion.apply
import androidx.room.compiler.codegen.XPropertySpec
import androidx.room.compiler.codegen.XTypeName
import androidx.room.compiler.codegen.XTypeSpec
import androidx.room.compiler.codegen.XTypeSpec.Builder.Companion.addOriginatingElement
import androidx.room.compiler.codegen.asClassName
import androidx.room.compiler.codegen.toJavaPoet
import androidx.room.compiler.processing.XElement
import androidx.room.compiler.processing.XMethodElement
import androidx.room.compiler.processing.XType
import androidx.room.compiler.processing.isVoid
import androidx.room.ext.L
import androidx.room.ext.N
import androidx.room.ext.RoomTypeNames.DELETE_OR_UPDATE_ADAPTER
import androidx.room.ext.RoomTypeNames.INSERTION_ADAPTER
import androidx.room.ext.RoomTypeNames.ROOM_DB
import androidx.room.ext.RoomTypeNames.ROOM_SQL_QUERY
import androidx.room.ext.RoomTypeNames.SHARED_SQLITE_STMT
import androidx.room.ext.RoomTypeNames.UPSERTION_ADAPTER
import androidx.room.ext.SupportDbTypeNames
import androidx.room.ext.T
import androidx.room.ext.W
import androidx.room.ext.capitalize
import androidx.room.processor.OnConflictProcessor
import androidx.room.solver.CodeGenScope
import androidx.room.solver.KotlinDefaultMethodDelegateBinder
import androidx.room.solver.types.getRequiredTypeConverters
import androidx.room.vo.Dao
import androidx.room.vo.DeleteOrUpdateShortcutMethod
import androidx.room.vo.InsertionMethod
import androidx.room.vo.KotlinBoxedPrimitiveMethodDelegate
import androidx.room.vo.KotlinDefaultMethodDelegate
import androidx.room.vo.QueryMethod
import androidx.room.vo.RawQueryMethod
import androidx.room.vo.ReadQueryMethod
import androidx.room.vo.ShortcutEntity
import androidx.room.vo.TransactionMethod
import androidx.room.vo.UpdateMethod
import androidx.room.vo.UpsertionMethod
import androidx.room.vo.WriteQueryMethod
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.TypeSpec
import com.squareup.kotlinpoet.javapoet.JWildcardTypeName
import java.util.Arrays
import java.util.Collections
import java.util.Locale

/**
 * Creates the implementation for a class annotated with Dao.
 */
class DaoWriter(
    val dao: Dao,
    private val dbElement: XElement,
    codeLanguage: CodeLanguage
) : TypeWriter(codeLanguage) {
    private val declaredDao = dao.element.type

    // TODO nothing prevents this from conflicting, we should fix.
    private val dbProperty: XPropertySpec = XPropertySpec
        .builder(codeLanguage, DB_PROPERTY_NAME, ROOM_DB, VisibilityModifier.PRIVATE)
        .build()

    private val companionTypeBuilder = lazy {
        XTypeSpec.companionObjectBuilder(codeLanguage)
    }

    companion object {
        const val GET_LIST_OF_TYPE_CONVERTERS_METHOD = "getRequiredConverters"

        const val DB_PROPERTY_NAME = "__db"

        private fun shortcutEntityFieldNamePart(shortcutEntity: ShortcutEntity): String {
            fun typeNameToFieldName(typeName: XClassName): String {
                return typeName.simpleNames.last()
            }
            return if (shortcutEntity.isPartialEntity) {
                typeNameToFieldName(shortcutEntity.pojo.className) + "As" +
                    typeNameToFieldName(shortcutEntity.entityClassName)
            } else {
                typeNameToFieldName(shortcutEntity.entityClassName)
            }
        }
    }

    override fun createTypeSpecBuilder(): XTypeSpec.Builder {
        val builder = XTypeSpec.classBuilder(codeLanguage, dao.implTypeName)

        /**
         * For prepared statements that perform insert/update/delete/upsert,
         * we check if there are any arguments of variable length (e.g. "IN (:var)").
         * If not, we should re-use the statement.
         * This requires more work but creates good performance.
         */
        val groupedPreparedQueries = dao.queryMethods
            .filterIsInstance<WriteQueryMethod>()
            .groupBy { it.parameters.any { it.queryParamAdapter?.isMultiple ?: true } }
        // queries that can be prepared ahead of time
        val preparedQueries = groupedPreparedQueries[false] ?: emptyList()
        // queries that must be rebuilt every single time
        val oneOffPreparedQueries = groupedPreparedQueries[true] ?: emptyList()
        val shortcutMethods = buildList {
            addAll(createInsertionMethods())
            addAll(createDeletionMethods())
            addAll(createUpdateMethods())
            addAll(createTransactionMethods())
            addAll(createPreparedQueries(preparedQueries))
            addAll(createUpsertMethods())
        }

        builder.apply {
            addOriginatingElement(dbElement)
            setVisibility(VisibilityModifier.PUBLIC)
            if (dao.element.isInterface()) {
                addSuperinterface(dao.typeName)
            } else {
                superclass(dao.typeName)
            }
            addProperty(dbProperty)

            addFunction(
                createConstructor(
                    shortcutMethods,
                    dao.constructorParamType != null
                )
            )

            shortcutMethods.forEach {
                addFunction(it.functionImpl)
            }

            dao.queryMethods.filterIsInstance<ReadQueryMethod>().forEach { method ->
                addFunction(createSelectMethod(method))
            }
            oneOffPreparedQueries.forEach {
                addFunction(createPreparedQueryMethod(it))
            }
            dao.rawQueryMethods.forEach {
                addFunction(createRawQueryMethod(it))
            }
            dao.kotlinDefaultMethodDelegates.forEach {
                addFunction(createDefaultMethodDelegate(it))
            }

            dao.delegatingMethods.forEach {
                addFunction(createDelegatingMethod(it))
            }
            // Keep this the last one to be generated because used custom converters will
            // register fields with a payload which we collect in dao to report used
            // Type Converters.
            addConverterListMethod(this)

            if (companionTypeBuilder.isInitialized()) {
                addType(companionTypeBuilder.value.build())
            }
        }
        return builder
    }

    private fun addConverterListMethod(typeSpecBuilder: XTypeSpec.Builder) {
        when (codeLanguage) {
            // For Java a static method is created
            CodeLanguage.JAVA -> typeSpecBuilder.addFunction(createConverterListMethod())
            // For Kotlin a function in the companion object is created
            CodeLanguage.KOTLIN -> companionTypeBuilder.value
                    .addFunction(createConverterListMethod())
                    .build()
        }
    }

    private fun createConverterListMethod(): XFunSpec {
        val body = XCodeBlock.builder(codeLanguage).apply {
            val requiredTypeConverters = getRequiredTypeConverters()
            if (requiredTypeConverters.isEmpty()) {
                when (language) {
                    CodeLanguage.JAVA ->
                        addStatement("return %T.emptyList()", Collections::class.asClassName())
                    CodeLanguage.KOTLIN ->
                        addStatement("return emptyList()")
                }
            } else {
                val placeholders = requiredTypeConverters.joinToString(",") { "%L" }
                val requiredTypeConvertersLiterals = requiredTypeConverters.map {
                    XCodeBlock.ofJavaClassLiteral(language, it)
                }.toTypedArray()
                when (language) {
                    CodeLanguage.JAVA ->
                        addStatement("return %T.asList($placeholders)",
                            Arrays::class.asClassName(),
                            *requiredTypeConvertersLiterals
                        )
                    CodeLanguage.KOTLIN ->
                        addStatement(
                            "return listOf($placeholders)",
                            *requiredTypeConvertersLiterals
                        )
                }
            }
        }.build()
        return XFunSpec.builder(
            codeLanguage,
            GET_LIST_OF_TYPE_CONVERTERS_METHOD,
            VisibilityModifier.PUBLIC
        ).apply(
            javaMethodBuilder = {
                addModifiers(javax.lang.model.element.Modifier.STATIC)
            },
            kotlinFunctionBuilder = {
                addAnnotation(kotlin.jvm.JvmStatic::class)
            },
        ).apply {
            returns(
                List::class.asClassName().parametrizedBy(
                    Class::class.asClassName().parametrizedBy(
                        // TODO(b/249984508): Create XTypeName factory for type variable names
                        XTypeName(
                            java = JWildcardTypeName.subtypeOf(Object::class.java),
                            kotlin = com.squareup.kotlinpoet.STAR
                        )
                    )
                )
            )
            addCode(body)
        }.build()
    }

    private fun createPreparedQueries(
        preparedQueries: List<WriteQueryMethod>
    ): List<PreparedStmtQuery> {
        return preparedQueries.map { method ->
            val fieldSpec = getOrCreateProperty(PreparedStatementProperty(method))
            val queryWriter = QueryWriter(method)
            val fieldImpl = PreparedStatementWriter(queryWriter)
                .createAnonymous(this@DaoWriter, dbProperty.toJavaPoet())
            val methodBody =
                createPreparedQueryMethodBody(method, fieldSpec, queryWriter)
            PreparedStmtQuery(
                mapOf(PreparedStmtQuery.NO_PARAM_FIELD to (fieldSpec to fieldImpl)),
                methodBody
            )
        }
    }

    private fun createPreparedQueryMethodBody(
        method: WriteQueryMethod,
        preparedStmtField: XPropertySpec,
        queryWriter: QueryWriter
    ): XFunSpec {
        val scope = CodeGenScope(this)
        method.preparedQueryResultBinder.executeAndReturn(
            prepareQueryStmtBlock = {
                val stmtName = getTmpVar("_stmt")
                builder().apply {
                    addStatement(
                        "final $T $L = $L.acquire()",
                        SupportDbTypeNames.SQLITE_STMT, stmtName, preparedStmtField.name
                    )
                }
                queryWriter.bindArgs(stmtName, emptyList(), this)
                stmtName
            },
            preparedStmtField = preparedStmtField.name,
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(scope.generate())
            .build()
    }

    private fun createTransactionMethods(): List<PreparedStmtQuery> {
        return dao.transactionMethods.map {
            PreparedStmtQuery(emptyMap(), createTransactionMethodBody(it))
        }
    }

    private fun createTransactionMethodBody(method: TransactionMethod): XFunSpec {
        val scope = CodeGenScope(this)
        method.methodBinder.executeAndReturn(
            returnType = method.returnType,
            parameterNames = method.parameterNames,
            daoName = dao.typeName.toJavaPoet(),
            daoImplName = dao.implTypeName.toJavaPoet(),
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(scope.generate())
            .build()
    }

    private fun createConstructor(
        shortcutMethods: List<PreparedStmtQuery>,
        callSuper: Boolean
    ): XFunSpec {
        val body = XCodeBlock.builder(codeLanguage).apply {
            addStatement("this.%N = %L", dbProperty, dbProperty.name)
            shortcutMethods.asSequence().filterNot {
                it.fields.isEmpty()
            }.map {
                it.fields.values
            }.flatten().groupBy {
                it.first.name
            }.map {
                it.value.first()
            }.forEach { (propertySpec, initExpression) ->
                addStatement("this.%L = %L", propertySpec.name, initExpression)
            }
        }.build()
        return XFunSpec.constructorBuilder(codeLanguage, VisibilityModifier.PUBLIC).apply {
            addParameter(
                typeName = dao.constructorParamType ?: ROOM_DB,
                name = dbProperty.name
            )
            if (callSuper) {
                callSuperConstructor(XCodeBlock.of(language, "%L", dbProperty.name))
            }
            addCode(body)
        }.build()
    }

    private fun createSelectMethod(method: ReadQueryMethod): XFunSpec {
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(createQueryMethodBody(method))
            .build()
    }

    private fun createRawQueryMethod(method: RawQueryMethod): XFunSpec {
        val body = XCodeBlock.builder(codeLanguage).apply {
            val scope = CodeGenScope(this@DaoWriter)
            val roomSQLiteQueryVar: String
            val queryParam = method.runtimeQueryParam
            val shouldReleaseQuery: Boolean
            when {
                queryParam?.isString() == true -> {
                    roomSQLiteQueryVar = scope.getTmpVar("_statement")
                    shouldReleaseQuery = true
                    addStatement(
                        "$T $L = $T.acquire($L, 0)",
                        ROOM_SQL_QUERY.toJavaPoet(),
                        roomSQLiteQueryVar,
                        ROOM_SQL_QUERY.toJavaPoet(),
                        queryParam.paramName
                    )
                }
                queryParam?.isSupportQuery() == true -> {
                    shouldReleaseQuery = false
                    roomSQLiteQueryVar = scope.getTmpVar("_internalQuery")
                    // move it to a final variable so that the generated code can use it inside
                    // callback blocks in java 7
                    addStatement(
                        "final $T $L = $N",
                        queryParam.type,
                        roomSQLiteQueryVar,
                        queryParam.paramName
                    )
                }
                else -> {
                    // try to generate compiling code. we would've already reported this error
                    roomSQLiteQueryVar = scope.getTmpVar("_statement")
                    shouldReleaseQuery = false
                    addStatement(
                        "$T $L = $T.acquire($L, 0)",
                        ROOM_SQL_QUERY.toJavaPoet(),
                        roomSQLiteQueryVar,
                        ROOM_SQL_QUERY.toJavaPoet(),
                        "missing query parameter"
                    )
                }
            }
            if (method.returnsValue) {
                // don't generate code because it will create 1 more error. The original error is
                // already reported by the processor.
                method.queryResultBinder.convertAndReturn(
                    roomSQLiteQueryVar = roomSQLiteQueryVar,
                    canReleaseQuery = shouldReleaseQuery,
                    dbProperty = dbProperty,
                    inTransaction = method.inTransaction,
                    scope = scope
                )
            }
            add(scope.generate())
        }.build()
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(body)
            .build()
    }

    private fun createPreparedQueryMethod(method: WriteQueryMethod): XFunSpec {
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(createPreparedQueryMethodBody(method))
            .build()
    }

    /**
     * Groups all insertion methods based on the insert statement they will use then creates all
     * field specs, EntityInsertionAdapterWriter and actual insert methods.
     */
    private fun createInsertionMethods(): List<PreparedStmtQuery> {
        return dao.insertionMethods
            .map { insertionMethod ->
                val onConflict = OnConflictProcessor.onConflictText(insertionMethod.onConflict)
                val entities = insertionMethod.entities

                val fields = entities.mapValues {
                    val spec = getOrCreateProperty(InsertionMethodProperty(it.value, onConflict))
                    val impl = EntityInsertionAdapterWriter.create(it.value, onConflict)
                        .createAnonymous(this@DaoWriter, dbProperty.name)
                    spec to impl
                }
                val methodImpl = overrideWithoutAnnotations(
                    insertionMethod.element,
                    declaredDao
                ).apply {
                    addCode(createInsertionMethodBody(insertionMethod, fields))
                }.build()
                PreparedStmtQuery(fields, methodImpl)
            }
    }

    private fun createInsertionMethodBody(
        method: InsertionMethod,
        insertionAdapters: Map<String, Pair<XPropertySpec, TypeSpec>>
    ): XCodeBlock {
        if (insertionAdapters.isEmpty() || method.methodBinder == null) {
            return XCodeBlock.builder(codeLanguage).build()
        }
        val scope = CodeGenScope(this)
        method.methodBinder.convertAndReturn(
            parameters = method.parameters,
            adapters = insertionAdapters,
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return scope.generate()
    }

    /**
     * Creates EntityUpdateAdapter for each deletion method.
     */
    private fun createDeletionMethods(): List<PreparedStmtQuery> {
        return createShortcutMethods(dao.deletionMethods, "deletion") { _, entity ->
            EntityDeletionAdapterWriter.create(entity)
                .createAnonymous(this@DaoWriter, dbProperty.name)
        }
    }

    /**
     * Creates EntityUpdateAdapter for each @Update method.
     */
    private fun createUpdateMethods(): List<PreparedStmtQuery> {
        return createShortcutMethods(dao.updateMethods, "update") { update, entity ->
            val onConflict = OnConflictProcessor.onConflictText(update.onConflictStrategy)
            EntityUpdateAdapterWriter.create(entity, onConflict)
                .createAnonymous(this@DaoWriter, dbProperty.name)
        }
    }

    private fun <T : DeleteOrUpdateShortcutMethod> createShortcutMethods(
        methods: List<T>,
        methodPrefix: String,
        implCallback: (T, ShortcutEntity) -> TypeSpec
    ): List<PreparedStmtQuery> {
        return methods.mapNotNull { method ->
            val entities = method.entities
            if (entities.isEmpty()) {
                null
            } else {
                val onConflict = if (method is UpdateMethod) {
                    OnConflictProcessor.onConflictText(method.onConflictStrategy)
                } else {
                    ""
                }
                val fields = entities.mapValues {
                    val spec = getOrCreateProperty(
                        DeleteOrUpdateAdapterProperty(it.value, methodPrefix, onConflict)
                    )
                    val impl = implCallback(method, it.value)
                    spec to impl
                }
                val methodSpec = overrideWithoutAnnotations(method.element, declaredDao).apply {
                    addCode(createDeleteOrUpdateMethodBody(method, fields))
                }.build()
                PreparedStmtQuery(fields, methodSpec)
            }
        }
    }

    private fun createDeleteOrUpdateMethodBody(
        method: DeleteOrUpdateShortcutMethod,
        adapters: Map<String, Pair<XPropertySpec, TypeSpec>>
    ): XCodeBlock {
        if (adapters.isEmpty() || method.methodBinder == null) {
            return XCodeBlock.builder(codeLanguage).build()
        }
        val scope = CodeGenScope(this)

        method.methodBinder.convertAndReturn(
            parameters = method.parameters,
            adapters = adapters,
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return scope.generate()
    }

    /**
     * Groups all upsertion methods based on the upsert statement they will use then creates all
     * field specs, EntityIUpsertionAdapterWriter and actual upsert methods.
     */
    private fun createUpsertMethods(): List<PreparedStmtQuery> {
        return dao.upsertionMethods
            .map { upsertionMethod ->
                val entities = upsertionMethod.entities
                val fields = entities.mapValues {
                    val spec = getOrCreateProperty(UpsertionAdapterProperty(it.value))
                    val impl = EntityUpsertionAdapterWriter.create(it.value)
                        .createConcrete(it.value, this@DaoWriter, dbProperty.name)
                    spec to impl
                }
                val methodImpl = overrideWithoutAnnotations(
                    upsertionMethod.element,
                    declaredDao
                ).apply {
                    addCode(createUpsertionMethodBody(upsertionMethod, fields))
                }.build()
                PreparedStmtQuery(fields, methodImpl)
            }
    }

    private fun createUpsertionMethodBody(
        method: UpsertionMethod,
        upsertionAdapters: Map<String, Pair<XPropertySpec, CodeBlock>>
    ): XCodeBlock {
        if (upsertionAdapters.isEmpty() || method.methodBinder == null) {
            return XCodeBlock.builder(codeLanguage).build()
        }
        val scope = CodeGenScope(this)

        method.methodBinder.convertAndReturn(
            parameters = method.parameters,
            adapters = upsertionAdapters,
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return scope.generate()
    }

    private fun createPreparedQueryMethodBody(method: WriteQueryMethod): XCodeBlock {
        val scope = CodeGenScope(this)
        method.preparedQueryResultBinder.executeAndReturn(
            prepareQueryStmtBlock = {
                val queryWriter = QueryWriter(method)
                val sqlVar = getTmpVar("_sql")
                val stmtVar = getTmpVar("_stmt")
                val listSizeArgs = queryWriter.prepareQuery(sqlVar, this)
                builder().apply {
                    addStatement(
                        "final $T $L = $N.compileStatement($L)",
                        SupportDbTypeNames.SQLITE_STMT, stmtVar, dbProperty.toJavaPoet(), sqlVar
                    )
                }
                queryWriter.bindArgs(stmtVar, listSizeArgs, this)
                stmtVar
            },
            preparedStmtField = null,
            dbField = dbProperty.toJavaPoet(),
            scope = scope
        )
        return scope.builder.build()
    }

    private fun createQueryMethodBody(method: ReadQueryMethod): XCodeBlock {
        val queryWriter = QueryWriter(method)
        val scope = CodeGenScope(this)
        val sqlVar = scope.getTmpVar("_sql")
        val roomSQLiteQueryVar = scope.getTmpVar("_statement")
        queryWriter.prepareReadAndBind(sqlVar, roomSQLiteQueryVar, scope)
        method.queryResultBinder.convertAndReturn(
            roomSQLiteQueryVar = roomSQLiteQueryVar,
            canReleaseQuery = true,
            dbProperty = dbProperty,
            inTransaction = method.inTransaction,
            scope = scope
        )
        return scope.generate()
    }

    // TODO(b/251459654): Handle @JvmOverloads in delegating functions with Kotlin codegen.
    private fun createDefaultMethodDelegate(method: KotlinDefaultMethodDelegate): XFunSpec {
        val scope = CodeGenScope(this)
        return overrideWithoutAnnotations(method.element, declaredDao).apply {
            // TODO(danysantiago): Revisit this in Kotlin codegen
            KotlinDefaultMethodDelegateBinder.executeAndReturn(
                daoName = dao.typeName.toJavaPoet(),
                daoImplName = dao.implTypeName.toJavaPoet(),
                methodName = method.element.jvmName,
                returnType = method.element.returnType,
                parameterNames = method.element.parameters.map { it.name },
                scope = scope
            )
            addCode(scope.generate())
        }.build()
    }

    // TODO(b/127483380): Reconsider the need of delegating method in KotlinPoet.
    private fun createDelegatingMethod(method: KotlinBoxedPrimitiveMethodDelegate): XFunSpec {
        val body = XCodeBlock.builder(codeLanguage).apply(
            javaCodeBuilder = {
                val args = method.concreteMethod.parameters.map {
                    val paramTypename = it.type.typeName
                    if (paramTypename.isBoxedPrimitive()) {
                        CodeBlock.of("$L", paramTypename, it.name.toString())
                    } else {
                        CodeBlock.of("($T) $L", paramTypename.unbox(), it.name.toString())
                    }
                }
                if (method.element.returnType.isVoid()) {
                    addStatement(
                        "$L($L)",
                        method.element.jvmName,
                        CodeBlock.join(args, ",$W")
                    )
                } else {
                    addStatement(
                        "return $L($L)",
                        method.element.jvmName,
                        CodeBlock.join(args, ",$W")
                    )
                }
            },
            kotlinCodeBuilder = { TODO("Kotlin codegen not yet implemented!") }
        ).build()
        return overrideWithoutAnnotations(method.element, declaredDao)
            .addCode(body)
            .build()
    }

    private fun overrideWithoutAnnotations(
        elm: XMethodElement,
        owner: XType
    ): XFunSpec.Builder {
        return XFunSpec.overridingBuilder(codeLanguage, elm, owner)
    }

    /**
     * Represents a query statement prepared in Dao implementation.
     *
     * @param fields This map holds all the member properties necessary for this query. The key is
     * the corresponding parameter name in the defining query method. The value is a pair from the
     * property declaration to definition.
     * @param functionImpl The body of the query method implementation.
     */
    data class PreparedStmtQuery(
        val fields: Map<String, Pair<XPropertySpec, Any>>,
        val functionImpl: XFunSpec
    ) {
        companion object {
            // The key to be used in `fields` where the method requires a field that is not
            // associated with any of its parameters
            const val NO_PARAM_FIELD = "-"
        }
    }

    private class InsertionMethodProperty(
        val shortcutEntity: ShortcutEntity,
        val onConflictText: String
    ) : SharedPropertySpec(
        baseName = "insertionAdapterOf${shortcutEntityFieldNamePart(shortcutEntity)}",
        type = INSERTION_ADAPTER.parametrizedBy(shortcutEntity.pojo.typeName)
    ) {
        override fun getUniqueKey(): String {
            return "${shortcutEntity.pojo.typeName}-${shortcutEntity.entityTypeName}$onConflictText"
        }

        override fun prepare(writer: TypeWriter, builder: XPropertySpec.Builder) {
        }
    }

    class DeleteOrUpdateAdapterProperty(
        val shortcutEntity: ShortcutEntity,
        val methodPrefix: String,
        val onConflictText: String
    ) : SharedPropertySpec(
        baseName = "${methodPrefix}AdapterOf${shortcutEntityFieldNamePart(shortcutEntity)}",
        type = DELETE_OR_UPDATE_ADAPTER.parametrizedBy(shortcutEntity.pojo.typeName)
    ) {
        override fun prepare(writer: TypeWriter, builder: XPropertySpec.Builder) {
        }

        override fun getUniqueKey(): String {
            return "${shortcutEntity.pojo.typeName}-${shortcutEntity.entityTypeName}" +
                "$methodPrefix$onConflictText"
        }
    }

    class UpsertionAdapterProperty(
        val shortcutEntity: ShortcutEntity
    ) : SharedPropertySpec(
        baseName = "upsertionAdapterOf${shortcutEntityFieldNamePart(shortcutEntity)}",
        type = UPSERTION_ADAPTER.parametrizedBy(shortcutEntity.pojo.typeName)
    ) {
        override fun getUniqueKey(): String {
            return "${shortcutEntity.pojo.typeName}-${shortcutEntity.entityTypeName}"
        }

        override fun prepare(writer: TypeWriter, builder: XPropertySpec.Builder) {
        }
    }

    class PreparedStatementProperty(val method: QueryMethod) : SharedPropertySpec(
        baseName = "preparedStmtOf${method.element.jvmName.capitalize(Locale.US)}",
        type = SHARED_SQLITE_STMT
    ) {
        override fun prepare(writer: TypeWriter, builder: XPropertySpec.Builder) {
        }

        override fun getUniqueKey(): String {
            return method.query.original
        }
    }
}
