import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.RoomSQLiteQuery.Companion.acquire
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
import java.lang.Class
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["unchecked", "deprecation"])
public class MyDao_Impl : MyDao {
    private val __db: RoomDatabase

    public constructor(__db: RoomDatabase) {
        this.__db = __db
    }

    public override fun getEntity(): MyEntity {
        val _sql: String = "SELECT * FROM MyEntity"
        val _statement: RoomSQLiteQuery = acquire(_sql, 0)
        __db.assertNotSuspendingTransaction()
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
            val _cursorIndexOfPk: Int = getColumnIndexOrThrow(_cursor, "pk")
            val _cursorIndexOfBoolean: Int = getColumnIndexOrThrow(_cursor, "boolean")
            val _cursorIndexOfNullableBoolean: Int = getColumnIndexOrThrow(_cursor, "nullableBoolean")
            val _result: MyEntity
            if (_cursor.moveToFirst()) {
                val _tmpPk: Int
                _tmpPk = _cursor.getInt(_cursorIndexOfPk)
                val _tmpBoolean: Boolean
                val _tmp: Int
                _tmp = _cursor.getInt(_cursorIndexOfBoolean)
                _tmpBoolean = _tmp != 0
                val _tmpNullableBoolean: Boolean?
                val _tmp_1: Int?
                if (_cursor.isNull(_cursorIndexOfNullableBoolean)) {
                    _tmp_1 = null
                } else {
                    _tmp_1 = _cursor.getInt(_cursorIndexOfNullableBoolean)
                }
                _tmpNullableBoolean = _tmp_1?.let { it != 0 }
                _result = MyEntity(_tmpPk,_tmpBoolean,_tmpNullableBoolean)
            } else {
                error("Cursor was empty, but expected a single item.")
            }
            return _result
        } finally {
            _cursor.close()
            _statement.release()
        }
    }

    public companion object {
        @JvmStatic
        public fun getRequiredConverters(): List<Class<*>> = emptyList()
    }
}