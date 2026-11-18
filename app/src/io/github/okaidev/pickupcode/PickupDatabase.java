package io.github.okaidev.pickupcode;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {PickupRecord.class}, version = 2, exportSchema = false)
public abstract class PickupDatabase extends RoomDatabase {

    private static volatile PickupDatabase INSTANCE;

    public abstract PickupDao pickupDao();

    /**
     * v4.2.1：加一列存来源原文。
     *
     * <p>绝对不能 destructive migration —— 用户的取件记录是事实源，
     * 清库等于把历史全删了（这条红线已经写过）。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            // 不能写 NOT NULL DEFAULT ''：Room 认为 String 字段可空，
            // 校验时会因为 notNull/defaultValue 对不上而整个迁移失败（真机踩过）。
            // 旧记录这里就是 NULL，RawSources.parse(null) 会当空处理。
            db.execSQL("ALTER TABLE pickup_records ADD COLUMN raw_sources TEXT");
        }
    };

    public static PickupDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (PickupDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            PickupDatabase.class,
                            "pickup_records.db"
                    )
                    .addMigrations(MIGRATION_1_2)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
