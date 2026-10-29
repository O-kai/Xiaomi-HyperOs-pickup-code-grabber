package io.github.okaidev.pickupcode;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {PickupRecord.class}, version = 1, exportSchema = false)
public abstract class PickupDatabase extends RoomDatabase {

    private static volatile PickupDatabase INSTANCE;

    public abstract PickupDao pickupDao();

    public static PickupDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (PickupDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            PickupDatabase.class,
                            "pickup_records.db"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
