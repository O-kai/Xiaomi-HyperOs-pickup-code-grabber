package io.github.okaidev.pickupcode;

/**
 * 一条取件记录的「可移植表示」（纯 Java，不依赖 Room / Android）。
 *
 * <p>为什么不让导出功能直接用 {@link PickupRecord}：
 * PickupRecord 带 Room 与 androidx 注解，单元测试跑在普通 JVM 上，
 * 加载它就得把整个 Room 依赖拖进 classpath。这里用一份纯数据做中转，
 * 导出/导入的格式逻辑就能完全离线验证。
 */
public class PickupRow {

    /** 取件码 */
    public String code = "";
    /** 来源（菜鸟驿站等） */
    public String source = "";
    /** 地点 */
    public String place = "";
    /** 收到时间（epoch 毫秒） */
    public long receivedAt;
    /** 状态：PENDING / PICKED_UP */
    public String status = PickupRecord.STATUS_PENDING;
    /** 取走时间（epoch 毫秒，0 = 未取） */
    public long pickedAt;

    public PickupRow() {
    }

    public PickupRow(String code, String source, String place,
                     long receivedAt, String status, long pickedAt) {
        this.code = code;
        this.source = source;
        this.place = place;
        this.receivedAt = receivedAt;
        this.status = status;
        this.pickedAt = pickedAt;
    }

    public boolean isPending() {
        return PickupRecord.STATUS_PENDING.equals(status);
    }

    /** 从本地记录转成可导出的行 */
    public static PickupRow of(PickupRecord r) {
        PickupRow row = new PickupRow();
        row.code = r.code;
        row.source = r.source == null ? "" : r.source;
        row.place = r.place == null ? "" : r.place;
        row.receivedAt = r.receivedAt;
        row.status = r.status == null ? PickupRecord.STATUS_PENDING : r.status;
        row.pickedAt = r.pickedAt;
        return row;
    }
}
