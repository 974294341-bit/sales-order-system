package com.salesorder.platform;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 单号、时间与工作日计算（急插单 7 个工作日、评审 1 个工作日等规则用到）。 */
public final class Ids {
    private Ids() {}

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String next(String prefix) {
        return prefix + "-" + LocalDate.now().format(DAY) + "-" + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    public static String uuid() { return UUID.randomUUID().toString(); }

    public static String now() { return LocalDateTime.now().withNano(0).toString(); }

    public static String today() { return LocalDate.now().format(DATE); }

    public static String date(LocalDate d) { return d.format(DATE); }

    public static LocalDate parseDate(String s) {
        try { return LocalDate.parse(s); }
        catch (Exception e) { throw Errors.validation("日期格式应为 yyyy-MM-dd: " + s); }
    }

    /** 在当前日期基础上加 n 个工作日（跳过周六周日，法定节假日未纳入）。 */
    public static LocalDate plusWorkdays(LocalDate from, int n) {
        LocalDate d = from;
        int left = n;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) left--;
        }
        return d;
    }

    public static LocalDate plusWorkdays(int n) { return plusWorkdays(LocalDate.now(), n); }

    /** 返回 a 到 b 之间的工作日数（不含 a，含 b）。 */
    public static int workdaysBetween(LocalDate a, LocalDate b) {
        int count = 0;
        LocalDate d = a;
        while (d.isBefore(b)) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }
}
