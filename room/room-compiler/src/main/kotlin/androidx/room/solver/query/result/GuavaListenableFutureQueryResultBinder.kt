/*
 * Copyright 2018 The Android Open Source Project
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

package androidx.room.solver.query.result

import androidx.room.compiler.codegen.XPropertySpec
import androidx.room.compiler.codegen.toJavaPoet
import androidx.room.compiler.processing.XType
import androidx.room.ext.AndroidTypeNames
import androidx.room.ext.CallableTypeSpecBuilder
import androidx.room.ext.L
import androidx.room.ext.N
import androidx.room.ext.RoomGuavaTypeNames
import androidx.room.ext.RoomTypeNames.DB_UTIL
import androidx.room.ext.T
import androidx.room.solver.CodeGenScope

/**
 * A ResultBinder that emits a ListenableFuture<T> where T is the input {@code typeArg}.
 *
 * <p>The Future runs on the background thread Executor.
 */
class GuavaListenableFutureQueryResultBinder(
    val typeArg: XType,
    adapter: QueryResultAdapter?
) : BaseObservableQueryResultBinder(adapter) {

    override fun convertAndReturn(
        roomSQLiteQueryVar: String,
        canReleaseQuery: Boolean,
        dbProperty: XPropertySpec,
        inTransaction: Boolean,
        scope: CodeGenScope
    ) {
        val dbField = dbProperty.toJavaPoet()
        val cancellationSignalVar = scope.getTmpVar("_cancellationSignal")
        scope.builder().addStatement(
            "final $T $L = $T.createCancellationSignal()",
            AndroidTypeNames.CANCELLATION_SIGNAL,
            cancellationSignalVar,
            DB_UTIL.toJavaPoet()
        )

        // Callable<T> // Note that this callable does not release the query object.
        val callableImpl = CallableTypeSpecBuilder(typeArg.typeName) {
            createRunQueryAndReturnStatements(
                builder = this,
                roomSQLiteQueryVar = roomSQLiteQueryVar,
                dbField = dbField,
                inTransaction = inTransaction,
                scope = scope,
                cancellationSignalVar = cancellationSignalVar
            )
        }.build()

        scope.builder().apply {
            addStatement(
                "return $T.createListenableFuture($N, $L, $L, $L, $L, $L)",
                RoomGuavaTypeNames.GUAVA_ROOM,
                dbField,
                if (inTransaction) "true" else "false",
                callableImpl,
                roomSQLiteQueryVar,
                canReleaseQuery,
                cancellationSignalVar
            )
        }
    }
}
