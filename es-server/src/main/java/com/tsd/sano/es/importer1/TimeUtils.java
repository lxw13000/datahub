package com.tsd.sano.es.importer1;

import lombok.Data;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;

/**
 * LocalDate 和 LocalDateTime 常用操作工具类
 */
public class TimeUtils {
    public static final String YYYY_MM_DD = "yyyy-MM-dd";
    public static final String HH_MM_SS = "HH:mm:ss";
    public static final String BASIC = "yyyy-MM-dd HH:mm:ss";
    public static final String YYYYMMDD = "yyyyMMdd";
    public static final String YYYY_MM_DDHHMM = "yyyy-MM-dd HH:mm";
    public static final String yyyyMMddHHmmss = "yyyyMMddHHmmss";

    // ==================== 常用格式化器 ====================

    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(YYYY_MM_DD);
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(HH_MM_SS);
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(BASIC);
    public static final DateTimeFormatter DATETIME_MINUTE_FORMATTER = DateTimeFormatter.ofPattern(YYYY_MM_DDHHMM);
    public static final DateTimeFormatter COMPACT_DATE_FORMATTER = DateTimeFormatter.ofPattern(YYYYMMDD);
    public static final DateTimeFormatter COMPACT_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(yyyyMMddHHmmss);

    // ==================== 获取当前时间 ====================

    /**
     * 获取当前日期
     */
    public static LocalDate today() {
        return LocalDate.now();
    }

    /**
     * 获取当前时间
     */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 获取当前时间，截断到小时（分秒归零）
     */
    public static LocalDateTime nowTruncatedToHour() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * 获取当前时间，截断到分钟（秒归零）
     */
    public static LocalDateTime nowTruncatedToMinute() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * 获取当前时间，截断到分钟（秒归零）
     */
    public static LocalDateTime nowTruncatedToMinute(LocalDateTime localDateTime) {
        return localDateTime.truncatedTo(ChronoUnit.MINUTES);
    }
    /**
     * 获取当前时间，截断到秒（毫秒归零）
     */
    public static LocalDateTime nowTruncatedToSecond() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    // ==================== 日期边界 ====================

    /**
     * 获取某天的开始时间 00:00:00
     */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    /**
     * 获取某天的结束时间 23:59:59
     */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(23, 59, 59);
    }

    /**
     * 获取今天的开始时间 00:00:00
     */
    public static LocalDateTime startOfToday() {
        return startOfDay(LocalDate.now());
    }

    /**
     * 获取今天的结束时间 23:59:59
     */
    public static LocalDateTime endOfToday() {
        return endOfDay(LocalDate.now());
    }

    /**
     * 获取某个时间所在天的开始时间 00:00:00
     */
    public static LocalDateTime startOfDay(LocalDateTime dateTime) {
        return dateTime.toLocalDate().atStartOfDay();
    }

    /**
     * 获取某个时间所在天的结束时间 23:59:59
     */
    public static LocalDateTime endOfDay(LocalDateTime dateTime) {
        return dateTime.toLocalDate().atTime(23, 59, 59);
    }

    // ==================== 周边界 ====================

    /**
     * 获取某天所在周的周一
     */
    public static LocalDate startOfWeek(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    /**
     * 获取某天所在周的周日
     */
    public static LocalDate endOfWeek(LocalDate date) {
        return date.with(DayOfWeek.SUNDAY);
    }

    /**
     * 获取本周一
     */
    public static LocalDate startOfThisWeek() {
        return startOfWeek(LocalDate.now());
    }

    /**
     * 获取本周日
     */
    public static LocalDate endOfThisWeek() {
        return endOfWeek(LocalDate.now());
    }

    // ==================== 月边界 ====================

    /**
     * 获取某天所在月的第一天
     */
    public static LocalDate startOfMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    /**
     * 获取某天所在月的最后一天
     */
    public static LocalDate endOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * 获取本月第一天
     */
    public static LocalDate startOfThisMonth() {
        return startOfMonth(LocalDate.now());
    }

    /**
     * 获取本月最后一天
     */
    public static LocalDate endOfThisMonth() {
        return endOfMonth(LocalDate.now());
    }

    /**
     * 获取某天所在月第一天的开始时间 00:00:00
     */
    public static LocalDateTime startOfMonthTime(LocalDate date) {
        return startOfMonth(date).atStartOfDay();
    }

    /**
     * 获取某天所在月最后一天的结束时间 23:59:59
     */
    public static LocalDateTime endOfMonthTime(LocalDate date) {
        return endOfMonth(date).atTime(23, 59, 59);
    }

    // ==================== 年边界 ====================

    /**
     * 获取某天所在年的第一天
     */
    public static LocalDate startOfYear(LocalDate date) {
        return date.withDayOfYear(1);
    }

    /**
     * 获取某天所在年的最后一天
     */
    public static LocalDate endOfYear(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfYear());
    }

    /**
     * 获取今年第一天
     */
    public static LocalDate startOfThisYear() {
        return startOfYear(LocalDate.now());
    }

    /**
     * 获取今年最后一天
     */
    public static LocalDate endOfThisYear() {
        return endOfYear(LocalDate.now());
    }

    // ==================== 日期偏移 ====================

    /**
     * 日期加减天数
     */
    public static LocalDate plusDays(LocalDate date, long days) {
        return date.plusDays(days);
    }

    /**
     * 日期加减周数
     */
    public static LocalDate plusWeeks(LocalDate date, long weeks) {
        return date.plusWeeks(weeks);
    }

    /**
     * 日期加减月数
     */
    public static LocalDate plusMonths(LocalDate date, long months) {
        return date.plusMonths(months);
    }

    /**
     * 日期加减年数
     */
    public static LocalDate plusYears(LocalDate date, long years) {
        return date.plusYears(years);
    }

    /**
     * 时间加减小时
     */
    public static LocalDateTime plusHours(LocalDateTime dateTime, long hours) {
        return dateTime.plusHours(hours);
    }

    /**
     * 时间加减分钟
     */
    public static LocalDateTime plusMinutes(LocalDateTime dateTime, long minutes) {
        return dateTime.plusMinutes(minutes);
    }

    /**
     * 时间加减秒
     */
    public static LocalDateTime plusSeconds(LocalDateTime dateTime, long seconds) {
        return dateTime.plusSeconds(seconds);
    }

    /**
     * 获取昨天
     */
    public static LocalDate yesterday() {
        return LocalDate.now().minusDays(1);
    }

    /**
     * 获取明天
     */
    public static LocalDate tomorrow() {
        return LocalDate.now().plusDays(1);
    }

    /**
     * 获取N天前
     */
    public static LocalDate daysAgo(long days) {
        return LocalDate.now().minusDays(days);
    }

    /**
     * 获取N天后
     */
    public static LocalDate daysLater(long days) {
        return LocalDate.now().plusDays(days);
    }

    // ==================== 日期差值计算 ====================

    /**
     * 计算两个日期之间相差天数
     */
    public static long daysBetween(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * 计算两个日期之间相差月数
     */
    public static long monthsBetween(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start, end);
    }

    /**
     * 计算两个日期之间相差年数
     */
    public static long yearsBetween(LocalDate start, LocalDate end) {
        return ChronoUnit.YEARS.between(start, end);
    }

    /**
     * 计算两个时间之间相差小时数
     */
    public static long hoursBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.HOURS.between(start, end);
    }

    /**
     * 计算两个时间之间相差分钟数
     */
    public static long minutesBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    /**
     * 计算两个时间之间相差秒数
     */
    public static long secondsBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.SECONDS.between(start, end);
    }

    // ==================== 格式化与解析 ====================

    /**
     * LocalDate 格式化为 yyyy-MM-dd
     */
    public static String formatDate(LocalDate date) {
        return date != null ? date.format(DATE_FORMATTER) : null;
    }

    /**
     * LocalDateTime 格式化为 yyyy-MM-dd HH:mm:ss
     */
    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATETIME_FORMATTER) : null;
    }

    /**
     * LocalDate 格式化为指定格式
     */
    public static String formatDate(LocalDate date, String pattern) {
        return date != null ? date.format(DateTimeFormatter.ofPattern(pattern)) : null;
    }

    /**
     * LocalDateTime 格式化为指定格式
     */
    public static String formatDateTime(LocalDateTime dateTime, String pattern) {
        return dateTime != null ? dateTime.format(DateTimeFormatter.ofPattern(pattern)) : null;
    }

    /**
     * LocalDate 格式化为 yyyyMMdd（整数）
     */
    public static Integer toDateInt(LocalDate date) {
        return date != null ? Integer.parseInt(date.format(COMPACT_DATE_FORMATTER)) : null;
    }
    /**
     * str 格式化为 LocalDate
     */
    public static LocalDate intToDate(Integer dayInt) {
        return LocalDate.parse(dayInt.toString(), COMPACT_DATE_FORMATTER);
    }
    /**
     * LocalDateTime 格式化为 yyyyMMdd（整数）
     */
    public static Integer toDateInt(LocalDateTime dateTime) {
        return dateTime != null ? toDateInt(dateTime.toLocalDate()) : null;
    }

    /**
     * 字符串解析为 LocalDate（yyyy-MM-dd）
     */
    public static LocalDate parseDate(String dateStr) {
        return dateStr != null ? LocalDate.parse(dateStr, DATE_FORMATTER) : null;
    }

    /**
     * 字符串解析为 LocalDateTime（yyyy-MM-dd HH:mm:ss）
     */
    public static LocalDateTime parseDateTime(String dateTimeStr) {
        return dateTimeStr != null ? LocalDateTime.parse(dateTimeStr, DATETIME_FORMATTER) : null;
    }

    /**
     * 字符串按指定格式解析为 LocalDate
     */
    public static LocalDate parseDate(String dateStr, String pattern) {
        return dateStr != null ? LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(pattern)) : null;
    }

    /**
     * 字符串按指定格式解析为 LocalDateTime
     */
    public static LocalDateTime parseDateTime(String dateTimeStr, String pattern) {
        return dateTimeStr != null ? LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern(pattern)) : null;
    }

    /**
     * yyyyMMdd 整数解析为 LocalDate
     */
    public static LocalDate parseDateInt(Integer dateInt) {
        return dateInt != null ? LocalDate.parse(dateInt.toString(), COMPACT_DATE_FORMATTER) : null;
    }

    // ==================== 类型转换 ====================

    /**
     * LocalDate 转 LocalDateTime（当天 00:00:00）
     */
    public static LocalDateTime toDateTime(LocalDate date) {
        return date != null ? date.atStartOfDay() : null;
    }

    /**
     * LocalDate 转 LocalDateTime（指定时间）
     */
    public static LocalDateTime toDateTime(LocalDate date, int hour, int minute, int second) {
        return date != null ? date.atTime(hour, minute, second) : null;
    }

    /**
     * LocalDateTime 转 LocalDate
     */
    public static Date toDate(LocalDateTime dateTime) {

        return dateTime != null ? Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()) : null;
    }

    /**
     * LocalDate 转时间戳（毫秒）
     */
    public static Long toTimestamp(LocalDate date) {
        return date != null ? date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * LocalDateTime 转时间戳（毫秒）
     */
    public static Long toTimestamp(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }

    /**
     * 时间戳（毫秒）转 LocalDateTime
     */
    public static LocalDateTime fromTimestamp(Long timestamp) {
        return timestamp != null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()) : null;
    }

    /**
     * 时间戳（毫秒）转 LocalDate
     */
    public static LocalDate dateFromTimestamp(Long timestamp) {
        return timestamp != null ? fromTimestamp(timestamp).toLocalDate() : null;
    }

    /**
     * Date 转 LocalDateTime
     */
    public static LocalDateTime fromDate(Date date) {
        return date != null ? LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()) : null;
    }

    /**
     * Date 转 LocalDate
     */
    public static LocalDate localDateFromDate(Date date) {
        return date != null ? fromDate(date).toLocalDate() : null;
    }

    /**
     * LocalDate 转 Date
     */
    public static Date toDate(LocalDate date) {
        return date != null ? Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()) : null;
    }

    // ==================== 判断方法 ====================

    /**
     * 是否是今天
     */
    public static boolean isToday(LocalDate date) {
        return date != null && date.equals(LocalDate.now());
    }

    /**
     * 是否是今天
     */
    public static boolean isToday(LocalDateTime dateTime) {
        return dateTime != null && dateTime.toLocalDate().equals(LocalDate.now());
    }

    /**
     * 是否是闰年
     */
    public static boolean isLeapYear(LocalDate date) {
        return date != null && date.isLeapYear();
    }

    /**
     * 是否是周末
     */
    public static boolean isWeekend(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    /**
     * 日期是否在某个范围内（包含边界）
     */
    public static boolean isBetween(LocalDate date, LocalDate start, LocalDate end) {
        if (date == null || start == null || end == null) {
            return false;
        }
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * 时间是否在某个范围内（包含边界）
     */
    public static boolean isBetween(LocalDateTime dateTime, LocalDateTime start, LocalDateTime end) {
        if (dateTime == null || start == null || end == null) {
            return false;
        }
        return !dateTime.isBefore(start) && !dateTime.isAfter(end);
    }

    /**
     * 是否在当前时间之前
     */
    public static boolean isBeforeNow(LocalDateTime dateTime) {
        return dateTime != null && dateTime.isBefore(LocalDateTime.now());
    }

    /**
     * 是否在当前时间之后
     */
    public static boolean isAfterNow(LocalDateTime dateTime) {
        return dateTime != null && dateTime.isAfter(LocalDateTime.now());
    }

    // ==================== 获取信息 ====================

    /**
     * 获取星期几（1=周一，7=周日）
     */
    public static int getDayOfWeek(LocalDate date) {
        return date != null ? date.getDayOfWeek().getValue() : 0;
    }

    /**
     * 获取星期几中文
     */
    public static String getDayOfWeekChinese(LocalDate date) {
        if (date == null) {
            return null;
        }
        String[] weeks = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return weeks[date.getDayOfWeek().getValue() - 1];
    }

    /**
     * 获取当月天数
     */
    public static int getDaysOfMonth(LocalDate date) {
        return date != null ? date.lengthOfMonth() : 0;
    }

    /**
     * 获取当年天数
     */
    public static int getDaysOfYear(LocalDate date) {
        return date != null ? date.lengthOfYear() : 0;
    }

    /**
     * 获取年份
     */
    public static int getYear(LocalDate date) {
        return date != null ? date.getYear() : 0;
    }

    /**
     * 获取月份（1-12）
     */
    public static int getMonth(LocalDate date) {
        return date != null ? date.getMonthValue() : 0;
    }

    /**
     * 获取日（1-31）
     */
    public static int getDay(LocalDate date) {
        return date != null ? date.getDayOfMonth() : 0;
    }

    /**
     * 获取小时（0-23）
     */
    public static int getHour(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.getHour() : 0;
    }

    /**
     * 获取分钟（0-59）
     */
    public static int getMinute(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.getMinute() : 0;
    }

    /**
     * 获取秒（0-59）
     */
    public static int getSecond(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.getSecond() : 0;
    }


    // 周

    /** 计算本周自然周范围：[周一, 周日] */
    public static WeekRange getThisWeekRange() {
        LocalDate now = LocalDate.now();
        LocalDate weekStart = now.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        return new WeekRange(weekStart, weekEnd);
    }

    /** 计算上周自然周范围：[上周一, 上周日] */
    public static WeekRange getLastWeekRange() {
        WeekRange thisWeek = getThisWeekRange();
        LocalDate lastWeekStart = thisWeek.getStart().minusWeeks(1);
        LocalDate lastWeekEnd = lastWeekStart.plusDays(6);
        return new WeekRange(lastWeekStart, lastWeekEnd);
    }

    /**
     * 获取当前周的最后一天(周日)
     * @return 周日
     */
    public static LocalDate getThisWeekSunday() {
        return LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /**
     * 获取指定偏移周的周日
     * @param weekOffset 0 = 本周  1 = 上周  -1 = 下周
     *
     */
    public static LocalDate getWeekSunday(int weekOffset) {
        LocalDate base = LocalDate.now().minusWeeks(weekOffset);
        return base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /** 获取指定日期所在周的周日 */
    public static LocalDate getWeekSunday(LocalDate date) {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /**
     * 获取往前第 N 周的范围（周一到周日）
     * @param minusWeek 往前几周（1=上周，2=上上周） -  ps 如果是负数那就是未来周
     */
    public static WeekRange getMinusWeekRange(int minusWeek) {
        LocalDate now = LocalDate.now();
        LocalDate weekStart = now.with(DayOfWeek.MONDAY).minusWeeks(minusWeek);
        LocalDate weekEnd = weekStart.plusDays(6);
        return new WeekRange(weekStart, weekEnd);
    }



    /**
     * 生成自然日开始时间。
     *
     * @param localDate 统计日期
     * @return yyyy-MM-dd 00:00:00
     */
    public static String buildStartTime(LocalDate localDate) {
        return localDate + " 00:00:00";
    }

    /**
     * 生成自然日结束时间。
     *
     * @param localDate 统计日期
     * @return yyyy-MM-dd 23:59:59
     */
    public static String buildEndTime(LocalDate localDate) {
        return localDate + " 23:59:59";
    }

    /**
     * 周范围：
     * start 周一，end 周天
     */
    @Data
    public static class WeekRange {
        /**
         * 周一
         */
        private final LocalDate start;
        /**
         * 周天
         */
        private final LocalDate end;
    }
}