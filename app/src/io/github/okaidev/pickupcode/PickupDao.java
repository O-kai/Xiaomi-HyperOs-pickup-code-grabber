package io.github.okaidev.pickupcode;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PickupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(PickupRecord record);

    @Query("SELECT * FROM pickup_records WHERE event_hash = :hash LIMIT 1")
    PickupRecord findByEventHash(String hash);

    @Query("SELECT * FROM pickup_records WHERE code = :code "
            + "AND COALESCE(place, '') = COALESCE(:place, '') "
            + "AND status = 'PENDING' AND is_test = 0 "
            + "ORDER BY id DESC LIMIT 1")
    PickupRecord findActive(String code, String place);

    @Query("UPDATE pickup_records SET last_seen_at = :ts WHERE id = :id")
    void touch(long id, long ts);

    @Query("SELECT * FROM pickup_records WHERE status = 'PENDING' AND is_test = 0 "
            + "ORDER BY received_at DESC")
    List<PickupRecord> pending();

    @Query("SELECT * FROM pickup_records WHERE status = 'PENDING' AND is_test = 0 "
            + "ORDER BY received_at DESC LIMIT :limit")
    List<PickupRecord> pendingLimit(int limit);

    @Query("SELECT COUNT(*) FROM pickup_records WHERE status = 'PENDING' AND is_test = 0")
    int countPending();

    @Query("SELECT * FROM pickup_records WHERE is_test = 0 "
            + "ORDER BY received_at DESC LIMIT :limit")
    List<PickupRecord> recent(int limit);

    @Query("SELECT * FROM pickup_records WHERE code = :code "
            + "AND status = 'PENDING' AND is_test = 0 "
            + "ORDER BY id DESC LIMIT 1")
    PickupRecord findPendingByCode(String code);

    @Query("SELECT * FROM pickup_records WHERE id = :id LIMIT 1")
    PickupRecord byId(long id);

    /** 按码查任意状态的记录（导入历史时用来避免与已有记录重复） */
    @Query("SELECT * FROM pickup_records WHERE code = :code ORDER BY id DESC LIMIT 1")
    PickupRecord findAnyByCode(String code);

    @Query("UPDATE pickup_records SET status = 'PICKED_UP', picked_at = :ts, "
            + "last_seen_at = :ts WHERE id = :id")
    int markDone(long id, long ts);

    @Query("DELETE FROM pickup_records WHERE id = :id")
    void delete(long id);
}
