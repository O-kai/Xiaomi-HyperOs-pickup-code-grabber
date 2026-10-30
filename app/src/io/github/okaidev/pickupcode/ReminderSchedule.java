package io.github.okaidev.pickupcode;

import java.util.Calendar;

/**
 * 每日提醒的调度时间计算（纯 Java，不依赖 Android，可离线单测）。
 *
 * <p>提醒「今天该点已过就顺延到明天」这条规则很容易在改 AlarmManager 时被改坏，
 * 而它坏了不会报错、只会表现为「提醒再也不响了」，所以单独抽出来并加测试。
 */
public final class ReminderSchedule {

    private ReminderSchedule() {
    }

    /**
     * 下一次触发的时间戳。
     *
     * @param hour       每天触发的时（0-23）
     * @param minute     每天触发的分（0-59）
     * @param nowMillis  当前时刻
     * @return 下一次触发的 epoch 毫秒；今天该点还没到就是今天，否则顺延到明天
     */
    public static long nextTriggerAt(int hour, int minute, long nowMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMillis) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }
}
