package com.scxd.model.enums;

public enum SyncMode {
    MERGE(1, "合并模式"),
    INSERT_ONLY(2, "仅插入"),
    CLEAN_INSERT(3, "清空后插入");

    private final int code;
    private final String desc;

    SyncMode(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() { return code; }
    public String getDesc() { return desc; }

    public static SyncMode fromCode(int code) {
        for (SyncMode m : values()) {
            if (m.code == code) return m;
        }
        return MERGE; // 默认合并模式
    }
}
