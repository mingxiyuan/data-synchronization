package com.trans.utils;

import lombok.extern.slf4j.Slf4j;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期工具方法
 */
@Slf4j
public class DateUtils {
    /**
     * 返回一个2019-04-11 11：14：30格式的时间
     * @return
     */
    public static Date getCurrentTime(){
        long currentTime = System.currentTimeMillis();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = new Date(currentTime);
        return date;
    }

    /* 获取当前时间的字符串 */
    public static String NowToString(){
        LocalDateTime localDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return localDateTime.format(formatter);
    }

    public static String NowToString2(){
        LocalDateTime localDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        return localDateTime.format(formatter);
    }

    public static String dateToString(Date time){
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return formatter.format(time);
    }

    /* 获取当前时间加day的时间 */
    public static Date getDateByDay(int day){
        LocalDateTime localDateTime = LocalDateTime.now();
        localDateTime = localDateTime.plusDays(day); //当前时间加上day天
        ZoneId zone = ZoneId.systemDefault();
        Instant instant = localDateTime.atZone(zone).toInstant();
        return Date.from(instant);
    }

    /* 获取当前时间与目标时间天数差 */
    public static double getday(Date date){
        if (date == null){
            return 0.0;
        }
        long currentTime = System.currentTimeMillis();
        long endTime = date.getTime();
        double res = currentTime - endTime;
        double out = Math.abs(res) / (1000 * 24 * 60 * 60);
        DecimalFormat df = new DecimalFormat("#.00");
        return Double.parseDouble(df.format(out));
    }

    /* 判断date是否失效 */
    public static boolean isTimeExpired(Date date){
        long currentTime = System.currentTimeMillis();
        if (date == null){
            return true;
        }
        if ((date.getTime() - currentTime) > 0){
            return false;
        }
        return true;
    }

    /* 判断当前时间是否在startTime 和 endTime之间 */
    public static boolean isEffectiveTime(String startTime, String endTime){
        if ("".equals(startTime) || "".equals(endTime)){
            return false;
        }
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end = LocalTime.parse(endTime);
        //开始时间在当前时间之前，并且结束时间在当前时间之后。 判断为true
        if(start.isBefore(now) && end.isAfter(now)){
            return true;
        }
        return false;
    }

    public static String getDate(){
        return new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
    }

    public static String getDateTime(){
        return new SimpleDateFormat("yyyyMMdd-HHmmssSSS").format(new Date());
    }

    public static Date dateStrFormatDate(String strDate, String strFormat){

        SimpleDateFormat sdf = new SimpleDateFormat(strFormat);
        try {
            return sdf.parse(strDate);
        } catch (ParseException e) {
            log.error("日期转换异常",e);
        }
        return new Date();
    }
}
