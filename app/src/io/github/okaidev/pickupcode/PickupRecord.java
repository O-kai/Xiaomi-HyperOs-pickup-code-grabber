package io.github.okaidev.pickupcode;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 本地取件记录（4.0）：App 自己的事实源。
 *
 * 只存提取后的结构化信息，默认不存短信正文；eventHash 用于事件幂等，
 * 同一短信经多个 Hook 点重复上报时只落一条。
 */
@Entity(
        tableName = "pickup_records",
        indices = {
                @Index(value = {"event_hash"}, unique = true),
                @Index(value = {"status", "received_at"})
        }
)
public class PickupRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PICKED_UP = "PICKED_UP";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "code")
    public String code = "";

    @ColumnInfo(name = "source")
    public String source;

    @ColumnInfo(name = "place")
    public String place;

    @ColumnInfo(name = "received_at")
    public long receivedAt;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "last_seen_at")
    public long lastSeenAt;

    @ColumnInfo(name = "status")
    public String status = STATUS_PENDING;

    @ColumnInfo(name = "picked_at")
    public long pickedAt;

    @ColumnInfo(name = "event_hash")
    public String eventHash;

    @ColumnInfo(name = "backend")
    public String backend;

    @ColumnInfo(name = "sync_state")
    public String syncState = "NONE";

    @ColumnInfo(name = "sync_error")
    public String syncError;

    @ColumnInfo(name = "is_test")
    public int isTest;

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }
}
