package com.scxd.model.enums;

public enum SyncStatus {
    RUNNING(1, "运行中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败");

    private final int code;
    private final String desc;

    SyncStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() { return code; }
    public String getDesc() { return desc; }

    public static SyncStatus fromCode(int code) {
        for (SyncStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("未知同步状态码: " + code);
    }

    public boolean matches(Integer code) {
        return code != null && this.code == code;
    }
}
